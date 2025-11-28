"""
Duplicate detection logic for geospatial features.
Handles finding duplicate features within a file and against the existing feature store.
"""

import time
import json
import traceback
from typing import List, Dict, Tuple, Any

from django.contrib.gis.geos import GEOSGeometry

from api.models import FeatureStore
from website.settings_utils import get_required_setting
from geo_lib.feature_id import generate_feature_hash
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
    """Remove 100% duplicate features and log the process."""
    import_log = ImportLog()

    if not features:
        return features, 0, import_log

    import_log.add(f"Checking {len(features)} features for internal duplicates", "Duplicate Detection", DatabaseLogLevel.INFO)

    # Track features by hash
    seen_hashes = set()
    unique_features = []
    duplicate_feature_count = 0

    for i, feature in enumerate(features):
        # Generate hash for this feature
        feature_hash = generate_feature_hash(feature)

        if feature_hash in seen_hashes:
            # This is a duplicate
            duplicate_feature_count += 1
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            import_log.add(f"Duplicate within file: '{feature_name}' ({feature_type})", 'Duplicate Detection', DatabaseLogLevel.INFO)
        else:
            # This is a unique feature
            seen_hashes.add(feature_hash)
            unique_features.append(feature)

    if duplicate_feature_count > 0:
        import_log.add(f"Removed {duplicate_feature_count} duplicate features within the file", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found within the file", "Duplicate Detection", DatabaseLogLevel.INFO)

    return unique_features, duplicate_feature_count, import_log


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


def find_coordinate_duplicates(features: List[Dict], user_id: int) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Find features that have duplicate coordinates in the existing featurestore.
    Returns (unique_features, duplicate_features_with_originals, log_messages)
    """
    import_log = ImportLog()

    if not features:
        return features, [], import_log

    import_log.add(f"Checking {len(features)} features against existing features in your library", "Duplicate Detection", DatabaseLogLevel.INFO)

    # For large files, use batched approach to reduce database queries
    batch_threshold = get_required_setting('DUPLICATE_DETECTION_BATCH_THRESHOLD')
    if len(features) > batch_threshold:
        import_log.add("Using optimized batch processing for large file", "Duplicate Detection", DatabaseLogLevel.INFO)
        return _find_coordinate_duplicates_batched(features, user_id, import_log)

    # For smaller files, use the original approach
    unique_features = []
    duplicate_features = []
    
    for i, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates:
            # Skip features without coordinates
            unique_features.append(feature)
            continue

        # Check for existing features with matching coordinates
        existing_features = _find_existing_features_by_coordinates(coordinates, geom_type, user_id)

        if existing_features:
            # This is a duplicate - add original feature info
            duplicate_info = {
                'feature': feature,
                'existing_features': existing_features
            }
            duplicate_features.append(duplicate_info)

            # Create log message for the duplicate
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            existing_count = len(existing_features)
            import_log.add(f"Coordinate duplicate found: '{feature_name}' ({feature_type}) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
        else:
            unique_features.append(feature)

    # Log summary
    if duplicate_features:
        import_log.add(f"Found {len(duplicate_features)} features that already exist in your library", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found in your existing library", "Duplicate Detection", DatabaseLogLevel.INFO)

    return unique_features, duplicate_features, import_log


def _find_coordinate_duplicates_batched(features: List[Dict], user_id: int, import_log: ImportLog) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Optimized duplicate detection for large files using batched database queries.
    """
    unique_features = []
    duplicate_features = []

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

        import_log.add(f"Processing {len(type_features)} {geom_type} features for duplicates", 'Find Coordinate Duplicates')

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
                                'existing_features': existing_features
                            }
                            duplicate_features.append(duplicate_info)
                            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                            existing_count = len(existing_features)
                            import_log.add(f"Coordinate duplicate found: '{feature_name}' (GeometryCollection) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
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

            # Single database query for the entire batch using spatial index
            try:
                # Extract GEOS objects for spatial query (temporary, not stored in feature dicts)
                geometries = [geom for _, _, _, geom in batch_geometries]
                existing_features = FeatureStore.objects.filter(
                    user_id=user_id,
                    geometry__in=geometries
                ).values('id', 'geojson', 'timestamp')

                # Create a lookup map for existing features using normalized coordinates
                # This handles floating point precision differences better than WKT comparison
                existing_lookup = {}
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
                        # This is a duplicate
                        duplicate_info = {
                            'feature': feature,
                            'existing_features': existing_lookup[coords_key]
                        }
                        duplicate_features.append(duplicate_info)

                        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                        existing_count = len(existing_lookup[coords_key])
                        import_log.add(f"Coordinate duplicate found: '{feature_name}' ({geom_type}) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
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

    # Log summary
    if duplicate_features:
        import_log.add(f"Found {len(duplicate_features)} features that already exist in your library", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found in your existing library", "Duplicate Detection", DatabaseLogLevel.INFO)

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

