import json
from datetime import datetime
from typing import Optional, Tuple, Dict

from api.models import ImportQueue
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.spatial.coordinates import normalize_coordinates


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
