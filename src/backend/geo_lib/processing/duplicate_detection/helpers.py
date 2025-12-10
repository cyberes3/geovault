import json
from typing import Dict, Any


def _normalize_feature_for_hashing(existing: Dict[str, Any]) -> Dict[str, Any]:
    """
    Format an existing feature from the database for consistent use.
    Ensures timestamps are serialized and GeoJSON structure is standardized.
    """
    # Get GeoJSON data (parse if string)
    geojson_data = existing['geojson']
    if isinstance(geojson_data, str):
        geojson_data = json.loads(geojson_data)

    # Handle timestamp serialization
    timestamp = existing['timestamp']
    if hasattr(timestamp, 'isoformat'):
        timestamp_str = timestamp.isoformat()
    else:
        timestamp_str = str(timestamp)

    return {
        'id': existing['id'],
        'name': geojson_data.get('properties', {}).get('name', 'Unnamed'),
        'type': geojson_data.get('geometry', {}).get('type', 'Unknown'),
        'timestamp': timestamp_str,
        'geojson': geojson_data
    }
