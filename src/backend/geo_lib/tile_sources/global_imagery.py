from . import register_tile_source

# Global Imagery configuration
GLOBAL_IMAGERY_CONFIG = {
    'id': 'global_imagery',
    'name': 'Global Imagery',
    'type': 'xyz',
    'requires_proxy': True,
    'url_template': 'https://caltopo.com/tile/imagery/{z}/{x}/{y}.png',
    'proxy_config': {
        'headers': {
            'Origin': 'https://caltopo.com',
            'Referer': 'https://caltopo.com/map.html',
            'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36'
        }
    },
    'client_config': {
        'type': 'xyz',
        'url': '/api/tiles/global_imagery/{z}/{x}/{y}'
    }
}

# Register the tile source
register_tile_source('global_imagery', GLOBAL_IMAGERY_CONFIG)
