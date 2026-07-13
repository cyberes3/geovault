"""
Business logic for re-reverse-geocoding `FeatureStore` rows: removing/regenerating reverse
geocoding tags, or regenerating other system tags while preserving existing reverse geocoding
tags. Used by the `regeocode_features` management command, which owns all CLI/output concerns
and delegates the actual tag computation and persistence to `RegeocodeService`.
"""
from dataclasses import dataclass, field
from typing import Optional

from api.models import FeatureStore
from geo_lib.processing.logging import ImportLog
from geo_lib.reverse_geocoding.constants import REVERSE_GEOCODING_TAG_PREFIXES
from geo_lib.reverse_geocoding.location_tags import reverse_geocode_coordinates
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.processing.tagging.modules.reverse_geocoding import get_representative_points
from geo_lib.types.feature import LineStringFeature, MultiLineStringFeature, PointFeature, PolygonFeature

_GEOMETRY_TYPES_WITH_REVERSE_GEOCODING = ('point', 'multipoint', 'linestring', 'multilinestring')


@dataclass
class TagRegenerationResult:
    """Outcome of regenerating tags for a single feature."""
    updated: bool
    message: Optional[str] = None
    removed_tags: list[str] = field(default_factory=list)
    added_tags: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _feature_class_for_geometry(geometry_type: str):
    """Get the appropriate feature class for a geometry type, or None if unsupported."""
    match geometry_type.lower():
        case 'point' | 'multipoint':
            return PointFeature
        case 'linestring':
            return LineStringFeature
        case 'multilinestring':
            return MultiLineStringFeature
        case 'polygon' | 'multipolygon':
            return PolygonFeature
        case _:
            return None


