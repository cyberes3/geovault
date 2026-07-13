"""
Tests for website.config.schema.GeoVaultConfig: field validators, a two-way sync check
between config.example.yaml and the schema (the single source of truth for every valid config
key), and rejection of invalid types/values (fail-loud-at-startup behavior). This guards
against the class of drift that let `processing.show_detailed_error_messages` go documented
in config.example.yaml but never actually wired up as a real Django setting.
"""
import tempfile
import typing
from pathlib import Path
from typing import get_args, get_origin

import yaml
from django.core.exceptions import ImproperlyConfigured
from django.test import SimpleTestCase
from pydantic import BaseModel, ValidationError

from website.config.loader import load_config
from website.config.schema import CeleryConfig, ExtensionsConfig, GeoVaultConfig

_EXAMPLE_CONFIG_PATH = Path(__file__).resolve().parent.parent.parent / 'backend' / 'config.example.yaml'


def _leaf_paths(data, prefix=()):
    """Yield dot-separated paths to every scalar/list leaf in a nested dict."""
    if isinstance(data, dict):
        for key, value in data.items():
            yield from _leaf_paths(value, prefix + (key,))
    else:
        yield '.'.join(prefix)


def _nested_model(annotation) -> typing.Optional[type[BaseModel]]:
    """Return the BaseModel subclass an annotation points to, unwrapping Optional[...]."""
    if isinstance(annotation, type) and issubclass(annotation, BaseModel):
        return annotation
    if get_origin(annotation) is typing.Union:
        for arg in get_args(annotation):
            nested = _nested_model(arg)
            if nested is not None:
                return nested
    return None


def _schema_field_paths(model_cls, prefix=()):
    """Yield dot-separated paths to every field in a (nested) Pydantic model, depth-first."""
    for field_name, field_info in model_cls.model_fields.items():
        path = prefix + (field_name,)
        yield '.'.join(path)
        nested = _nested_model(field_info.annotation)
        if nested is not None and nested is not ExtensionsConfig:
            yield from _schema_field_paths(nested, path)


def _resolve_schema_path(path: str) -> bool:
    """
    True if a dot-separated path resolves to a real field on GeoVaultConfig (or one of its
    nested models). `extensions.*` is always considered valid, since ExtensionsConfig is a
    permissive container for per-extension plugin settings with no fixed shape.
    """
    parts = path.split('.')
    if parts[0] == 'extensions':
        return True
    model_cls = GeoVaultConfig
    for part in parts:
        if part not in model_cls.model_fields:
            return False
        nested = _nested_model(model_cls.model_fields[part].annotation)
        if nested is None:
            return True
        model_cls = nested
    return True


class TestConfigExampleSchemaSync(SimpleTestCase):
    """
    config.example.yaml is the human-facing documentation of every key GeoVaultConfig accepts.
    These tests catch drift in both directions so the example docs can't silently fall out of
    sync with the schema that's actually validated against config.yaml at startup.
    """

    def test_every_example_yaml_key_resolves_to_a_schema_field(self):
        """Every live (uncommented) key in config.example.yaml must resolve to a GeoVaultConfig field."""
        with open(_EXAMPLE_CONFIG_PATH, 'r', encoding='utf-8') as f:
            raw = yaml.safe_load(f) or {}
        bad_paths = [path for path in _leaf_paths(raw) if not _resolve_schema_path(path)]
        self.assertEqual(
            bad_paths, [],
            f"config.example.yaml has keys with no matching GeoVaultConfig field: {bad_paths}. "
            "Either add the field to the schema, or remove the stale key from the example file.",
        )

    def test_every_schema_field_is_mentioned_in_example_yaml(self):
        """
        Every schema field name should appear somewhere in config.example.yaml, whether as a live
        key or in a comment (e.g. an optional/advanced field shown commented-out). This is a
        best-effort textual check, not a structural one, since several fields are documented only
        in comments rather than as live YAML keys.
        """
        text = _EXAMPLE_CONFIG_PATH.read_text(encoding='utf-8')
        undocumented = [
            path for path in _schema_field_paths(GeoVaultConfig)
            if path.rsplit('.', 1)[-1] not in text
        ]
        self.assertEqual(
            undocumented, [],
            f"GeoVaultConfig field(s) not mentioned anywhere in config.example.yaml: {undocumented}. "
            "Document them (even commented-out) so operators know they exist.",
        )


class TestGeocodingSearchModeValidation(SimpleTestCase):
    """GeoVaultConfig.geocoding_search_mode: normalized to lowercase, invalid values disabled with a warning."""

    def test_missing_key_returns_none(self):
        config = GeoVaultConfig.model_validate({})
        self.assertIsNone(config.geocoding_search_mode)

    def test_maptiler_is_normalized_to_lowercase(self):
        config = GeoVaultConfig.model_validate({'geocoding_search_mode': 'maptiler'})
        self.assertEqual(config.geocoding_search_mode, 'maptiler')
        config = GeoVaultConfig.model_validate({'geocoding_search_mode': 'Maptiler'})
        self.assertEqual(config.geocoding_search_mode, 'maptiler')

    def test_google_is_accepted(self):
        config = GeoVaultConfig.model_validate({'geocoding_search_mode': 'google'})
        self.assertEqual(config.geocoding_search_mode, 'google')

    def test_invalid_value_returns_none(self):
        config = GeoVaultConfig.model_validate({'geocoding_search_mode': 'nominatim'})
        self.assertIsNone(config.geocoding_search_mode)


