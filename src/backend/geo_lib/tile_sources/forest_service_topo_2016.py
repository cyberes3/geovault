from geo_lib.tile_sources.caltopo import CALTOPO_HEADERS
from geo_lib.tile_sources.base import TileSource


class ForestServiceTopo2016TileSource(TileSource):
    """Forest Service Topo 2016 (CalTopo f16a) tile source."""

    @property
    def id(self):
        return 'forest-service-topo-2016'

    @property
    def name(self):
        return 'Forest Service Topo 2016'

    @property
    def type(self):
        return 'xyz'

    @property
    def requires_proxy(self):
        return True

    @property
    def url_template(self):
        return 'https://caltopo.com/tile/f16a/{z}/{x}/{y}.png?ctdarkmode=false'

    @property
    def proxy_config(self):
        return {
            'headers': CALTOPO_HEADERS
        }

    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/forest-service-topo-2016/{z}/{x}/{y}',
            'tileSize': 256,
            'attribution': (
                '© CalTopo, U.S. Forest Service, © OpenStreetMap contributors, Various DEM sources'
            )
        }
