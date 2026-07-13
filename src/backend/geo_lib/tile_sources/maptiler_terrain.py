from django.conf import settings

from geo_lib.tile_sources.base import TileSource


class MapTilerTerrainTileSource(TileSource):
    """MapTiler terrain tile source."""

    def __init__(self):
        self._api_key = settings.MAPTILER_API_KEY
        self._use_proxy = settings.MAPTILER_PROXY_TILES
        self._site_domain = settings.SITE_DOMAIN
    
    @property
    def enabled(self):
        return self._api_key is not None
    
    @property
    def id(self):
        return 'maptiler-terrain'
    
    @property
    def name(self):
        return 'MapTiler Terrain'
    
    @property
    def type(self):
        return 'terrain'
    
    @property
    def hidden(self):
        return True
    
    @property
    def requires_proxy(self):
        return self._use_proxy
    
    @property
    def url_template(self):
        if self._use_proxy:
            return f'https://api.maptiler.com/tiles/terrain-rgb-v2/{{z}}/{{x}}/{{y}}.png?key={self._api_key}'
        return None
    
    @property
    def proxy_config(self):
        if self._use_proxy:
            return {
                'headers': {
                    'Origin': self._site_domain
                }
            }
        return None
    
    @property
    def client_config(self):
        if self._use_proxy:
            return {
                'type': 'raster-dem',
                'tiles': ['/api/tiles/maptiler-terrain/{z}/{x}/{y}'],
                'tileSize': 512,
                'maxzoom': 14,
                'attribution': '© MapTiler © OpenStreetMap contributors'
            }
        else:
            return {
                'type': 'raster-dem',
                'url': f'https://api.maptiler.com/tiles/terrain-rgb-v2/tiles.json?key={self._api_key}',
                'attribution': '© MapTiler © OpenStreetMap contributors'
            }
    
    @property
    def exaggeration(self):
        return 1.5 if self._use_proxy else 1
