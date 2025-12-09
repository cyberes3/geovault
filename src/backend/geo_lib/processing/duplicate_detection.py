"""
Duplicate detection logic for geospatial features.
Handles finding duplicate features within a file and against the existing feature store.
"""

import time
import json
import traceback
from typing import List, Dict, Tuple, Any, Optional, Literal
from datetime import datetime

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore, ImportQueue
from website.settings_utils import get_required_setting
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_models import DuplicateMatchType, DuplicateSource
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


COORDINATE_TOLERANCE = 1e-6

GEOM_TYPE_MAPPING = {
    'point': 'Point',
    'multipoint': 'MultiPoint',
    'linestring': 'LineString',
    'multilinestring': 'MultiLineString',
    'polygon': 'Polygon',
    'multipolygon': 'MultiPolygon',
}


def _format_existing_feature(existing: Dict[str, Any]) -> Dict[str, Any]:
    """
    Format an existing feature from the database for consistent use.
    Ensures timestamps are serialized and GeoJSON structure is standardized.
    """
    # Get GeoJSON data (parse if string)
    geojson_data = existing['geojson']
    if isinstance(geojson_data, str):
        geojson_data = json.loads(geojson_data)

    # Handle timestamp serialization
    timestamp = existing['timestamp']
    if hasattr(timestamp, 'isoformat'):
        timestamp_str = timestamp.isoformat()
    else:
        timestamp_str = str(timestamp)

    return {
        'id': existing['id'],
        'name': geojson_data.get('properties', {}).get('name', 'Unnamed'),
        'type': geojson_data.get('geometry', {}).get('type', 'Unknown'),
        'timestamp': timestamp_str,
        'geojson': geojson_data
    }


def strip_duplicate_features(features) -> Tuple[List[Any], int, ImportLog]:
    """Remove 100% duplicate features and return data only (no logging)."""
    import_log = ImportLog()

    if not features:
        return features, 0, import_log

    # Track features by hash
    seen_hashes = set()
    unique_features = []
    duplicate_feature_count = 0

    for i, feature in enumerate(features):
        # Generate hash for this feature
        geojson_hash = generate_geojson_hash(feature)

        if geojson_hash in seen_hashes:
            # This is a duplicate
            duplicate_feature_count += 1
        else:
            # This is a unique feature
            seen_hashes.add(geojson_hash)
            unique_features.append(feature)

    return unique_features, duplicate_feature_count, import_log


def _build_queue_hash_map(
    user_id: int,
    exclude_queue_id: Optional[int] = None,
    exclude_timestamp: Optional[datetime] = None
) -> Tuple[Dict[str, Dict], Dict[str, Dict]]:
    """
    Build hash lookup maps for cross-queue duplicate detection.
    
    Returns:
        Tuple of (queue_hash_to_item, queue_hash_to_feature) dictionaries
    """
    queue_hash_to_item = {}
    queue_hash_to_feature = {}
    
    if exclude_queue_id is None:
        return queue_hash_to_item, queue_hash_to_feature
    
    other_queue_items = ImportQueue.objects.filter(
        user_id=user_id,
        imported=False
    ).exclude(id=exclude_queue_id)
    
    if exclude_timestamp:
        other_queue_items = other_queue_items.filter(timestamp__lt=exclude_timestamp)
    
    for queue_item in other_queue_items:
        if not queue_item.geofeatures:
            continue
        for feature_idx, feature in enumerate(queue_item.geofeatures):
            geojson_hash = feature.get('properties', {}).get('geojson_hash')
            if not geojson_hash:
                geojson_hash = generate_geojson_hash(feature)
            if geojson_hash not in queue_hash_to_item:
                queue_hash_to_item[geojson_hash] = {
                    'queue_item_id': queue_item.id,
                    'queue_item_filename': queue_item.original_filename,
                    'feature_index': feature_idx
                }
                queue_hash_to_feature[geojson_hash] = feature
    
    return queue_hash_to_item, queue_hash_to_feature


