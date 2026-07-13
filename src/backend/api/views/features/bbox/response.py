"""Standardized bbox query response building."""
import time
from typing import Dict, List

from website.settings_utils import get_required_setting


def _build_bbox_response(features: List[Dict], total_count: int, zoom_level: int, fallback_used: bool, **extra_fields) -> Dict:
    """
    Build standardized bbox query response dictionary.

    Args:
        features: List of GeoJSON feature dictionaries
        total_count: Total number of features in bbox
        zoom_level: Zoom level used for query
        fallback_used: Whether fallback mechanism was used
        **extra_fields: Additional fields to include in response (e.g., 'tag' for public shares)

    Returns:
        Dictionary ready to be converted to a success response
    """
    # Get the configured limit for comparison
    max_features = get_required_setting('MAX_FEATURES_PER_REQUEST')

    # Create GeoJSON FeatureCollection
    geojson_data = {
        "type": "FeatureCollection",
        "features": features
    }

    response_data = {
        'data': geojson_data,
        'feature_count': len(features),
        'total_features_in_bbox': total_count,
        'max_features_limit': max_features,
        'zoom_level': zoom_level,
        'timestamp': time.time(),
        'fallback_used': fallback_used
    }

    # Add any extra fields
    response_data.update(extra_fields)

    return response_data
