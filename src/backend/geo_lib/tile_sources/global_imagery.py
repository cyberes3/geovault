from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class GlobalImageryTileSource(TileSource):
    """Global Imagery tile source."""
    
    @property
    def id(self):
        return 'global-imagery'
    
    @property
    def name(self):
        return 'Global Imagery'
    
    @property
    def type(self):
        return 'xyz'
    
    @property
    def requires_proxy(self):
        return True
    
    @property
    def url_template(self):
        return 'https://caltopo.com/tile/imagery/{z}/{x}/{y}.png'
    
    @property
    def proxy_config(self):
        return {
            'headers': CALTOPO_HEADERS
        }
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/global-imagery/{z}/{x}/{y}',
            'tileSize': 256,
            'attribution': '© CalTopo, © MapBox, © Maxar, USDA Farm Service Agency, © EOX IT, contains modified Copernicus data (2019)'
        }
