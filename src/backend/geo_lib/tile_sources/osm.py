"""
OpenStreetMap tile source configuration.

This tile source does not require a proxy as it can be accessed directly.
"""

from . import register_tile_source

# OpenStreetMap configuration
OSM_CONFIG = {
    'id': 'osm',
    'name': 'OpenStreetMap',
    'type': 'osm',
    'requires_proxy': False,
    'url_template': 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    'client_config': {
        'type': 'osm'
    }
}

# Register the tile source
register_tile_source('osm', OSM_CONFIG)

