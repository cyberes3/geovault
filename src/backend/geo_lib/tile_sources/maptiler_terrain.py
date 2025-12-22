from website.config_loader import get_config_loader
from geo_lib.tile_sources.base import TileSource


class MapTilerTerrainTileSource(TileSource):
    """MapTiler terrain tile source."""
    
    def __init__(self):
        self._config = get_config_loader()
        self._api_key = self._config.get_with_env_override(
            'maptiler.api_key',
            'MAPTILER_API_KEY',
            None
        )
        self._use_proxy = self._config.get_bool('maptiler.proxy_tiles', False)
        self._site_domain = self._config.get_str('site.domain')
    
    @property
    def enabled(self):
        return self._api_key is not None
    
    @property
    def id(self):
        return 'maptiler_terrain'
    
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
                'tiles': ['/api/tiles/maptiler_terrain/{z}/{x}/{y}'],
                'tileSize': 512,
                'maxzoom': 14
            }
        else:
            return {
                'type': 'raster-dem',
                'url': f'https://api.maptiler.com/tiles/terrain-rgb-v2/tiles.json?key={self._api_key}'
            }
    
    @property
    def exaggeration(self):
        return 1.5 if self._use_proxy else 1
