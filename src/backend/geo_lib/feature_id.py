"""
Utility functions for generating consistent feature IDs based on GeoJSON content.
"""
import hashlib
import json
from functools import lru_cache
from typing import Dict, Any


@lru_cache(maxsize=10000)
def _hash_geometry_and_properties(geometry_json: str, properties_json: str) -> str:
    """
    Cached hash generation for geometry and properties.
    This avoids re-serializing the same data multiple times.
    """
    # Combine geometry and properties into a single string for hashing
    combined_data = f"{geometry_json}|{properties_json}"
    return hashlib.sha256(combined_data.encode('utf-8')).hexdigest()


def generate_feature_hash(geojson_feature: Dict[str, Any]) -> str:
    """
    Generate a consistent hash-based ID for a GeoJSON feature.
    
    The hash is based on the geometry and properties of the feature, ensuring
    that identical features will have the same ID regardless of when they were
    imported or processed.
    
    Args:
        geojson_feature: A GeoJSON feature dictionary
        
    Returns:
        A SHA-256 hash string representing the feature's unique identity
    """
    # Create a normalized version of the feature for hashing
    # We exclude the 'id' field if it exists to ensure consistency
    normalized_feature = {
        'type': geojson_feature.get('type', 'Feature'),
        'geometry': geojson_feature.get('geometry'),
        'properties': geojson_feature.get('properties', {})
    }

    # Remove any existing 'feature_hash' from properties to avoid circular dependencies
    # Also remove 'system_tags' since they contain import metadata (source-file, import-year, etc.)
    # that shouldn't affect feature identity - features should be considered duplicates
    # if they have the same geometry and user properties, regardless of import metadata
    if 'feature_hash' in normalized_feature['properties'] or 'system_tags' in normalized_feature['properties']:
        normalized_feature['properties'] = normalized_feature['properties'].copy()
        if 'feature_hash' in normalized_feature['properties']:
            del normalized_feature['properties']['feature_hash']
        if 'system_tags' in normalized_feature['properties']:
            del normalized_feature['properties']['system_tags']

    # Convert geometry and properties to JSON strings separately for caching
    geometry_json = json.dumps(normalized_feature['geometry'], sort_keys=True, separators=(',', ':'))
    properties_json = json.dumps(normalized_feature['properties'], sort_keys=True, separators=(',', ':'))

    # Use cached hash generation
    return _hash_geometry_and_properties(geometry_json, properties_json)
