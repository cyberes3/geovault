"""
Shared utilities for import operations.
Contains helper functions used by both single and bulk import jobs.
"""

import json
import threading
from typing import Dict, Any, Optional, Tuple

from django.contrib.gis.geos import GEOSGeometry
from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.const_strings import prepare_user_tags
from geo_lib.feature_id import generate_feature_hash
from geo_lib.types.feature import PointFeature, PolygonFeature, LineStringFeature, MultiLineStringFeature
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


def strip_icon_properties(feature: dict) -> dict:
    """
    Remove icon-related properties from a feature.
    
    Args:
        feature: Feature dictionary with properties
        
    Returns:
        Feature dictionary with icon properties removed
    """
    if not isinstance(feature, dict) or 'properties' not in feature:
        return feature
    
    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol',
        'styleUrl',  # KML style URLs might reference icons
    ]
    
    # Remove icon properties
    for prop_name in icon_property_names:
        if prop_name in feature['properties']:
            del feature['properties'][prop_name]
    
    # Also check nested structures (e.g., style objects)
    def remove_icons_from_dict(d):
        if not isinstance(d, dict):
            return
        for key, value in list(d.items()):
            if key in icon_property_names:
                del d[key]
            elif isinstance(value, dict):
                remove_icons_from_dict(value)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        remove_icons_from_dict(item)
    
    remove_icons_from_dict(feature['properties'])
    
    return feature


def delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(log_id=log_id).delete()[0]
    return deleted_count


def broadcast_item_imported(user_id: int, item_id: int):
    """Broadcast WebSocket event when an item is imported."""
    channel_layer = get_channel_layer()
    if channel_layer:
        # Get item details for history broadcast
        try:
            item = ImportQueue.objects.get(id=item_id)
            item_data = {
                'id': item_id,
                'original_filename': item.original_filename,
                'timestamp': item.timestamp.isoformat()
            }
        except ImportQueue.DoesNotExist:
            item_data = {'id': item_id}

        # Broadcast to import queue module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_queue_item_imported',
                'data': {'id': item_id}
            }
        )

        # Broadcast to import history module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_history_item_added',
                'data': item_data
            }
        )


def process_single_feature_for_import(
    feature: Dict[str, Any], feature_index: int, import_item: ImportQueue,
    user_id: int, import_custom_icons: bool, existing_hashes: set,
    current_batch_hashes: set, duplicate_check_lock: threading.Lock
) -> Optional[FeatureStore]:
    """
    Process a single feature for import, including validation, tag generation, and FeatureStore creation.
    This is a worker function designed to be called in parallel.
    
    Args:
        feature: Feature dictionary from geofeatures
        feature_index: Index of the feature (for logging)
        import_item: The ImportQueue item being imported
        user_id: ID of the user importing the feature
        import_custom_icons: Whether to import custom icons
        existing_hashes: Set of feature hashes already in the database
        current_batch_hashes: Set of feature hashes in the current batch (for internal duplicate detection)
        duplicate_check_lock: Lock for thread-safe duplicate checking
        
    Returns:
        FeatureStore object if successful, None if skipped or failed
    """
    try:
        c = None
        if 'geometry' not in feature or not feature['geometry']:
            logger.warning(f"Skipping feature {feature_index} due to missing or empty geometry: {feature.get('properties', {}).get('name', 'Unnamed')}")
            return None

        geometry_type = feature['geometry']['type'].lower()
        match geometry_type:
            case 'point':
                c = PointFeature
            case 'multipoint':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'multilinestring':
                c = MultiLineStringFeature
            case 'polygon':
                c = PolygonFeature
            case 'multipolygon':
                c = PolygonFeature
            case _:
                feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                logger.warning(f"Skipping feature {feature_index} '{feature_name}' due to unsupported geometry type: {geometry_type}")
                return None

        assert c is not None

        # Strip icon properties if import_custom_icons is False
        if not import_custom_icons:
            feature = strip_icon_properties(feature.copy())

        feature_instance = c(**feature)
        # Tags are already generated during processing step, just use existing tags
        existing_tags = feature_instance.properties.tags or []
        # Prepare tags before storing (lowercase and deduplicate)
        existing_tags = prepare_user_tags(existing_tags)
        feature_instance.properties.tags = existing_tags

        # Create the GeoJSON data
        geojson_data = json.loads(feature_instance.model_dump_json())

        # Generate hash-based ID for the feature
        feature_hash = generate_feature_hash(geojson_data)

        # Check if this feature already exists for this user or in current batch (thread-safe)
        with duplicate_check_lock:
            if feature_hash in existing_hashes or feature_hash in current_batch_hashes:
                # Skip importing duplicate features
                # Skipping duplicate feature (normal operation)
                return None

            # Add to current batch hashes to prevent duplicates within the same import
            current_batch_hashes.add(feature_hash)

        # Update the feature's ID in the GeoJSON data
        geojson_data['properties']['id'] = feature_hash

        # Create geometry object for spatial queries
        geometry = None
        if 'geometry' in geojson_data and geojson_data['geometry']:
            try:
                # Ensure coordinates are properly formatted for GEOSGeometry
                geom_data = geojson_data['geometry'].copy()

                # Handle 3D coordinates by ensuring they're properly structured
                if geom_data['type'] == 'Point':
                    coords = geom_data['coordinates']
                    # Ensure Point has exactly 3 coordinates (x, y, z) or 2 (x, y)
                    if len(coords) == 2:
                        coords = [coords[0], coords[1], 0.0]  # Add Z=0 for 2D points
                    elif len(coords) == 3:
                        coords = [coords[0], coords[1], coords[2]]  # Keep 3D
                    geom_data['coordinates'] = coords

                elif geom_data['type'] == 'LineString':
                    coords = geom_data['coordinates']
                    # Ensure each coordinate in LineString has 3 dimensions
                    geom_data['coordinates'] = [
                        [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                        for coord in coords
                    ]

                elif geom_data['type'] == 'Polygon':
                    coords = geom_data['coordinates']
                    # Ensure each coordinate in Polygon has 3 dimensions
                    geom_data['coordinates'] = [
                        [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in ring
                        ]
                        for ring in coords
                    ]

                geometry = GEOSGeometry(json.dumps(geom_data))
            except Exception as e:
                # Log internal error details for debugging - don't expose to user
                logger.warning(f"Error creating geometry for feature {feature_index}: {type(e).__name__}: {str(e)}")
                import traceback
                logger.error(f"Geometry creation error traceback for feature {feature_index}: {traceback.format_exc()}")

        # Create FeatureStore object
        return FeatureStore(
            geojson=geojson_data,
            file_hash=feature_hash,
            geometry=geometry,
            source=import_item,
            user_id=user_id
        )
    except Exception as e:
        logger.error(f"Error processing feature {feature_index}: {str(e)}")
        import traceback
        logger.error(f"Feature processing error traceback: {traceback.format_exc()}")
        return None

