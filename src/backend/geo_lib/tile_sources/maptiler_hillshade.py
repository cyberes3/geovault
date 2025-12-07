"""
MapTiler hillshade tile source configuration.

This module registers MapTiler hillshade as a tile source for 3D terrain visualization.
Hillshade can be overlaid on base layers to provide depth perception.
Can optionally use server proxy for caching to conserve API usage.
"""

from . import register_tile_source
from website.config_loader import get_config_loader


def register_maptiler_hillshade():
    """
    Register MapTiler hillshade as a tile source based on configuration.
    Supports both direct access and proxied access with caching.
    """
    config = get_config_loader()
    
    # Get MapTiler API key
    api_key = config.get_with_env_override(
        'maptiler.api_key',
        'MAPTILER_API_KEY',
        None
    )
    
    # If no API key, skip registration
    if not api_key:
        return
    
    # Check if proxy is enabled
    use_proxy = config.get_bool('maptiler.proxy_tiles', False)
    
    if use_proxy:
        # Use server proxy for hillshade tiles (WebP format)
        source_config = {
            'id': 'maptiler_hillshade',
            'name': 'MapTiler Hillshade',
            'type': 'hillshade',
            'requires_proxy': True,
            'hidden': True,  # Don't show in basemap selector
            'url_template': f'https://api.maptiler.com/tiles/hillshade/{{z}}/{{x}}/{{y}}.webp?key={api_key}',
            'proxy_config': {
                'headers': {
                    'User-Agent': 'Mozilla/5.0'
                }
            },
            'client_config': {
                'type': 'raster',
                'tiles': ['/api/tiles/maptiler_hillshade/{z}/{x}/{y}'],
                'tileSize': 256
            },
            'opacity': 0.3
        }
    else:
        # Direct access to MapTiler hillshade
        source_config = {
            'id': 'maptiler_hillshade',
            'name': 'MapTiler Hillshade',
            'type': 'hillshade',
            'requires_proxy': False,
            'hidden': True,  # Don't show in basemap selector
            'client_config': {
                'type': 'raster',
                'url': f'https://api.maptiler.com/tiles/hillshade/tiles.json?key={api_key}'
            },
            'opacity': 0.3
        }
    
    # Register the tile source
    register_tile_source('maptiler_hillshade', source_config)


# Register MapTiler hillshade when this module is imported
register_maptiler_hillshade()

