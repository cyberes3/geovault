"""
OpenTopoMap tile source configuration.

This tile source uses openmaps.fr, which provides OpenTopoMap tiles.
See: https://openmaps.fr/tile-usage-policy.html

This tile source does not require a proxy as it can be accessed directly.
"""

from geo_lib.tile_sources.base import TileSource


class OpenTopoMapTileSource(TileSource):
    """OpenTopoMap tile source from openmaps.fr."""
    
    @property
    def id(self):
        return 'opentopomap'
    
    @property
    def name(self):
        return 'OpenTopoMap'
    
    @property
    def type(self):
        return 'xyz'
    
    @property
    def url_template(self):
        return 'https://tile.openmaps.fr/opentopomap/{z}/{x}/{y}.png'
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': 'https://tile.openmaps.fr/opentopomap/{z}/{x}/{y}.png',
            'tileSize': 256,
            'attribution': '<a href="https://github.com/sletuffe/OpenTopoMap">&copy; OpenTopoMap-R</a> <a href="https://openmaps.fr/donate">❤️ Donation</a> <a href="http://www.openstreetmap.org/copyright">&copy; OpenStreetMap</a>'
        }
