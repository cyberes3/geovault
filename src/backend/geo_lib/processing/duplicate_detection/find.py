import json
import traceback
from datetime import datetime
from typing import List, Dict, Optional, Tuple, Literal

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection import _logger
from geo_lib.processing.duplicate_detection.constants import (
    COORDINATE_TOLERANCE,
    GEOJSON_GEOM_TYPE_NAMES,
)
from geo_lib.processing.duplicate_detection.geometry_matcher import (
    FeatureStoreGeometryLookup,
    GeometryDuplicateContext,
    GeometryDuplicateMatcher,
)
from geo_lib.processing.duplicate_detection.helpers import _normalize_feature_for_hashing
from geo_lib.processing.duplicate_detection.mapping import _build_queue_hash_map
from geo_lib.processing.duplicate_detection.models import DuplicateSource, DuplicateMatchType
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.spatial.coordinates import geometries_match, normalize_coordinates
from website.settings_utils import get_required_setting


def _make_geometry_context(
        user_id: int,
        exclude_queue_id: Optional[int],
        exclude_timestamp: Optional[datetime],
        source_filter: Optional[Literal['feature_store', 'cross_queue']],
) -> GeometryDuplicateContext:
    return GeometryDuplicateContext(
        user_id=user_id,
        source_filter=source_filter,
        exclude_queue_id=exclude_queue_id,
        exclude_timestamp=exclude_timestamp,
    )


def _find_library_matches_for_batch_item(
        feature: Dict,
        normalized_coords: List,
        geometry: GEOSGeometry,
        user_id: int,
) -> List[Dict]:
    """dwithin prefilter + full geometries_match confirmation for one feature."""
    feature_geom_type = feature.get('geometry', {}).get('type', '').lower()
    candidates = FeatureStore.objects.filter(
        user_id=user_id,
        geometry__dwithin=(geometry, COORDINATE_TOLERANCE),
    ).values('id', 'geojson', 'timestamp')

    matches = []
    for existing in candidates:
        formatted_existing = _normalize_feature_for_hashing(existing)
        existing_geojson = formatted_existing['geojson']
        existing_geom_type = existing_geojson.get('geometry', {}).get('type', '').lower()
        existing_coords = existing_geojson.get('geometry', {}).get('coordinates', [])

        if (
            existing_geom_type == feature_geom_type
            and geometries_match(normalized_coords, normalize_coordinates(existing_coords))
        ):
            matches.append(formatted_existing)

    return matches


