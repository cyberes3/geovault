from dataclasses import dataclass
from datetime import datetime
from typing import Dict, List, Optional, Tuple

from api.models import ImportQueue
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.spatial.coordinates import geometries_match, normalize_coordinates


@dataclass(frozen=True)
class QueueGeometryEntry:
    geom_type: str
    coordinates: List
    queue_item_id: int
    queue_item_filename: str
    feature: Dict
    feature_index: int

    def to_existing_feature_ref(self, geom_type: str) -> Dict:
        return {
            'id': self.queue_item_id,
            'name': self.queue_item_filename,
            'type': geom_type,
            'timestamp': None,
            'geojson': self.feature,
            'feature_index': self.feature_index,
        }


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


def _build_queue_geometry_entries(
        user_id: int,
        exclude_queue_id: Optional[int] = None,
        exclude_timestamp: Optional[datetime] = None,
) -> List[QueueGeometryEntry]:
    """Build geometry entries from older import-queue items for cross-queue matching."""
    entries: List[QueueGeometryEntry] = []

    if exclude_queue_id is None:
        return entries

    other_queue_items = ImportQueue.objects.filter(
        user_id=user_id,
        imported=False,
    ).exclude(id=exclude_queue_id)

    if exclude_timestamp:
        other_queue_items = other_queue_items.filter(timestamp__lt=exclude_timestamp)

    for queue_item in other_queue_items:
        if not queue_item.geofeatures:
            continue
        for feature_idx, queue_feature in enumerate(queue_item.geofeatures):
            queue_geom = queue_feature.get('geometry', {})
            queue_coords = queue_geom.get('coordinates')
            queue_type = queue_geom.get('type', '').lower()

            if queue_coords:
                entries.append(QueueGeometryEntry(
                    geom_type=queue_type,
                    coordinates=queue_coords,
                    queue_item_id=queue_item.id,
                    queue_item_filename=queue_item.original_filename,
                    feature=queue_feature,
                    feature_index=feature_idx,
                ))

    return entries


def _find_queue_geometry_match(
        geom_type: str,
        coordinates: List,
        entries: List[QueueGeometryEntry],
) -> Optional[QueueGeometryEntry]:
    """Find the first older queue feature with matching geometry (same rule as feature library)."""
    if not coordinates:
        return None

    normalized_input = normalize_coordinates(coordinates)

    for entry in entries:
        if entry.geom_type != geom_type:
            continue
        if geometries_match(normalized_input, normalize_coordinates(entry.coordinates)):
            return entry

    return None
