"""Typed, frozen extension manifest loaded from manifest.toml."""
from enum import Enum
from pathlib import Path

import tomllib
from pydantic import BaseModel, ConfigDict, Field, field_validator


class MapEngine(str, Enum):
    MAPLIBRE = "maplibre"
    NONE = "none"


class ExtensionManifest(BaseModel):
    """Validated record for one first-party extension."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    name: str
    version: str
    enabled_by_default: bool = True
    icon: str | None = None
    map_route: bool = False
    public_share_route: bool = False
    description: str | None = None
    umd_global: str | None = None
    requires_map_engine: MapEngine = MapEngine.NONE
    dashboard_widget: str | None = None

    @field_validator("name")
    @classmethod
    def name_is_snake_case(cls, value: str) -> str:
        if not value or not value.replace("_", "").isalnum() or value[0].isdigit():
            raise ValueError("name must be a non-empty snake_case identifier")
        if value != value.lower():
            raise ValueError("name must be lowercase snake_case")
        return value

    @property
    def kebab_name(self) -> str:
        return self.name.replace("_", "-")


def load_manifest(manifest_path: Path) -> ExtensionManifest:
    """Parse and validate a manifest.toml file."""
    with manifest_path.open("rb") as handle:
        data = tomllib.load(handle)
    return ExtensionManifest.model_validate(data)


def discover_manifest_path(extension_dir: Path) -> Path | None:
    candidate = extension_dir / "manifest.toml"
    if candidate.is_file():
        return candidate
    return None