def _find_geometry_duplicates_batched(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[str] = None,
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """Large-file geometry duplicate detection with batched PostGIS queries."""
    import_log = ImportLog()
    context = _make_geometry_context(user_id, exclude_queue_id, exclude_timestamp, source_filter)
    matcher = GeometryDuplicateMatcher(context)

    unique_features: List[Dict] = []
    duplicate_features: List[Dict] = []

    features_by_type: Dict[str, List[Tuple[int, Dict, List]]] = {
        'point': [],
        'linestring': [],
        'polygon': [],
        'multilinestring': [],
        'multipolygon': [],
        'multipoint': [],
        'geometrycollection': [],
    }

    for i, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates or geom_type not in features_by_type:
            unique_features.append(feature)
            continue

        features_by_type[geom_type].append((i, feature, coordinates))

    batch_size = get_required_setting('DUPLICATE_DETECTION_BATCH_SIZE')

    for geom_type, type_features in features_by_type.items():
        if not type_features:
            continue

        for batch_start in range(0, len(type_features), batch_size):
            batch_end = min(batch_start + batch_size, len(type_features))
            batch_features = type_features[batch_start:batch_end]
            batch_geometries: List[Tuple[int, Dict, List, GEOSGeometry]] = []

            for idx, feature, coordinates in batch_features:
                if geom_type == 'geometrycollection':
                    duplicate_info = None
                    if context.checks_feature_store:
                        collection_matches = (
                            FeatureStoreGeometryLookup.find_geometry_collection_matches(
                                coordinates, user_id
                            )
                        )
                        if collection_matches:
                            duplicate_info = GeometryDuplicateMatcher._build_duplicate_payload(
                                feature, collection_matches
                            )
                    if duplicate_info is None:
                        duplicate_info = matcher.resolve(feature, library_matches=[])
                    if duplicate_info:
                        duplicate_features.append(duplicate_info)
                    else:
                        unique_features.append(feature)
                    continue

                try:
                    normalized_coords = normalize_coordinates(coordinates)
                    geom_type_name = GEOJSON_GEOM_TYPE_NAMES.get(geom_type, geom_type.title())
                    geom_data = {
                        'type': geom_type_name,
                        'coordinates': normalized_coords,
                    }
                    geos_geometry = GEOSGeometry(json.dumps(geom_data))
                    batch_geometries.append((idx, feature, normalized_coords, geos_geometry))
                except Exception:
                    import_log.add(
                        f"Failed to create geometry for feature {idx}",
                        'Find Coordinate Duplicates',
                    )
                    _logger.error(
                        f"Failed to create geometry for feature {idx}: {traceback.format_exc()}"
                    )
                    duplicate_info = matcher.resolve(feature)
                    if duplicate_info:
                        duplicate_features.append(duplicate_info)
                    else:
                        unique_features.append(feature)

            if not batch_geometries:
                continue

            try:
                library_lookup: Dict[int, List[Dict]] = {}

                if context.checks_feature_store:
                    for idx, feature, normalized_coords, geos_geometry in batch_geometries:
                        matches = _find_library_matches_for_batch_item(
                            feature, normalized_coords, geos_geometry, user_id
                        )
                        if matches:
                            library_lookup[idx] = matches

                for idx, feature, _normalized_coords, _geos_geometry in batch_geometries:
                    duplicate_info = matcher.resolve(
                        feature,
                        library_matches=library_lookup.get(idx, []),
                    )
                    if duplicate_info:
                        duplicate_features.append(duplicate_info)
                    else:
                        unique_features.append(feature)

            except Exception:
                import_log.add(
                    "Batch query encountered an issue, processing individually",
                    'Duplicate Detection',
                    DatabaseLogLevel.WARNING,
                )
                _logger.warning(
                    f"Batch query failed for {geom_type} features: {traceback.format_exc()}"
                )
                for _idx, feature, _coordinates in batch_features:
                    duplicate_info = matcher.resolve(feature)
                    if duplicate_info:
                        duplicate_features.append(duplicate_info)
                    else:
                        unique_features.append(feature)

    return unique_features, duplicate_features, import_log


def _find_hash_duplicates(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[Literal['feature_store', 'cross_queue']] = None
) -> List[Dict]:
    """
    Find features that have duplicate hashes in the existing featurestore and/or import queue.
    """
    if not features:
        return []

    existing_store_hashes = set()
    hash_to_store_id = {}
    if source_filter != 'cross_queue':
        existing_store_hashes = set(
            FeatureStore.objects.filter(user_id=user_id).values_list('geojson_hash', flat=True)
        )

        for feature in FeatureStore.objects.filter(
            user_id=user_id, geojson_hash__isnull=False
        ).values('id', 'geojson_hash', 'geojson', 'timestamp'):
            hash_to_store_id[feature['geojson_hash']] = {
                'id': feature['id'],
                'geojson': feature['geojson'],
                'timestamp': feature['timestamp'],
            }

    queue_hash_to_item = {}
    queue_hash_to_feature = {}
    if source_filter != 'feature_store':
        queue_hash_to_item, queue_hash_to_feature = _build_queue_hash_map(
            user_id, exclude_queue_id, exclude_timestamp
        )

    hash_duplicates = []

    for feature in features:
        geojson_hash = feature.get('properties', {}).get('geojson_hash')
        if not geojson_hash:
            geojson_hash = generate_geojson_hash(feature)

        if geojson_hash in existing_store_hashes:
            existing_features_list = []
            if geojson_hash in hash_to_store_id:
                store_feature = hash_to_store_id[geojson_hash]
                existing_features_list = [_normalize_feature_for_hashing(store_feature)]

            hash_duplicates.append({
                'feature': feature,
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': existing_features_list,
            })
        elif geojson_hash in queue_hash_to_item:
            queue_info = queue_hash_to_item[geojson_hash]
            queue_existing = {
                'id': queue_info['queue_item_id'],
                'name': queue_info['queue_item_filename'],
                'type': feature.get('geometry', {}).get('type', 'Unknown'),
                'timestamp': None,
                'geojson': queue_hash_to_feature[geojson_hash],
                'feature_index': queue_info.get('feature_index', 0),
            }

            hash_duplicates.append({
                'feature': feature,
                'source': DuplicateSource.CROSS_QUEUE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': [queue_existing],
            })

    return hash_duplicates


def _find_geometry_duplicates(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[Literal['feature_store', 'cross_queue']] = None,
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """Find features with duplicate geometry in the feature library and/or import queue."""
    import_log = ImportLog()

    if not features:
        return features, [], import_log

    batch_threshold = get_required_setting('DUPLICATE_DETECTION_BATCH_THRESHOLD')
    if len(features) > batch_threshold:
        return _find_geometry_duplicates_batched(
            features, user_id, exclude_queue_id, exclude_timestamp, source_filter
        )

    context = _make_geometry_context(user_id, exclude_queue_id, exclude_timestamp, source_filter)
    matcher = GeometryDuplicateMatcher(context)
    unique_features, duplicate_features = matcher.partition(features)

    return unique_features, duplicate_features, import_log
