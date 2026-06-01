"""
Geometry duplicate matching for import queue and feature library.

Single resolution path: library (optional spatial prefilter) + queue, with feature-store
priority. Used by both batched and non-batched duplicate detection.
"""
import json
import traceback
from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Optional

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore
from geo_lib.processing.duplicate_detection import _logger
from geo_lib.processing.duplicate_detection.constants import COORDINATE_TOLERANCE, GEOM_TYPE_MAPPING
from geo_lib.processing.duplicate_detection.helpers import _normalize_feature_for_hashing
from geo_lib.processing.duplicate_detection.mapping import (
    QueueGeometryEntry,
    _build_queue_geometry_entries,
    _find_queue_geometry_match,
)
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource
from geo_lib.spatial.coordinates import geometries_match, normalize_coordinates


@dataclass(frozen=True)
class GeometryDuplicateContext:
    user_id: int
    source_filter: Optional[str] = None
    exclude_queue_id: Optional[int] = None
    exclude_timestamp: Optional[datetime] = None

    @property
    def checks_feature_store(self) -> bool:
        return self.source_filter != 'cross_queue'

    @property
    def checks_cross_queue(self) -> bool:
        return self.source_filter != 'feature_store' and self.exclude_queue_id is not None


class FeatureStoreGeometryLookup:
    """PostGIS-backed geometry duplicate lookup against imported features."""

    @staticmethod
    def find_matches(coordinates: List, geom_type: str, user_id: int) -> List[Dict]:
        try:
            normalized_coords = normalize_coordinates(coordinates)
            if not normalized_coords or geom_type not in GEOM_TYPE_MAPPING:
                return []

            geojson_geom = {
                'type': GEOM_TYPE_MAPPING[geom_type],
                'coordinates': normalized_coords,
            }

            try:
                target_geometry = GEOSGeometry(json.dumps(geojson_geom))
            except Exception as exc:
                _logger.debug(f"Failed to create geometry for duplicate check: {exc}")
                return []

            candidates = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__dwithin=(target_geometry, COORDINATE_TOLERANCE),
            ).values('id', 'geojson', 'timestamp')

            matches = []
            for feat in candidates:
                feat_geojson = (
                    feat['geojson']
                    if isinstance(feat['geojson'], dict)
                    else json.loads(feat['geojson'])
                )
                feat_geom_type = feat_geojson.get('geometry', {}).get('type', '').lower()
                feat_coords = feat_geojson.get('geometry', {}).get('coordinates', [])

                if feat_geom_type == geom_type and geometries_match(
                    normalized_coords,
                    normalize_coordinates(feat_coords),
                ):
                    matches.append(feat)

            return [_normalize_feature_for_hashing(feature) for feature in matches]

        except Exception:
            _logger.error(
                f"Error finding existing features by coordinates: {traceback.format_exc()}"
            )
            return []

    @staticmethod
    def find_geometry_collection_matches(geometries: List, user_id: int) -> List[Dict]:
        try:
            for geometry in geometries:
                geom_type = geometry.get('type', '').lower()
                coordinates = geometry.get('coordinates', [])
                if coordinates:
                    matches = FeatureStoreGeometryLookup.find_matches(
                        coordinates, geom_type, user_id
                    )
                    if matches:
                        return matches
            return []
        except Exception:
            _logger.error(
                f"Error finding geometry collection duplicates: {traceback.format_exc()}"
            )
            return []


class GeometryDuplicateMatcher:
    """Resolves whether a feature is a geometry duplicate and builds duplicate payloads."""

    def __init__(self, context: GeometryDuplicateContext) -> None:
        self._context = context
        self._queue_entries: List[QueueGeometryEntry] = []
        if context.checks_cross_queue:
            self._queue_entries = _build_queue_geometry_entries(
                context.user_id,
                context.exclude_queue_id,
                context.exclude_timestamp,
            )

    def resolve(
            self,
            feature: Dict,
            library_matches: Optional[List[Dict]] = None,
    ) -> Optional[Dict]:
        """
        Classify a feature as a geometry duplicate or not.

        library_matches:
            None — query the feature store when enabled.
            [] — skip library lookup (already known non-duplicate).
            [...] — use these library matches (batched path).
        """
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates:
            return None

        existing_refs: List[Dict] = []

        if self._context.checks_feature_store:
            if library_matches is None:
                library_matches = FeatureStoreGeometryLookup.find_matches(
                    coordinates, geom_type, self._context.user_id
                )
            existing_refs.extend(library_matches)

        if self._context.checks_cross_queue:
            queue_match = _find_queue_geometry_match(
                geom_type, coordinates, self._queue_entries
            )
            if queue_match:
                existing_refs.append(queue_match.to_existing_feature_ref(geom_type))

        if not existing_refs:
            return None

        return self._build_duplicate_payload(feature, existing_refs)

    def partition(
            self,
            features: List[Dict],
    ) -> tuple[List[Dict], List[Dict]]:
        unique_features: List[Dict] = []
        duplicate_features: List[Dict] = []

        for feature in features:
            duplicate_info = self.resolve(feature)
            if duplicate_info:
                duplicate_features.append(duplicate_info)
            else:
                unique_features.append(feature)

        return unique_features, duplicate_features

    @staticmethod
    def _build_duplicate_payload(feature: Dict, existing_refs: List[Dict]) -> Optional[Dict]:
        store_refs = [
            ref for ref in existing_refs
            if ref.get('timestamp') is not None
        ]
        queue_refs = [
            ref for ref in existing_refs
            if ref.get('timestamp') is None
        ]

        if store_refs:
            source = DuplicateSource.FEATURE_STORE
            existing_for_dup = store_refs
        elif queue_refs:
            source = DuplicateSource.CROSS_QUEUE
            existing_for_dup = queue_refs
        else:
            _logger.error(
                "Geometry duplicate refs had no feature-store or cross-queue source: %s",
                existing_refs,
            )
            return None

        return {
            'feature': feature,
            'source': source,
            'match_type': DuplicateMatchType.GEOMETRY,
            'existing_features': existing_for_dup,
        }