class TestGeoVaultConfigRejectsBadTypes(SimpleTestCase):
    """
    GeoVaultConfig.model_validate must reject bad types/values with a clear, field-path-naming
    error rather than silently coercing or defaulting -- the core "fail loud at startup" promise
    of the Pydantic schema replacing the old dot-path ConfigLoader.
    """

    def test_rejects_non_numeric_string_for_int_field(self):
        with self.assertRaises(ValidationError) as ctx:
            GeoVaultConfig.model_validate({'database': {'port': 'not-a-number'}})
        self.assertIn('database.port', str(ctx.exception))

    def test_rejects_non_boolean_string_for_bool_field(self):
        with self.assertRaises(ValidationError) as ctx:
            GeoVaultConfig.model_validate({'security': {'debug': 'maybe'}})
        self.assertIn('security.debug', str(ctx.exception))

    def test_rejects_wrong_type_for_list_field(self):
        with self.assertRaises(ValidationError) as ctx:
            GeoVaultConfig.model_validate({'security': {'additional_allowed_hosts': 'not-a-list'}})
        self.assertIn('security.additional_allowed_hosts', str(ctx.exception))

    def test_rejects_out_of_range_type_nested_two_levels_deep(self):
        with self.assertRaises(ValidationError) as ctx:
            GeoVaultConfig.model_validate({'database': {'pool': {'min_size': 'lots'}}})
        self.assertIn('database.pool.min_size', str(ctx.exception))

    def test_accepts_a_fully_valid_nested_config(self):
        config = GeoVaultConfig.model_validate({
            'database': {'port': 5432, 'pool': {'min_size': 2, 'max_size': 30}},
            'security': {'debug': True, 'additional_allowed_hosts': ['example.com']},
        })
        self.assertEqual(config.database.port, 5432)
        self.assertTrue(config.security.debug)


class TestCeleryConfigRejectsBadTypes(SimpleTestCase):
    """
    CeleryConfig closes the gap where 8 `celery.*` settings keys were read but never validated
    (see this module's docstring and Phase 5/6 of the backend cleanup). Its fields must reject
    bad values the same as every other config section.
    """

    def test_default_celery_config_is_valid(self):
        config = CeleryConfig.model_validate({})
        self.assertEqual(config.default_queue, 'default')
        self.assertFalse(config.task_always_eager)

    def test_rejects_non_boolean_task_always_eager(self):
        with self.assertRaises(ValidationError) as ctx:
            CeleryConfig.model_validate({'task_always_eager': 'yes-please'})
        self.assertIn('task_always_eager', str(ctx.exception))

    def test_rejects_non_numeric_worker_startup_timeout(self):
        with self.assertRaises(ValidationError) as ctx:
            CeleryConfig.model_validate({'worker_startup_timeout_seconds': 'soon'})
        self.assertIn('worker_startup_timeout_seconds', str(ctx.exception))

    def test_rejects_non_numeric_beat_heartbeat_max_age(self):
        with self.assertRaises(ValidationError) as ctx:
            CeleryConfig.model_validate({'beat_heartbeat_max_age_seconds': 'never'})
        self.assertIn('beat_heartbeat_max_age_seconds', str(ctx.exception))

    def test_rejects_bad_celery_section_nested_under_full_config(self):
        """The full GeoVaultConfig tree surfaces CeleryConfig field errors with a `celery.` prefix."""
        with self.assertRaises(ValidationError) as ctx:
            GeoVaultConfig.model_validate({'celery': {'beat_startup_wait_seconds': 'a while'}})
        self.assertIn('celery.beat_startup_wait_seconds', str(ctx.exception))


class TestLoadConfigFailsLoudOnInvalidValues(SimpleTestCase):
    """
    website.config.loader.load_config() is the actual startup entrypoint: any invalid value in
    config.yaml must raise ImproperlyConfigured naming the offending field, instead of silently
    falling back to a default (the bug class this whole schema replaces).
    """

    def _write_config(self, tmp_path: Path, contents: dict) -> Path:
        config_path = tmp_path / 'config.yaml'
        with open(config_path, 'w', encoding='utf-8') as f:
            yaml.safe_dump(contents, f)
        return config_path

    def test_raises_improperly_configured_for_bad_database_port(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = self._write_config(Path(tmp_dir), {'database': {'port': 'not-a-number'}})
            with self.assertRaises(ImproperlyConfigured) as ctx:
                load_config(config_path)
            self.assertIn('database.port', str(ctx.exception))

    def test_raises_improperly_configured_for_bad_celery_value(self):
        # worker_startup_timeout_seconds has no env-var override, so this exercises YAML-driven
        # validation directly rather than being masked by run-tests.sh's CELERY_TASK_ALWAYS_EAGER.
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = self._write_config(
                Path(tmp_dir), {'celery': {'worker_startup_timeout_seconds': 'not-a-number'}},
            )
            with self.assertRaises(ImproperlyConfigured) as ctx:
                load_config(config_path)
            self.assertIn('celery.worker_startup_timeout_seconds', str(ctx.exception))

    def test_valid_config_loads_without_error(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = self._write_config(Path(tmp_dir), {
                'database': {'port': 5432},
                'celery': {'worker_startup_timeout_seconds': 7},
            })
            config = load_config(config_path)
            self.assertEqual(config.database.port, 5432)
            self.assertEqual(config.celery.worker_startup_timeout_seconds, 7)

    def test_missing_config_file_loads_defaults_without_error(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / 'does-not-exist.yaml'
            config = load_config(config_path)
            self.assertIsInstance(config, GeoVaultConfig)
