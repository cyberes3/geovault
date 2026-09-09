"""
Import providers extract external ids before the property whitelist and receive
those ids again after FeatureStore rows are created.

`created_features` on ImportHookPayload is the source of truth after finalize.
"""
import logging
import traceback
from threading import Lock
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, Field

logger = logging.getLogger("website.extensions.import_provider")

EXTERNAL_IDS_FEATURE_KEY = "_gv_external_ids"

_providers: list[tuple[str, "ImportProvider"]] = []
_providers_lock = Lock()


class ExternalIds(BaseModel):
    """External identifiers extracted from a feature before property whitelist."""

    model_config = ConfigDict(extra="forbid")

    by_provider: dict[str, dict[str, str]] = Field(default_factory=dict)

    def merge(self, other: "ExternalIds") -> "ExternalIds":
        merged = {key: dict(value) for key, value in self.by_provider.items()}
        for provider, values in other.by_provider.items():
            existing = merged.setdefault(provider, {})
            existing.update(values)
        return ExternalIds(by_provider=merged)

    def is_empty(self) -> bool:
        return not any(self.by_provider.values())

    def to_storable(self) -> dict[str, dict[str, str]]:
        return {key: dict(value) for key, value in self.by_provider.items()}

    @classmethod
    def from_storable(cls, raw: Any) -> "ExternalIds":
        if not isinstance(raw, dict):
            return cls()
        by_provider: dict[str, dict[str, str]] = {}
        for provider, values in raw.items():
            if isinstance(provider, str) and isinstance(values, dict):
                by_provider[provider] = {str(k): str(v) for k, v in values.items()}
        return cls(by_provider=by_provider)


class ImportHookPayload(BaseModel):
    """Typed payload delivered to ImportProvider.on_import_finalized."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    import_item: Any
    user_id: int
    created_features: list[Any]
    external_ids: list[ExternalIds]


class ImportProvider(Protocol):
    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        """Read provider-specific ids from the original feature (before whitelist)."""

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        """React to a completed import. Must not raise into the import job (fail-open)."""


def register_import_provider(provider: ImportProvider) -> None:
    """Register an ImportProvider. Must be called from extension_ready()."""
    from website.extensions.capabilities import current_extension_name

    if not callable(getattr(provider, "extract_external_ids", None)):
        raise TypeError("ImportProvider must implement extract_external_ids")
    if not callable(getattr(provider, "on_import_finalized", None)):
        raise TypeError("ImportProvider must implement on_import_finalized")

    extension_name = current_extension_name()
    if extension_name is None:
        raise ValueError(
            "Cannot register ImportProvider outside of extension context. "
            "Register providers in extension_ready()."
        )

    with _providers_lock:
        _providers.append((extension_name, provider))
    logger.debug("Registered ImportProvider for extension %s", extension_name)


def unregister_import_providers(extension_name: str) -> int:
    """Remove every provider registered by `extension_name`. Returns how many were removed."""
    with _providers_lock:
        before = len(_providers)
        remaining = [(name, provider) for name, provider in _providers if name != extension_name]
        _providers[:] = remaining
        return before - len(remaining)


def list_import_providers() -> list[tuple[str, ImportProvider]]:
    with _providers_lock:
        return list(_providers)


def clear_import_providers() -> None:
    """Remove every registered provider. Tests use this to isolate cases."""
    with _providers_lock:
        _providers.clear()


def collect_external_ids(geojson: dict) -> ExternalIds:
    """Ask every provider for ids on this feature. Fail-open per provider."""
    merged = ExternalIds()
    for extension_name, provider in list_import_providers():
        try:
            extracted = provider.extract_external_ids(geojson)
            if extracted is None:
                continue
            if not isinstance(extracted, ExternalIds):
                extracted = ExternalIds.model_validate(extracted)
            merged = merged.merge(extracted)
        except Exception:
            logger.error(
                "ImportProvider.extract_external_ids failed for %s:\n%s",
                extension_name,
                traceback.format_exc(),
            )
    return merged


def attach_external_ids(feature: dict, external_ids: ExternalIds) -> None:
    """Store extracted ids on the feature dict (top-level, not properties)."""
    if external_ids.is_empty():
        feature.pop(EXTERNAL_IDS_FEATURE_KEY, None)
        return
    feature[EXTERNAL_IDS_FEATURE_KEY] = external_ids.to_storable()


def pop_external_ids(feature: dict) -> ExternalIds:
    """Remove and return stored ids so pydantic/whitelist never see them."""
    return ExternalIds.from_storable(feature.pop(EXTERNAL_IDS_FEATURE_KEY, None))


def execute_import_providers(import_item: Any, user_id: int, created_features: list[Any]) -> None:
    """
    Deliver ImportHookPayload to every provider.

    `external_ids` is parallel to `created_features` (same order). Features that
    were created without extracted ids receive an empty ExternalIds.
    """
    ids = [getattr(feature, EXTERNAL_IDS_FEATURE_KEY, ExternalIds()) for feature in created_features]
    payload = ImportHookPayload(
        import_item=import_item,
        user_id=user_id,
        created_features=created_features,
        external_ids=ids,
    )
    for extension_name, provider in list_import_providers():
        try:
            provider.on_import_finalized(payload)
        except Exception:
            logger.error(
                "ImportProvider.on_import_finalized failed for %s:\n%s",
                extension_name,
                traceback.format_exc(),
            )
