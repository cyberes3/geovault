"""Shared utilities for sharing"""
import re

from typing import Tuple

from api.views.features.bbox_query import BboxQueryResult, _get_features_in_bbox


def _get_public_share_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, tag: str, zoom_level: int, allow_downloads: bool = False) -> BboxQueryResult:
    """
    Get features within bounding box that have a specific tag.
    Handles world-wide extents that cross the International Date Line.
    Returns both the features and the total count in a single optimized operation.
    Features are returned with public-safe properties (excludes _id and tags unless allow_downloads is True).
    
    This is a wrapper around the consolidated _get_features_in_bbox() function.
    """
    return _get_features_in_bbox(bbox, user_id, zoom_level, tag=tag, public_safe=True, allow_downloads=allow_downloads)


def _validate_share_id(share_id: str) -> bool:
    """
    Validate share_id format.
    Must be a valid UUID4 format (36 characters with hyphens).
    """
    if not share_id or not isinstance(share_id, str):
        return False
    # UUID4 format: 8-4-4-4-12 hexadecimal characters
    uuid_pattern = r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    return bool(re.match(uuid_pattern, share_id.lower()))