class RegeocodeService:
    """
    Regenerates reverse geocoding tags (or other system tags, preserving reverse geocoding
    tags) for `FeatureStore` rows. Callers are responsible for iterating a queryset, wrapping
    calls in a transaction, and reporting `TagRegenerationResult`s to the user.
    """

    def __init__(self):
        self._geocoding_prefixes = tuple(f"{prefix}:" for prefix in REVERSE_GEOCODING_TAG_PREFIXES)

    def separate_tags(self, system_tags: list[str]) -> tuple[list[str], list[str]]:
        """Split `system_tags` into (reverse_geocoding_tags, other_tags)."""
        geocoding_tags = [tag for tag in system_tags if tag.startswith(self._geocoding_prefixes)]
        other_tags = [tag for tag in system_tags if not tag.startswith(self._geocoding_prefixes)]
        return geocoding_tags, other_tags

    def regenerate_other_tags(
        self,
        feature_store: FeatureStore,
        geojson: dict,
        preserved_geocoding_tags: list[str],
        dry_run: bool,
    ) -> TagRegenerationResult:
        """Regenerate non-reverse_geocoding tags while preserving reverse geocoding tags."""
        geometry_type = geojson.get('geometry', {}).get('type', '')
        feature_class = _feature_class_for_geometry(geometry_type)

        old_other_tags = [
            tag for tag in geojson.get('properties', {}).get('system_tags', [])
            if not tag.startswith(self._geocoding_prefixes)
        ]

        if not feature_class:
            # Unsupported geometry type - preserve all existing tags, nothing to regenerate.
            return TagRegenerationResult(updated=False)

        geojson.setdefault('properties', {})['geojson_hash'] = feature_store.geojson_hash

        feature_instance = feature_class(**geojson)
        import_log = ImportLog()
        # We don't pass filename/file_content, so source-file/source-device tags won't be
        # regenerated; all existing tags are preserved regardless.
        new_tags = generate_auto_tags(feature_instance, import_log, skip_reverse_geocoding=True)

        # Union: keep all old tags and add any new ones, never remove.
        all_other_tags = sorted(set(old_other_tags + new_tags))
        all_tags = sorted(set(all_other_tags + preserved_geocoding_tags))

        added = len(set(new_tags) - set(old_other_tags))
        warnings = [log_msg.msg for log_msg in import_log.get() if log_msg.level.value >= 30]

        if added == 0 and sorted(set(old_other_tags)) == all_other_tags:
            return TagRegenerationResult(updated=False, warnings=warnings)

        if not dry_run:
            geojson['properties']['system_tags'] = all_tags
            feature_store.geojson = geojson
            feature_store.save()

        parts = []
        if added > 0:
            parts.append(f'added {added}')
        if preserved_geocoding_tags:
            parts.append(f'preserved {len(preserved_geocoding_tags)} reverse geocoding')
        message = f'regenerated other tags ({", ".join(parts)})' if parts else 'regenerated other tags'
        return TagRegenerationResult(updated=True, message=message, warnings=warnings)

    def regenerate_geocoding_tags(
        self,
        feature_store: FeatureStore,
        geojson: dict,
        filtered_tags: list[str],
        dry_run: bool,
    ) -> TagRegenerationResult:
        """Regenerate reverse geocoding tags, preserving `filtered_tags` (the non-geocoding tags)."""
        original_tags = geojson.get('properties', {}).get('system_tags', [])
        original_geocoding_tags, _ = self.separate_tags(original_tags)
        geometry_type = geojson.get('geometry', {}).get('type', '').lower()

        if geometry_type not in _GEOMETRY_TYPES_WITH_REVERSE_GEOCODING:
            # Polygon (or unrecognized type) - just remove old reverse_geocoding tags, if any.
            if not original_geocoding_tags:
                return TagRegenerationResult(updated=False)
            self._save_tags(feature_store, geojson, filtered_tags, dry_run)
            return TagRegenerationResult(
                updated=True,
                message=f'removed {len(original_geocoding_tags)} tags',
                removed_tags=sorted(original_geocoding_tags),
            )

        feature_class = PointFeature if geometry_type in ('point', 'multipoint') else LineStringFeature
        feature_obj = feature_class(**geojson)
        points = get_representative_points(feature_obj)

        if not points:
            if not original_geocoding_tags:
                return TagRegenerationResult(updated=False)
            self._save_tags(feature_store, geojson, filtered_tags, dry_run)
            return TagRegenerationResult(
                updated=True,
                message=f'removed {len(original_geocoding_tags)} tags, no new tags',
                removed_tags=sorted(original_geocoding_tags),
            )

        all_location_tags: set[str] = set()
        warnings = []
        for lat, lon in points:
            try:
                location_tags, log_messages = reverse_geocode_coordinates(lat, lon)
                all_location_tags.update(location_tags)
                warnings.extend(log_msg.message for log_msg in log_messages if log_msg.level in ('ERROR', 'WARNING'))
            except Exception as e:
                warnings.append(f'Failed to geocode point ({lat}, {lon}): {e}')

        original_set = set(original_geocoding_tags)
        net_removed = sorted(original_set - all_location_tags)
        net_added = sorted(all_location_tags - original_set)

        if not net_removed and not net_added:
            return TagRegenerationResult(updated=False, warnings=warnings)

        all_tags_sorted = sorted(all_location_tags.union(filtered_tags))
        self._save_tags(feature_store, geojson, all_tags_sorted, dry_run)

        msg_parts = []
        if net_removed:
            msg_parts.append(f'removed {len(net_removed)} tags')
        if net_added:
            msg_parts.append(f'added {len(net_added)} tags')
        return TagRegenerationResult(
            updated=True,
            message=', '.join(msg_parts),
            removed_tags=net_removed,
            added_tags=net_added,
            warnings=warnings,
        )

    @staticmethod
    def _save_tags(feature_store: FeatureStore, geojson: dict, new_tags: list[str], dry_run: bool) -> None:
        if dry_run:
            return
        geojson.setdefault('properties', {})['system_tags'] = new_tags
        feature_store.geojson = geojson
        feature_store.save()
