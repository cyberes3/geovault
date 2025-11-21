"""
Mapbuilder Topo tile source configuration.

This tile source requires a backend proxy to avoid CORS issues.
"""

from . import register_tile_source

# Mapbuilder Topo configuration
MB_TOPO_CONFIG = {
    'id': 'mb_topo',
    'name': 'Mapbuilder Topo',
    'type': 'xyz',
    'requires_proxy': True,
    'url_template': 'https://caltopo.com/tile/mb_topo/{z}/{x}/{y}.png?ctdarkmode=false',
    'proxy_config': {
        'headers': {
            'Origin': 'https://caltopo.com',
            'Referer': 'https://caltopo.com/map.html',
            'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36'
        }
    },
    'client_config': {
        'type': 'xyz',
        'url': '/api/tiles/mb_topo/{z}/{x}/{y}'
    }
}

# Register the tile source
register_tile_source('mb_topo', MB_TOPO_CONFIG)

