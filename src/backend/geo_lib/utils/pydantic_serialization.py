"""
Utility functions for serializing features through Pydantic models.
Ensures all features are validated and serialized through Pydantic before storage.
"""

import logging
from typing import List, Dict, Any, Optional

from geo_lib.types.feature import (
    PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
)

logger = logging.getLogger(__name__)


def convert_feature_to_pydantic(feature: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    Convert a feature dictionary to a Pydantic model and serialize it.
    Uses model_dump(mode='json') to automatically serialize datetime objects.
    
    Args:
        feature: Feature dictionary (should already be GeoJSON format)
        
    Returns:
        Serialized feature dictionary ready for JSONField storage, or None if invalid
    """
    geometry_type = feature.get('geometry', {}).get('type', '').lower()

    # Get appropriate feature class
    match geometry_type:
        case 'point' | 'multipoint':
            feature_class = PointFeature
        case 'linestring':
            feature_class = LineStringFeature
        case 'multilinestring':
            feature_class = MultiLineStringFeature
        case 'polygon' | 'multipolygon':
            feature_class = PolygonFeature
        case _:
            # Unsupported type (e.g., GeometryCollection) - skip
            logger.warning(f"Unsupported geometry type: {geometry_type}")
            return None

    # Convert to Pydantic model and serialize
    pydantic_feature = feature_class(**feature)
    return pydantic_feature.model_dump(mode='json', exclude_none=True, by_alias=True)


def convert_features_to_pydantic(features: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Convert a list of features through Pydantic models and serialize them.
    Handles both regular features and duplicate info dictionaries.
    
    Args:
        features: List of feature dictionaries or duplicate info dicts
        
    Returns:
        List of serialized feature dictionaries
    """
    result = []
    for item in features:
        # Handle duplicate info structure
        if isinstance(item, dict) and 'feature' in item and 'existing_features' in item:
            duplicate_info = item.copy()
            duplicate_info['feature'] = convert_feature_to_pydantic(duplicate_info['feature'])
            duplicate_info['existing_features'] = [
                convert_feature_to_pydantic(f) if isinstance(f, dict) and 'geometry' in f else f
                for f in duplicate_info.get('existing_features', [])
            ]
            result.append(duplicate_info)
        else:
            converted = convert_feature_to_pydantic(item)
            if converted:
                result.append(converted)

    return result
