import base64

from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class GoogleMapsTileSource(TileSource):
    """Google Maps tile source with proxying support."""

    # Base64 encoded API key from the curl command
    # Decode: 
    _API_KEY_B64 = 'QUl6YVN5QW8wZzBuWmg1YU9FaE1XMlM4NzZLTWpKOE9xYU4tVndR'

    @property
    def id(self):
        return 'google_maps'

    @property
    def name(self):
        return 'Google Maps'

    @property
    def type(self):
        return 'xyz'

    @property
    def requires_proxy(self):
        return True

    @property
    def url_template(self):
        """Google Maps tile URL template using standard raster format."""
        api_key = base64.b64decode(self._API_KEY_B64).decode('utf-8')
        # Use standard Google Maps raster tile format with high DPI support
        # scale=2 requests 512x512 tiles for sharp rendering
        # lyrs=m is for roadmap, other options: s (satellite), t (terrain), y (hybrid)
        return f'https://mt0.google.com/vt/lyrs=m&x={{x}}&y={{y}}&z={{z}}&scale=2&key={api_key}'

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
            'url': '/api/tiles/google_maps/{z}/{x}/{y}',
            'tileSize': 256
        }