def _build_queue_coords_map(
    user_id: int,
    exclude_queue_id: Optional[int] = None,
    exclude_timestamp: Optional[datetime] = None
) -> Dict[Tuple[str, str], Dict]:
    """
    Build coordinate lookup map for cross-queue duplicate detection.
    
    Returns:
        Dictionary mapping (geom_type, normalized_coords_json) to queue item info
    """
    queue_coords_map = {}
    
    if exclude_queue_id is None:
        return queue_coords_map
    
    other_queue_items = ImportQueue.objects.filter(
        user_id=user_id,
        imported=False
    ).exclude(id=exclude_queue_id)
    
    if exclude_timestamp:
        other_queue_items = other_queue_items.filter(timestamp__lt=exclude_timestamp)
    
    for queue_item in other_queue_items:
        # Skip items that are still processing (empty geofeatures)
        if not queue_item.geofeatures or len(queue_item.geofeatures) == 0:
            continue
        for feature_idx, queue_feature in enumerate(queue_item.geofeatures):
            queue_geom = queue_feature.get('geometry', {})
            queue_coords = queue_geom.get('coordinates')
            queue_type = queue_geom.get('type', '').lower()
            
            if queue_coords:
                normalized_queue_coords = normalize_coordinates(queue_coords)
                coords_key = (queue_type, json.dumps(normalized_queue_coords, sort_keys=True))
                if coords_key not in queue_coords_map:
                    queue_coords_map[coords_key] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename,
                        'feature': queue_feature,
                        'feature_index': feature_idx
                    }
    
    return queue_coords_map




def normalize_coordinates(coords: List, tolerance: float = COORDINATE_TOLERANCE) -> List:
    """Normalize coordinates by rounding to specified tolerance."""
    if not coords:
        return []
    
    if isinstance(coords[0], (int, float)):
        # Single coordinate pair
        return [round(coord, 6) for coord in coords]
    else:
        # Nested coordinates (LineString or Polygon)
        return [normalize_coordinates(coord, tolerance) for coord in coords]


def coordinates_match(coord1: List, coord2: List, tolerance: float = COORDINATE_TOLERANCE) -> bool:
    """Check if two coordinate sets match within tolerance."""
    norm1 = normalize_coordinates(coord1, tolerance)
    norm2 = normalize_coordinates(coord2, tolerance)
    return norm1 == norm2


