"""Validate collection tag rules and pinned features."""

from typing import Sequence

from api.models import FeatureStore
from geo_lib.tags.tag_index import TagIndex


class CollectionEditorError(ValueError):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class CollectionEditor:
    @staticmethod
    def validate_rules(user, tags: Sequence[str] | None) -> list[str]:
        if not tags:
            return []
        cleaned: list[str] = []
        seen: set[str] = set()
        for tag in tags:
            if not isinstance(tag, str) or not tag.strip():
                continue
            value = tag.strip()
            if value.endswith(':'):
                raise CollectionEditorError('Collection tag rules cannot use prefix-form tags')
            if value in seen:
                continue
            seen.add(value)
            cleaned.append(value)
        if not cleaned:
            return []
        existing = {
            name
            for name in cleaned
            if TagIndex.exists(user.id, name, scope=None)
        }
        return [tag for tag in cleaned if tag in existing]

    @staticmethod
    def validate_pins(user, feature_ids: Sequence[int] | None) -> list[int]:
        if not feature_ids:
            return []
        requested = []
        seen: set[int] = set()
        for feature_id in feature_ids:
            if not isinstance(feature_id, int) or feature_id in seen:
                continue
            seen.add(feature_id)
            requested.append(feature_id)
        if not requested:
            return []
        allowed = set(
            FeatureStore.objects.owned_by(user).main_map().with_geometry()
            .filter(id__in=requested)
            .values_list('id', flat=True)
        )
        return [feature_id for feature_id in requested if feature_id in allowed]
