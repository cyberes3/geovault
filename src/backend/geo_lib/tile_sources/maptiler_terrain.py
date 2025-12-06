"""
MapTiler terrain tile source configuration.

This module registers MapTiler terrain-rgb-v2 as a tile source for 3D terrain.
Can optionally use server proxy for caching to conserve API usage.
"""

from . import register_tile_source
from website.config_loader import get_config_loader


def register_maptiler_terrain():
    """
    Register MapTiler terrain as a tile source based on configuration.
    Supports both direct access and proxied access with caching.
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
    
    # Check if proxy is enabled
    use_proxy = config.get_bool('tilesources.maptiles.proxy', False)
    
    if use_proxy:
        # Use server proxy for terrain tiles (raster-dem PNG format)
        source_config = {
            'id': 'maptiler_terrain',
            'name': 'MapTiler Terrain',
            'type': 'terrain',
            'requires_proxy': True,
            'hidden': True,  # Don't show in basemap selector
            'url_template': f'https://api.maptiler.com/tiles/terrain-rgb-v2/{{z}}/{{x}}/{{y}}.png?key={api_key}',
            'proxy_config': {
                'headers': {
                    'User-Agent': 'Mozilla/5.0'
                }
            },
            'client_config': {
                'type': 'raster-dem',
                'tiles': ['/api/tiles/maptiler_terrain/{z}/{x}/{y}'],
                'tileSize': 512,
                'maxzoom': 14
            },
            'exaggeration': 1.5
        }
    else:
        # Direct access to MapTiler terrain
        source_config = {
            'id': 'maptiler_terrain',
            'name': 'MapTiler Terrain',
            'type': 'terrain',
            'requires_proxy': False,
            'hidden': True,  # Don't show in basemap selector
            'client_config': {
                'type': 'raster-dem',
                'url': f'https://api.maptiler.com/tiles/terrain-rgb-v2/tiles.json?key={api_key}'
            },
            'exaggeration': 1
        }
    
    # Register the tile source
    register_tile_source('maptiler_terrain', source_config)


# Register MapTiler terrain when this module is imported
register_maptiler_terrain()

