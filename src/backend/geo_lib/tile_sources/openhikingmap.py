"""
OpenHikingMap tile source configuration.

This tile source uses openmaps.fr, which provides OpenHikingMap tiles.
See: https://openmaps.fr/tile-usage-policy.html

This tile source does not require a proxy as it can be accessed directly.
"""

from geo_lib.tile_sources.base import TileSource
from geo_lib.utils.version import get_user_agent


class OpenHikingMapTileSource(TileSource):
    """OpenHikingMap tile source from openmaps.fr."""

    @property
    def id(self):
        return 'openhikingmap'

    @property
    def name(self):
        return 'OpenHikingMap'

    @property
    def type(self):
        return 'xyz'

    @property
    def enabled(self):
        return False

    @property
    def url_template(self):
        return 'https://tile.openmaps.fr/openhikingmap/{z}/{x}/{y}.png'

    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': 'https://tile.openmaps.fr/openhikingmap/{z}/{x}/{y}.png',
            'tileSize': 256,
            'attribution': '<a href="https://wiki.openstreetmap.org/wiki/OpenHikingMap">&copy; OpenHikingMap</a> <a href="https://openmaps.fr/donate">❤️ Donation</a> <a href="http://www.openstreetmap.org/copyright">&copy; OpenStreetMap</a>'
        }

    @property
    def proxy_config(self):
        """Proxy configuration with user agent for openmaps.fr."""
        return {
            'headers': {
                'User-Agent': get_user_agent()
            }
        }