def find_geometry_duplicates(
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
        return _find_geometry_duplicates_batched(features, user_id, import_log, exclude_queue_id, exclude_timestamp, source_filter)

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
            
            # Create duplicate entry for the duplicate
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            existing_count = len(existing_features)
            
            # Check if it's from queue or store - prioritize feature store
            has_store_dup = any('timestamp' in ef and ef['timestamp'] is not None for ef in existing_features)
            has_queue_dup = any('timestamp' in ef and ef['timestamp'] is None for ef in existing_features)
            
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


def _find_geometry_duplicates_batched(
    features: List[Dict], 
    user_id: int, 
    import_log: ImportLog,
    exclude_queue_id: Optional[int] = None,
    exclude_timestamp: Optional[datetime] = None,
    source_filter: Optional[str] = None
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Optimized duplicate detection for large files using batched database queries.
    """
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
                except Exception as e:
                    import_log.add(f"Failed to create geometry for feature {idx}: {str(e)}", 'Find Coordinate Duplicates')
                    logger.error(f"Failed to create geometry for feature {idx}: {traceback.format_exc()}")
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
                        formatted_existing = _format_existing_feature(existing)
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

            except Exception as e:
                import_log.add(f"Batch query encountered an issue, processing individually", 'Duplicate Detection', DatabaseLogLevel.WARNING)
                # Log internal error details for debugging
                logger.warning(f"Batch query failed for {geom_type} features: {str(e)}")
                logger.error(f"Batch query error traceback: {traceback.format_exc()}")
                # Fall back to individual processing for this batch
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


def get_skipped_feature_ids_from_duplicates(
    duplicate_features: List[Dict],
    existing_skipped_ids: Optional[List[str]] = None
) -> set:
    """
    Extract feature IDs from duplicate features to add to skipped_feature_ids.
    
    Args:
        duplicate_features: List of duplicate feature info dicts
        existing_skipped_ids: Optional existing list of skipped feature IDs
    
    Returns:
        Set of feature IDs to skip (including existing ones)
    """
    skipped_feature_ids = set(existing_skipped_ids if existing_skipped_ids else [])
    
    for dup_info in duplicate_features:
        dup_feature = dup_info.get('feature')
        if dup_feature:
            geojson_hash = generate_geojson_hash(dup_feature)
            feature_id = dup_feature.get('properties', {}).get('geojson_hash', geojson_hash)
            skipped_feature_ids.add(feature_id)
    
    return skipped_feature_ids


def find_hash_duplicates(
    features: List[Dict],
    user_id: int,
    exclude_queue_id: Optional[int] = None,
    exclude_timestamp: Optional[datetime] = None,
    source_filter: Optional[Literal['feature_store', 'cross_queue']] = None
) -> Tuple[List[Dict], ImportLog]:
    """
    Find features that have duplicate hashes in the existing featurestore and/or import queue.
    
    Args:
        features: List of features to check for hash duplicates
        user_id: User ID to check duplicates for
        exclude_queue_id: Optional ImportQueue item ID to exclude from cross-queue checks
        exclude_timestamp: Optional timestamp - only check queue items older than this timestamp
        source_filter: Optional filter to check only specific source: 'feature_store', 'cross_queue', or None (both)
    
    Returns:
        Tuple of (hash_duplicate_features, log_messages)
    """
    import_log = ImportLog()
    
    if not features:
        return [], import_log

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
                existing_features_list = [_format_existing_feature(store_feature)]
            
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
    
    return hash_duplicates, import_log


def find_duplicates_for_source(
    features: List[Dict],
    user_id: int,
    source: Literal['feature_store', 'cross_queue'],
    exclude_queue_id: Optional[int] = None,
    exclude_timestamp: Optional[datetime] = None
) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Find all duplicates (hash + geometry) for a specific source with proper priority.
    
    This consolidates hash and geometry checking for a single source, applying the
    priority rule that hash duplicates take precedence over geometry duplicates.
    
    Args:
        features: List of features to check for duplicates
        user_id: User ID to check duplicates for
        source: Source to check - 'feature_store' or 'cross_queue'
        exclude_queue_id: Optional ImportQueue item ID to exclude from cross-queue checks
        exclude_timestamp: Optional timestamp - only check queue items older than this timestamp
    
    Returns:
        Tuple of (remaining_features, all_duplicates, import_log)
        - remaining_features: features that are NOT duplicates in this source
        - all_duplicates: list of duplicate info dicts (hash + geometry), hash duplicates first
        - import_log: log messages from detection
        
    Raises:
        ValueError: If source is not 'feature_store' or 'cross_queue'
    """
    # Validate source parameter
    if source not in ['feature_store', 'cross_queue']:
        raise ValueError(f"Invalid source: '{source}'. Must be 'feature_store' or 'cross_queue'")
    
    import_log = ImportLog()
    
    # STEP 1: Find hash duplicates for this source
    hash_duplicates, hash_log = find_hash_duplicates(
        features,
        user_id,
        exclude_queue_id=exclude_queue_id,
        exclude_timestamp=exclude_timestamp,
        source_filter=source
    )
    # Don't extend logs - caller will handle logging
    
    # Build set of features that are hash duplicates
    hash_dup_hashes = {
        generate_geojson_hash(dup['feature']) 
        for dup in hash_duplicates if dup.get('feature')
    }
    
    # STEP 2: Find geometry duplicates for this source (on remaining features)
    remaining_after_hash = [
        f for f in features 
        if generate_geojson_hash(f) not in hash_dup_hashes
    ]
    
    _, geom_duplicates, geom_log = find_geometry_duplicates(
        remaining_after_hash,
        user_id,
        exclude_queue_id=exclude_queue_id,
        exclude_timestamp=exclude_timestamp,
        source_filter=source
    )
    # Don't extend logs - caller will handle logging
    
    # Build set of features that are geometry duplicates
    geom_dup_hashes = {
        generate_geojson_hash(dup['feature']) 
        for dup in geom_duplicates if dup.get('feature')
    }
    
    # Combine: hash duplicates first (higher priority), then geometry
    all_duplicates = hash_duplicates + geom_duplicates
    
    # Remaining features: not hash or geometry duplicates
    all_dup_hashes = hash_dup_hashes | geom_dup_hashes
    remaining_features = [
        f for f in features 
        if generate_geojson_hash(f) not in all_dup_hashes
    ]
    
    return remaining_features, all_duplicates, import_log


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
            logger.debug(f"Failed to create geometry for duplicate check: {e}")
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
            try:
                feat_geojson = feat['geojson'] if isinstance(feat['geojson'], dict) else json.loads(feat['geojson'])
                feat_geom_type = feat_geojson.get('geometry', {}).get('type', '').lower()
                
                if feat_geom_type == geom_type:
                     feature_coords = feat_geojson.get('geometry', {}).get('coordinates', [])
                     if feature_coords:
                         normalized_feature_coords = normalize_coordinates(feature_coords)
                         if normalized_coords == normalized_feature_coords:
                             existing_features.append(feat)
            except Exception:
                continue

        # Convert to result format
        result = []
        for feature in existing_features:
            result.append(_format_existing_feature(feature))

        return result

    except Exception as e:
        # Log internal error details but don't expose to user
        logger.error(f"Error finding existing features by coordinates: {type(e).__name__}: {str(e)}")
        logger.error(f"Coordinate lookup error traceback: {traceback.format_exc()}")
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

    except Exception as e:
        logger.error(f"Error finding geometry collection duplicates: {type(e).__name__}: {str(e)}")
        logger.error(f"Geometry collection error traceback: {traceback.format_exc()}")
        return []
