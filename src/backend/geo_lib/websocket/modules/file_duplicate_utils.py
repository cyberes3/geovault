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
    Check if ALL features in a file are duplicates.
    
    Args:
        geofeatures: List of GeoJSON features in the file
        duplicate_features: List of duplicate feature info dicts
        
    Returns:
        True if the file has features and ALL of them are duplicates, False otherwise
    """
    if not geofeatures:
        return False
    
    if not duplicate_features:
        return False
    
    # Create a set of hashes for faster lookup
    dup_hashes = set()
    for dup_info in duplicate_features:
        dup_feature = dup_info.get('feature')
        if dup_feature:
            dup_hash = dup_feature.get('properties', {}).get('geojson_hash')
            if not dup_hash:
                dup_hash = generate_geojson_hash(dup_feature)
            if dup_hash:
                dup_hashes.add(dup_hash)
                
    if not dup_hashes:
        return False
        
    # Check each feature in the file
    for feature in geofeatures:
        feature_hash = feature.get('properties', {}).get('geojson_hash')
        if not feature_hash:
            feature_hash = generate_geojson_hash(feature)
            
        if feature_hash not in dup_hashes:
            return False
            
    return True

