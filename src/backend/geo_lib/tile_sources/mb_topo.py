from geo_lib.tile_sources.registry import TileSource


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
            'headers': {
                'Origin': 'https://caltopo.com',
                'Referer': 'https://caltopo.com/map.html',
                'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36'
            }
        }
    
    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/mb_topo/{z}/{x}/{y}'
        }
