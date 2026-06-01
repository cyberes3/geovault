from geo_lib.tile_sources.base import TileSource


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