"""
Loads, overlays environment variable overrides on, and validates config.yaml.

Call `get_config()` to get the process-wide validated `GeoVaultConfig` singleton;
`website.settings` submodules read exclusively from it (or from the resulting
`django.conf.settings` values it produces).

Any invalid value in config.yaml raises `django.core.exceptions.ImproperlyConfigured` at
Django startup (settings-module import time), naming the offending field path, instead of
silently falling back to a default.
"""
import os
from pathlib import Path
from typing import Any, Optional, Union, get_args, get_origin

import yaml
from django.core.exceptions import ImproperlyConfigured
from pydantic import BaseModel, ValidationError

from geo_lib.logging.console import get_tagged_logger
from website.config.schema import GeoVaultConfig

logger = get_tagged_logger('config')

_CONFIG_FILENAME = 'config.yaml'


def get_config_path() -> Path:
    """Resolve config.yaml: GEOVAULT_CONFIG_PATH env var, else <backend>/config.yaml."""
    env_path = os.environ.get('GEOVAULT_CONFIG_PATH')
    if env_path:
        return Path(env_path)
    # website/config/loader.py -> website/config -> website -> backend
    backend_dir = Path(__file__).resolve().parent.parent.parent
    return backend_dir / _CONFIG_FILENAME


def _load_yaml(config_path: Path) -> dict:
    if not config_path.exists():
        logger.warning(
            "Config file not found at %s. Using default values for every setting. "
            "Create config.yaml or set GEOVAULT_CONFIG_PATH.",
            config_path,
        )
        return {}

    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            return yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        raise ImproperlyConfigured(f"Error parsing YAML config file {config_path}: {e}") from e
    except OSError as e:
        raise ImproperlyConfigured(f"Error reading config file {config_path}: {e}") from e


def _extract_nested_model(annotation: Any) -> Optional[type[BaseModel]]:
    """Return the BaseModel subclass a field annotation points to, unwrapping Optional[...]."""
    if isinstance(annotation, type) and issubclass(annotation, BaseModel):
        return annotation
    if get_origin(annotation) is Union:
        for arg in get_args(annotation):
            nested = _extract_nested_model(arg)
            if nested is not None:
                return nested
    return None


def _apply_env_overrides(model_cls: type[BaseModel], data: dict) -> dict:
    """
    Recursively overlay environment variable values onto the raw YAML dict, before Pydantic
    validation/type-coercion runs. A field opts into this by declaring
    `Field(..., json_schema_extra={"env": "ENV_VAR_NAME"})`; when that env var is set, its
    (string) value replaces whatever the YAML file has for that field.
    """
    result = dict(data)
    for field_name, field_info in model_cls.model_fields.items():
        extra = field_info.json_schema_extra
        env_var = extra.get('env') if isinstance(extra, dict) else None
        if env_var and env_var in os.environ:
            result[field_name] = os.environ[env_var]
            continue

        nested_model = _extract_nested_model(field_info.annotation)
        if nested_model is not None:
            sub_data = result.get(field_name)
            if sub_data is None:
                sub_data = {}
            if isinstance(sub_data, dict):
                result[field_name] = _apply_env_overrides(nested_model, sub_data)
    return result


def load_config(config_path: Optional[Path] = None) -> GeoVaultConfig:
    """Load, env-override, and validate config.yaml. Raises ImproperlyConfigured on any invalid value."""
    path = config_path or get_config_path()
    raw = _load_yaml(path)
    overridden = _apply_env_overrides(GeoVaultConfig, raw)

    try:
        config = GeoVaultConfig.model_validate(overridden)
    except ValidationError as e:
        raise ImproperlyConfigured(f"Invalid configuration in {path}:\n{e}") from e

    logger.info("Loaded configuration from %s", path)
    return config


_config: Optional[GeoVaultConfig] = None


def get_config() -> GeoVaultConfig:
    """Return the process-wide validated configuration singleton, loading it on first access."""
    global _config
    if _config is None:
        _config = load_config()
    return _config
