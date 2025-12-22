import base64

from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class GoogleTerrainTileSource(TileSource):
    """Google Terrain tile source with proxying support."""

    # Base64 encoded API key
    # Decode: 
    _API_KEY_B64 = 'QUl6YVN5QW8wZzBuWmg1YU9FaE1XMlM4NzZLTWpKOE9xYU4tVndR'

    @property
    def id(self):
        return 'google_terrain'

    @property
    def name(self):
        return 'Google Terrain'

    @property
    def type(self):
        return 'xyz'

    @property
    def requires_proxy(self):
        return True

    @property
    def url_template(self):
        """Google Maps tile URL template using terrain format."""
        api_key = base64.b64decode(self._API_KEY_B64).decode('utf-8')
        # lyrs=p is for terrain with roads
        # lyrs=t is for terrain only (no labels)
        # scale=2 requests 512x512 tiles for sharp rendering
        return f'https://mt0.google.com/vt/lyrs=p&x={{x}}&y={{y}}&z={{z}}&scale=2&key={api_key}'

    @property
    def proxy_config(self):
        """Proxy configuration with headers from the curl command."""
        return {
            'headers': CALTOPO_HEADERS
        }

    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/google_terrain/{z}/{x}/{y}',
            'tileSize': 256
        }

