from geo_lib.tile_sources.base import TileSource
from geo_lib.utils.version import get_user_agent


class OSMTileSource(TileSource):
    """OpenStreetMap tile source."""
    
    @property
    def id(self):
        return 'osm'
    
    @property
    def name(self):
        return 'OpenStreetMap'
    
    @property
    def type(self):
        return 'xyz'
    
    @property
    def url_template(self):
        return 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            'tileSize': 256,
            'attribution': '© OpenStreetMap contributors'
        }
    
    @property
    def proxy_config(self):
        """Proxy configuration with user agent for OSM."""
        return {
            'headers': {
                'User-Agent': get_user_agent()
            }
        }