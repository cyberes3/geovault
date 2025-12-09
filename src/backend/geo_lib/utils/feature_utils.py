"""
Utility functions for working with GeoJSON features.
"""

from typing import Dict, List


def build_feature_type_summary(features: List[Dict]) -> str:
    """
    Build a summary string of feature types and their counts.
    
    Args:
        features: List of GeoJSON features
        
    Returns:
        Summary string like "5 Point, 2 LineString, 1 Polygon"
    """
    feature_types = {}
    for feature in features:
        geom_type = feature.get('geometry', {}).get('type', 'Unknown')
        feature_types[geom_type] = feature_types.get(geom_type, 0) + 1

    return ', '.join([f"{count} {ftype}" for ftype, count in feature_types.items()])
