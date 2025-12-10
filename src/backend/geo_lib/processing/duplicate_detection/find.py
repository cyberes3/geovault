import json
import traceback
from datetime import datetime
from typing import List, Dict, Optional, Tuple, Literal

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection import _logger
from geo_lib.processing.duplicate_detection.constants import GEOM_TYPE_MAPPING, COORDINATE_TOLERANCE
from geo_lib.processing.duplicate_detection.helpers import _normalize_feature_for_hashing
from geo_lib.processing.duplicate_detection.mapping import _build_queue_coords_map, _build_queue_hash_map
from geo_lib.processing.duplicate_detection.models import DuplicateSource, DuplicateMatchType
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.spatial.coordinates import normalize_coordinates
from website.settings_utils import get_required_setting


def _find_geometry_duplicates_batched(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[str] = None
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Optimized duplicate detection for large files using batched database queries.
    """
    import_log = ImportLog()
    unique_features = []
    duplicate_features = []
    # Track counts by source type
    feature_store_coord_count = 0
    cross_queue_coord_count = 0

    # Group features by geometry type for more efficient queries
    features_by_type = {'point': [], 'linestring': [], 'polygon': [], 'multilinestring': [], 'multipolygon': [], 'multipoint': [], 'geometrycollection': []}

    for i, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates or geom_type not in features_by_type:
            unique_features.append(feature)
            continue

        features_by_type[geom_type].append((i, feature, coordinates))

    # Process each geometry type in batches
    for geom_type, type_features in features_by_type.items():
        if not type_features:
            continue

        # Process in batches to avoid memory issues
        batch_size = get_required_setting('DUPLICATE_DETECTION_BATCH_SIZE')
        for batch_start in range(0, len(type_features), batch_size):
            batch_end = min(batch_start + batch_size, len(type_features))
            batch_features = type_features[batch_start:batch_end]

            # Create geometries for this batch
            # Normalize coordinates first to handle floating point precision differences
            batch_geometries = []
            for idx, feature, coordinates in batch_features:
                try:
                    # Normalize coordinates before creating geometry to ensure consistent WKT representation
                    normalized_coords = normalize_coordinates(coordinates)

                    # Handle different geometry type naming conventions
                    if geom_type == 'multilinestring':
                        geom_type_name = 'MultiLineString'
                    elif geom_type == 'multipolygon':
                        geom_type_name = 'MultiPolygon'
                    elif geom_type == 'multipoint':
                        geom_type_name = 'MultiPoint'
                    elif geom_type == 'geometrycollection':

                        # GeometryCollection needs special handling - skip batching for now
                        # and use the regular duplicate detection logic
                        existing_features = _find_geometry_collection_duplicates(coordinates, user_id)
                        if existing_features:
                            duplicate_info = {
                                'feature': feature,
                                'source': DuplicateSource.FEATURE_STORE,
                                'match_type': DuplicateMatchType.GEOMETRY,
                                'existing_features': existing_features
                            }
                            duplicate_features.append(duplicate_info)
                            feature_store_coord_count += 1
                        else:
                            unique_features.append(feature)
                        continue
                    else:
                        geom_type_name = geom_type.title()

                    # Create GEOS object temporarily for spatial query (not stored in feature dict)
                    geom_data = {
                        'type': geom_type_name,
                        'coordinates': normalized_coords
                    }
                    geometry = GEOSGeometry(json.dumps(geom_data))
                    batch_geometries.append((idx, feature, normalized_coords, geometry))
                except:
                    import_log.add(f"Failed to create geometry for feature {idx}", 'Find Coordinate Duplicates')
                    _logger.error(f"Failed to create geometry for feature {idx}: {traceback.format_exc()}")
                    unique_features.append(feature)

            if not batch_geometries:
                continue

            # Single database query for the entire batch using spatial index (only if not filtering for cross_queue)
            try:
                # Extract GEOS objects for spatial query (temporary, not stored in feature dicts)
                geometries = [geom for _, _, _, geom in batch_geometries]

                # Create a lookup map for existing features using normalized coordinates
                # This handles floating point precision differences better than WKT comparison
                existing_lookup = {}

                # Query FeatureStore only if not filtering for cross_queue
                if source_filter != 'cross_queue':
                    existing_features = FeatureStore.objects.filter(
                        user_id=user_id,
                        geometry__in=geometries
                    ).values('id', 'geojson', 'timestamp')

                    for existing in existing_features:
                        # Format the feature using the helper
                        formatted_existing = _normalize_feature_for_hashing(existing)
                        existing_geojson = formatted_existing['geojson']

                        # Get coordinates from formatted geojson and normalize them
                        existing_coords = existing_geojson.get('geometry', {}).get('coordinates', [])
                        if existing_coords:
                            normalized_existing_coords = normalize_coordinates(existing_coords)

                            # Use normalized coordinates as key for lookup
                            coords_key = json.dumps(normalized_existing_coords, sort_keys=True)
                            if coords_key not in existing_lookup:
                                existing_lookup[coords_key] = []
                            existing_lookup[coords_key].append(formatted_existing)

                # Check each feature in the batch using normalized coordinate comparison
                for idx, feature, normalized_coords, _ in batch_geometries:
                    # Use the already normalized coordinates
                    coords_key = json.dumps(normalized_coords, sort_keys=True)

                    if coords_key in existing_lookup:

                        # This is a duplicate from FeatureStore
                        duplicate_info = {
                            'feature': feature,
                            'source': DuplicateSource.FEATURE_STORE,
                            'match_type': DuplicateMatchType.GEOMETRY,
                            'existing_features': existing_lookup[coords_key]
                        }
                        duplicate_features.append(duplicate_info)
                        feature_store_coord_count += 1
                    else:
                        unique_features.append(feature)

            except:
                import_log.add(f"Batch query encountered an issue, processing individually", 'Duplicate Detection', DatabaseLogLevel.WARNING)
                _logger.warning(f"Batch query failed for {geom_type} features: {traceback.format_exc()}")
                for idx, feature, coordinates in batch_features:
                    unique_features.append(feature)

    # Also check for cross-queue coordinate duplicates if needed (only if not filtering for feature_store)
    if source_filter != 'feature_store' and exclude_queue_id is not None:
        # Build coordinate map from queue items
        queue_coords_map = _build_queue_coords_map(user_id, exclude_queue_id, exclude_timestamp)

        # Check features that weren't already marked as duplicates
        # Note: We don't add queue matches to feature_store duplicates due to priority rule
        # Feature_store duplicates take precedence over cross_queue duplicates
        duplicate_geojson_hashes = {generate_geojson_hash(dup_info['feature']) for dup_info in duplicate_features if dup_info.get('feature')}

        for feature in unique_features[:]:  # Use slice copy to modify during iteration
            geojson_hash = generate_geojson_hash(feature)
            if geojson_hash in duplicate_geojson_hashes:
                continue

            feature_geom = feature.get('geometry', {})
            feature_coords = feature_geom.get('coordinates')
            feature_type = feature_geom.get('type', '').lower()

            if feature_coords:
                normalized_feature_coords = normalize_coordinates(feature_coords)
                coords_key = (feature_type, json.dumps(normalized_feature_coords, sort_keys=True))

                if coords_key in queue_coords_map:
                    queue_match = queue_coords_map[coords_key]
                    queue_existing = {
                        'id': queue_match['queue_item_id'],
                        'name': queue_match['queue_item_filename'],
                        'type': feature_type,
                        'timestamp': None,
                        'geojson': queue_match['feature'],
                        'feature_index': queue_match.get('feature_index', 0)
                    }

                    duplicate_info = {
                        'feature': feature,
                        'source': DuplicateSource.CROSS_QUEUE,
                        'match_type': DuplicateMatchType.GEOMETRY,
                        'existing_features': [queue_existing]
                    }
                    duplicate_features.append(duplicate_info)
                    unique_features.remove(feature)
                    cross_queue_coord_count += 1

    return unique_features, duplicate_features, import_log


def _find_existing_features_by_coordinates(coordinates: List, geom_type: str, user_id: int) -> List[Dict]:
    """Find existing features in the database with matching coordinates."""
    try:
        # Normalize coordinates to handle floating point precision differences
        normalized_coords = normalize_coordinates(coordinates)
        if not normalized_coords:
            return []

        if geom_type not in GEOM_TYPE_MAPPING:
            return []

        # Create GEOSGeometry temporarily for spatial filter (not stored in data structures)
        geojson_geom = {
            "type": GEOM_TYPE_MAPPING[geom_type],
            "coordinates": normalized_coords
        }

        try:
            target_geometry = GEOSGeometry(json.dumps(geojson_geom))
        except Exception as e:
            # If geometry creation fails, we can't do spatial lookup
            _logger.debug(f"Failed to create geometry for duplicate check: {e}")
            return []

        # Use spatial index to find candidates within small tolerance
        # 1e-6 degrees is roughly 10cm
        candidates = FeatureStore.objects.filter(
            user_id=user_id,
            geometry__dwithin=(target_geometry, COORDINATE_TOLERANCE)
        ).values('id', 'geojson', 'timestamp')

        # Filter candidates using exact normalized coordinate comparison
        existing_features = []

        for feat in candidates:
            feat_geojson = feat['geojson'] if isinstance(feat['geojson'], dict) else json.loads(feat['geojson'])
            feat_geom_type = feat_geojson.get('geometry', {}).get('type', '').lower()

            if feat_geom_type == geom_type:
                feature_coords = feat_geojson.get('geometry', {}).get('coordinates', [])
                if feature_coords:
                    normalized_feature_coords = normalize_coordinates(feature_coords)
                    if normalized_coords == normalized_feature_coords:
                        existing_features.append(feat)

        # Convert to result format
        result = []
        for feature in existing_features:
            result.append(_normalize_feature_for_hashing(feature))

        return result

    except:
        _logger.error(f"Error finding existing features by coordinates: {traceback.format_exc()}")
        return []


def _find_geometry_collection_duplicates(geometries: List, user_id: int) -> List[Dict]:
    """
    Find existing features that match any geometry within a GeometryCollection.
    Returns the first match found for any geometry in the collection.
    """
    try:
        # Check each geometry in the collection for duplicates
        for geometry in geometries:
            geom_type = geometry.get('type', '').lower()
            coordinates = geometry.get('coordinates', [])

            if coordinates:
                # Recursively check this geometry for duplicates
                existing_features = _find_existing_features_by_coordinates(coordinates, geom_type, user_id)
                if existing_features:
                    # Return the first match found
                    return existing_features

        # No duplicates found in any geometry
        return []

    except:
        _logger.error(f"Error finding geometry collection duplicates: {traceback.format_exc()}")
        return []


def _find_hash_duplicates(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[Literal['feature_store', 'cross_queue']] = None
) -> List[Dict]:
    """
    Find features that have duplicate hashes in the existing featurestore and/or import queue.

    Args:
        features: List of features to check for hash duplicates
        user_id: User ID to check duplicates for
        exclude_queue_id: Optional ImportQueue item ID to exclude from cross-queue checks
        exclude_timestamp: Optional timestamp - only check queue items older than this timestamp
        source_filter: Optional filter to check only specific source: 'feature_store', 'cross_queue', or None (both)

    Returns:
        hash_duplicate_features
    """
    if not features:
        return []

    # Get existing feature hashes from FeatureStore (only if not filtering for cross_queue)
    existing_store_hashes = set()
    hash_to_store_id = {}
    if source_filter != 'cross_queue':
        existing_store_hashes = set(
            FeatureStore.objects.filter(user_id=user_id).values_list('geojson_hash', flat=True)
        )

        # Build hash to feature_store_id mapping for linking
        for feature in FeatureStore.objects.filter(user_id=user_id, geojson_hash__isnull=False).values('id', 'geojson_hash', 'geojson', 'timestamp'):
            hash_to_store_id[feature['geojson_hash']] = {
                'id': feature['id'],
                'geojson': feature['geojson'],
                'timestamp': feature['timestamp']
            }

    # Get cross-queue hashes if needed (only if not filtering for feature_store)
    queue_hash_to_item = {}
    queue_hash_to_feature = {}
    if source_filter != 'feature_store':
        queue_hash_to_item, queue_hash_to_feature = _build_queue_hash_map(
            user_id, exclude_queue_id, exclude_timestamp
        )

    # Check each feature for hash duplicates
    hash_duplicates = []
    # Track counts by source type
    feature_store_hash_count = 0
    cross_queue_hash_count = 0

    for feature in features:
        geojson_hash = feature.get('properties', {}).get('geojson_hash')
        if not geojson_hash:
            geojson_hash = generate_geojson_hash(feature)

        feature_name = feature.get('properties', {}).get('name', 'Unnamed')

        # Check FeatureStore first (higher priority)
        if geojson_hash in existing_store_hashes:
            existing_features_list = []
            if geojson_hash in hash_to_store_id:
                store_feature = hash_to_store_id[geojson_hash]
                existing_features_list = [_normalize_feature_for_hashing(store_feature)]

            hash_duplicates.append({
                'feature': feature,
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': existing_features_list
            })
            feature_store_hash_count += 1
        # Check cross-queue (only if not in FeatureStore)
        elif geojson_hash in queue_hash_to_item:
            queue_info = queue_hash_to_item[geojson_hash]
            queue_existing = {
                'id': queue_info['queue_item_id'],
                'name': queue_info['queue_item_filename'],
                'type': feature.get('geometry', {}).get('type', 'Unknown'),
                'timestamp': None,
                'geojson': queue_hash_to_feature[geojson_hash],
                'feature_index': queue_info.get('feature_index', 0)
            }

            hash_duplicates.append({
                'feature': feature,
                'source': DuplicateSource.CROSS_QUEUE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': [queue_existing]
            })
            cross_queue_hash_count += 1

    return hash_duplicates


def _find_geometry_duplicates(
        features: List[Dict],
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
        source_filter: Optional[Literal['feature_store', 'cross_queue']] = None
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Find features that have duplicate geometry in the existing featurestore and/or import queue.

    Args:
        features: List of features to check for duplicates
        user_id: User ID to check duplicates for
        exclude_queue_id: Optional ImportQueue item ID to exclude from cross-queue checks
        exclude_timestamp: Optional timestamp - only check queue items older than this timestamp
        source_filter: Optional filter to check only specific source: 'feature_store', 'cross_queue', or None (both)

    Returns:
        Tuple of (unique_features, duplicate_features_with_originals, log_messages)
    """
    import_log = ImportLog()

    if not features:
        return features, [], import_log

    # For large files, use batched approach to reduce database queries
    batch_threshold = get_required_setting('DUPLICATE_DETECTION_BATCH_THRESHOLD')
    if len(features) > batch_threshold:
        return _find_geometry_duplicates_batched(features, user_id, exclude_queue_id, exclude_timestamp, source_filter)

    # For smaller files, use the original approach
    unique_features = []
    duplicate_features = []

    # Build coordinate map from queue items if needed (only if not filtering for feature_store)
    queue_coords_map = {}
    if source_filter != 'feature_store':
        queue_coords_map = _build_queue_coords_map(user_id, exclude_queue_id, exclude_timestamp)

    # Track which features we've already found as duplicates
    duplicate_geojson_hashes = set()
    # Track counts by source type
    feature_store_coord_count = 0
    cross_queue_coord_count = 0

    for i, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates:
            # Skip features without coordinates
            unique_features.append(feature)
            continue

        # Check for existing features with matching coordinates in FeatureStore (only if not filtering for cross_queue)
        existing_features = []
        if source_filter != 'cross_queue':
            existing_features = _find_existing_features_by_coordinates(coordinates, geom_type, user_id)

        # Also check queue items if needed (only if not filtering for feature_store)
        if source_filter != 'feature_store' and exclude_queue_id is not None:
            normalized_feature_coords = normalize_coordinates(coordinates)
            coords_key = (geom_type, json.dumps(normalized_feature_coords, sort_keys=True))

            if coords_key in queue_coords_map:
                queue_match = queue_coords_map[coords_key]
                queue_existing = {
                    'id': queue_match['queue_item_id'],
                    'name': queue_match['queue_item_filename'],
                    'type': geom_type,
                    'timestamp': None,
                    'geojson': queue_match['feature'],
                    'feature_index': queue_match.get('feature_index', 0)
                }
                existing_features.append(queue_existing)

        if existing_features:
            # Check if it's from queue or store - prioritize feature store
            has_store_dup = any('timestamp' in ef and ef['timestamp'] is not None for ef in existing_features)
            has_queue_dup = any('timestamp' in ef and ef['timestamp'] is None for ef in existing_features)

            # If we have existing_features, at least one of these should be True
            assert has_store_dup or has_queue_dup, \
                f"existing_features found but neither has_store_dup nor has_queue_dup is True: {existing_features}"

            # Determine source based on priority rule (feature_store takes priority)
            if has_store_dup:
                source = DuplicateSource.FEATURE_STORE
                # Filter to only feature store existing features
                store_existing = [ef for ef in existing_features if 'timestamp' in ef and ef['timestamp'] is not None]
                duplicate_info = {
                    'feature': feature,
                    'source': source,
                    'match_type': DuplicateMatchType.GEOMETRY,
                    'existing_features': store_existing
                }
                feature_store_coord_count += 1
            else:
                source = DuplicateSource.CROSS_QUEUE
                # Filter to only cross-queue existing features
                queue_existing = [ef for ef in existing_features if 'timestamp' in ef and ef['timestamp'] is None]
                duplicate_info = {
                    'feature': feature,
                    'source': source,
                    'match_type': DuplicateMatchType.GEOMETRY,
                    'existing_features': queue_existing
                }
                cross_queue_coord_count += 1

            duplicate_features.append(duplicate_info)
            duplicate_geojson_hashes.add(generate_geojson_hash(feature))
        else:
            unique_features.append(feature)

    return unique_features, duplicate_features, import_log
