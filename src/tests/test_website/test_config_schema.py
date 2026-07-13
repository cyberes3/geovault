"""
Tests for website.config.schema.GeoVaultConfig: field validators, and a two-way sync check
between config.example.yaml and the schema (the single source of truth for every valid config
key). This guards against the class of drift that let `processing.show_detailed_error_messages`
go documented in config.example.yaml but never actually wired up as a real Django setting.
"""
import typing
from pathlib import Path
from typing import get_args, get_origin

import yaml
from django.test import SimpleTestCase
from pydantic import BaseModel

from website.config.schema import ExtensionsConfig, GeoVaultConfig

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
