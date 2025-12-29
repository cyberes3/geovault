"""
Utility functions for file duplicate detection in WebSocket modules.
"""
from typing import List, Dict, Any

from geo_lib.feature_id import generate_geojson_hash


def check_all_features_duplicate(
    geofeatures: List[Dict[str, Any]],
    duplicate_features: List[Dict[str, Any]]
) -> bool:
    """
    Check if a file has exactly 1 feature and that feature is a duplicate.
    
    Args:
        geofeatures: List of GeoJSON features in the file
        duplicate_features: List of duplicate feature info dicts
        
    Returns:
        True if file has exactly 1 feature and that feature is a duplicate, False otherwise
    """
    if not geofeatures or len(geofeatures) != 1:
        return False
    
    if not duplicate_features or len(duplicate_features) == 0:
        return False
    
    # Get the single feature's hash
    single_feature = geofeatures[0]
    feature_hash = single_feature.get('properties', {}).get('geojson_hash')
    if not feature_hash:
        feature_hash = generate_geojson_hash(single_feature)
    
    # Check if this feature is in duplicate_features
    for dup_info in duplicate_features:
        dup_feature = dup_info.get('feature')
        if dup_feature:
            dup_feature_hash = dup_feature.get('properties', {}).get('geojson_hash')
            if not dup_feature_hash:
                dup_feature_hash = generate_geojson_hash(dup_feature)
            
            if dup_feature_hash == feature_hash:
                return True
    
    return False

