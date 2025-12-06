"""
MapTiler maps tile source configuration.

This module dynamically registers MapTiler maps as tile sources based on configuration.
MapTiler maps are vector tile sources that can be used directly without a proxy.
"""

import functools
import requests
from . import register_tile_source
from website.config_loader import get_config_loader


@functools.lru_cache(maxsize=None)
def fetch_map_name(map_id, api_key):
    """
    Fetch the display name for a MapTiler map from its style.json.
    
    Cached for the lifetime of the server to avoid repeated API calls.
    
    Args:
        map_id: MapTiler map ID
        api_key: MapTiler API key
        
    Returns:
        Display name from the style.json, or a formatted fallback name
    """
    try:
        style_url = f'https://api.maptiler.com/maps/{map_id}/style.json?key={api_key}'
        response = requests.get(style_url, timeout=5)
        
        if response.status_code == 200:
            style_data = response.json()
            # Get the name from the style.json
            if 'name' in style_data:
                return f"MapTiler {style_data['name']}"
    except Exception as e:
        # Log error but continue with fallback name
        print(f"Warning: Could not fetch name for map '{map_id}': {e}")
    
    # Fallback: format the map ID as a display name
    return f"MapTiler {map_id.replace('-', ' ').title()}"


def register_maptiler_maps():
    """
    Register MapTiler maps as tile sources based on configuration.
    This function reads the map IDs from config and registers each as a tile source.
    Requires API key to be configured.
    """
    config = get_config_loader()
    
    # Get MapTiler API key
    api_key = config.get_with_env_override(
        'tilesources.maptiles.api_key',
        'MAPTILER_API_KEY',
        None
    )
    
    # If no API key, skip registration
    if not api_key:
        return
    
    # Get list of map IDs
    map_ids = config.get_list('tilesources.maptiles.maps', [])
    
    # If no maps configured, skip registration
    if not map_ids:
        return
    
    # Register each map as a tile source
    for map_id in map_ids:
        if not map_id or not isinstance(map_id, str):
            continue
        
        # Fetch display name from MapTiler's style.json
        display_name = fetch_map_name(map_id, api_key)
        
        # Create tile source configuration
        # MapTiler maps use vector tiles accessed via style.json
        # For MapLibre, we'll use the style URL directly
        source_config = {
            'id': f'maptiles_{map_id}',
            'name': display_name,
            'type': 'maptiler',
            'requires_proxy': False,
            'client_config': {
                'type': 'maptiler',
                'style_url': f'https://api.maptiler.com/maps/{map_id}/style.json?key={api_key}',
                'map_id': map_id
            }
        }
        
        # Register the tile source
        register_tile_source(f'maptiles_{map_id}', source_config)


# Register MapTiler maps when this module is imported
register_maptiler_maps()

