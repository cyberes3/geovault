"""
GeoJSON normalization for consistent hashing across different file formats.

This module ensures that KML, KMZ, and GPX files that contain the same geographic
data produce identical hashes, regardless of minor formatting differences like:
- Color case differences (#FF0000 vs #ff0000)
- Coordinate precision differences
- Property ordering
"""

import hashlib
import json
from typing import Any, Dict

from geo_lib.logging.console import get_import_logger

logger = get_import_logger()


def norm_coords(c):
    return round(float(c), 12) if isinstance(c, (int, float)) else [norm_coords(x) for x in c] if isinstance(c, list) else c


def hash_normalized_geojson(geojson: Dict[str, Any]) -> str:
    """
    Create a hash of normalized GeoJSON data.
    
    This ensures that files with the same geographic content but minor
    formatting differences (color case, coordinate precision) produce
    the same hash.
    
    Normalizations applied:
    - Coordinates rounded to 12 decimal places (~1mm precision)
    - Color properties (#FF0000 → #ff0000)
    - Consistent JSON serialization with sorted keys
    
    Args:
        geojson: GeoJSON FeatureCollection
    
    Returns:
        SHA256 hash of the normalized GeoJSON
    """
    
    feature_count = len(geojson.get('features', []))
    logger.debug(f"Normalizing {feature_count} features for hashing")

    # Color properties that need lowercase normalization
    color_props = {'stroke', 'fill', 'marker-color'}

    # Normalize the entire GeoJSON structure inline
    normalized = {
        'type': geojson.get('type', 'FeatureCollection'),
        'features': [
            {
                'type': f.get('type', 'Feature'),
                'geometry': {
                    'type': f['geometry']['type'],
                    'coordinates': norm_coords(f['geometry']['coordinates'])
                } if f.get('geometry') and 'coordinates' in f['geometry'] else f.get('geometry'),
                'properties': {
                    k: v.lower() if k in color_props and isinstance(v, str) and v.startswith('#') else v
                    for k, v in f.get('properties', {}).items()
                }
            }
            for f in geojson.get('features', [])
        ]
    }

    # Generate hash from sorted JSON
    content_hash = hashlib.sha256(json.dumps(normalized, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    logger.debug(f"Generated content hash: {content_hash[:16]}... for {feature_count} features")
    
    return content_hash
