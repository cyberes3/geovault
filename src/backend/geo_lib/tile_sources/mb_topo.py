from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class MapbuilderTopoTileSource(TileSource):
    """Mapbuilder Topo tile source."""
    
    @property
    def id(self):
        return 'mb_topo'
    
    @property
    def name(self):
        return 'Mapbuilder Topo'
    
    @property
    def type(self):
        return 'xyz'
    
    @property
    def requires_proxy(self):
        return True
    
    @property
    def url_template(self):
        return 'https://caltopo.com/tile/mb_topo/{z}/{x}/{y}.png?ctdarkmode=false'
    
    @property
    def proxy_config(self):
        return {
            'headers': CALTOPO_HEADERS
        }
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/mb_topo/{z}/{x}/{y}',
            'attribution': '© CalTopo, © OpenStreetMap contributors, Various DEM sources'
        }
