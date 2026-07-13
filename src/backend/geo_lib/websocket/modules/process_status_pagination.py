"""
Spatial sorting and pagination helpers for the import review UI
(`ProcessStatusModule`).

Features are always displayed sorted north-to-south, west-to-east (rather
than file order) so review is spatially coherent regardless of how the
source file ordered its placemarks/waypoints.
"""
from typing import Any, Dict, List, Tuple

from geo_lib.spatial.bbox import get_feature_bounding_box_center


def sort_features_spatially(geofeatures: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], Dict[int, int]]:
    """
    Sort features north-to-south, west-to-east by bounding-box center.

    Returns:
        Tuple of (sorted_features, original_to_new_index), where
        original_to_new_index maps each feature's index in `geofeatures` to
        its index in the returned sorted list.
    """
    features_with_indices = []
    for original_idx, feature in enumerate(geofeatures):
        center = get_feature_bounding_box_center(feature)
        assert center is not None
        sort_key = (-center[0], center[1])
        features_with_indices.append((feature, original_idx, sort_key))

    features_with_indices.sort(key=lambda x: x[2])

    sorted_features = [item[0] for item in features_with_indices]
    original_to_new_index = {item[1]: new_idx for new_idx, item in enumerate(features_with_indices)}

    return sorted_features, original_to_new_index


def build_other_queue_sorted_indices(other_queue_items) -> Dict[int, Dict[int, int]]:
    """
    Build a mapping of `queue_item.id -> {original_index: sorted_index}` for
    a set of other in-flight `ImportQueue` items, applying the same spatial
    sort used for the item currently being paginated. This lets cross-queue
    duplicate links point at the correct (sorted) position in the target item.
    """
    queue_item_sorted_indices: Dict[int, Dict[int, int]] = {}
    for queue_item in other_queue_items:
        if not queue_item.geofeatures:
            continue
        _, original_to_sorted = sort_features_spatially(queue_item.geofeatures)
        queue_item_sorted_indices[queue_item.id] = original_to_sorted

    return queue_item_sorted_indices


def normalize_page_bounds(page: int) -> Tuple[int, int, int, int]:
    """
    Normalize pagination parameters (page_size is always forced to 50) and
    compute the slice bounds for the requested page.

    Returns:
        Tuple of (page, page_size, start_idx, end_idx)
    """
    if page < 1:
        page = 1
    page_size = 50

    start_idx = (page - 1) * page_size
    end_idx = start_idx + page_size
    return page, page_size, start_idx, end_idx


def build_pagination_metadata(page: int, page_size: int, total_features: int, end_idx: int) -> Dict[str, Any]:
    """Build the `pagination` block returned alongside a page of features."""
    return {
        'page': page,
        'page_size': page_size,
        'total_features': total_features,
        'total_pages': (total_features + page_size - 1) // page_size,
        'has_next': end_idx < total_features,
        'has_previous': page > 1
    }
