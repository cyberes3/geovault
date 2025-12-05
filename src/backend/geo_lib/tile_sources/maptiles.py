"""
MapTiler maps tile source configuration.

This module dynamically registers MapTiler maps as tile sources based on configuration.
MapTiler maps are vector tile sources that can be used directly without a proxy.
"""

from . import register_tile_source
from website.config_loader import get_config_loader


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
    
    # Map ID to display name mapping (common MapTiler maps)
    map_names = {
        'winter-v2': 'MapTiler Winter',
        'streets-v2': 'MapTiler Streets',
        'satellite': 'MapTiler Satellite',
        'outdoor-v2': 'MapTiler Outdoor',
        'basic-v2': 'MapTiler Basic',
        'bright-v2': 'MapTiler Bright',
        'dark-v2': 'MapTiler Dark',
        'topo-v2': 'MapTiler Topo',
        'hybrid': 'MapTiler Hybrid',
        'positron': 'MapTiler Positron',
        'toner': 'MapTiler Toner',
    }
    
    # Register each map as a tile source
    for map_id in map_ids:
        if not map_id or not isinstance(map_id, str):
            continue
        
        # Generate display name (use mapping if available, otherwise format the ID)
        display_name = map_names.get(map_id, f"MapTiler {map_id.replace('-', ' ').title()}")
        
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

