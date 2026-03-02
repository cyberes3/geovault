import base64

from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class GoogleSatelliteHybridTileSource(TileSource):
    """Google Satellite with roads and places overlay (hybrid), via CalTopo proxy."""

    _API_KEY_B64 = 'QUl6YVN5QW8wZzBuWmg1YU9FaE1XMlM4NzZLTWpKOE9xYU4tVndR'

    @property
    def id(self):
        return 'google-satellite-hybrid'

    @property
    def name(self):
        return 'Google Satellite Hybrid'

    @property
    def type(self):
        return 'xyz'

    @property
    def requires_proxy(self):
        return True

    @property
    def url_template(self):
        """Google Maps tile URL template - hybrid = satellite + roads/labels (lyrs=y)."""
        api_key = base64.b64decode(self._API_KEY_B64).decode('utf-8')
        # lyrs=y is hybrid: satellite imagery with roads and place labels overlay
        return f'https://mt0.google.com/vt/lyrs=y&x={{x}}&y={{y}}&z={{z}}&scale=2&key={api_key}'

    @property
    def proxy_config(self):
        return {
            'headers': CALTOPO_HEADERS
        }

    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/google-satellite-hybrid/{z}/{x}/{y}',
            'tileSize': 256,  # 512px image covers one 256-tile area; grid is 256
            'attribution': '© Google'
        }
