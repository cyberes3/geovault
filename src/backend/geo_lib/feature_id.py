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


def _strip_none_values(obj: Any) -> Any:
    """Recursively remove None values from dictionaries to match Pydantic serialization."""
    if isinstance(obj, dict):
        return {k: _strip_none_values(v) for k, v in obj.items() if v is not None}
    elif isinstance(obj, list):
        return [_strip_none_values(v) for v in obj]
    return obj


def generate_geojson_hash(geojson_feature: Dict[str, Any]) -> str:
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

    # Remove any existing 'geojson_hash' from properties to avoid circular dependencies
    # Also remove 'system_tags' since they contain import metadata (source-file, import-year, etc.)
    # that shouldn't affect feature identity - features should be considered duplicates
    # if they have the same geometry and user properties, regardless of import metadata
    if 'geojson_hash' in normalized_feature['properties'] or 'system_tags' in normalized_feature['properties']:
        normalized_feature['properties'] = normalized_feature['properties'].copy()
        if 'geojson_hash' in normalized_feature['properties']:
            del normalized_feature['properties']['geojson_hash']
        if 'system_tags' in normalized_feature['properties']:
            del normalized_feature['properties']['system_tags']

    # Strip None values to match Pydantic's exclude_none=True behavior
    # This ensures hashes calculated on raw data match hashes calculated on Pydantic-serialized data (from DB)
    normalized_feature['properties'] = _strip_none_values(normalized_feature['properties'])
    normalized_feature['geometry'] = _strip_none_values(normalized_feature['geometry'])

    # Convert geometry and properties to JSON strings separately for caching
    geometry_json = json.dumps(normalized_feature['geometry'], sort_keys=True, separators=(',', ':'))
    properties_json = json.dumps(normalized_feature['properties'], sort_keys=True, separators=(',', ':'))

    # Use cached hash generation
    return _hash_geometry_and_properties(geometry_json, properties_json)
