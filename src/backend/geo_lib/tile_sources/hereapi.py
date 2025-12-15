import base64

from geo_lib.tile_sources.registry import TileSource


class HereApiTileSource(TileSource):
    """HERE API base map tile source."""

    # Base64 encoded API key
    _API_KEY_B64 = 'S1h0UU9wVW13MmprRUpCMmdCUWg3WXZ6aUNCcDgxajlwOVgtT0hXWjlGMA=='

    @property
    def id(self):
        return 'hereapi'

    @property
    def name(self):
        return 'HERE Streets'

    @property
    def type(self):
        return 'xyz'

    @property
    def requires_proxy(self):
        return True

    @property
    def url_template(self):
        api_key = base64.b64decode(self._API_KEY_B64).decode('utf-8')
        return f'https://maps.hereapi.com/v3/base/mc/{{z}}/{{x}}/{{y}}/png?xnlp=CL_JSMv3.1.38.0&apikey={api_key}&size=512'

    @property
    def proxy_config(self):
        return {
            'headers': {
                'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0',
                'Accept': '*/*',
                'Accept-Language': 'en-US,en;q=0.5',
                'Accept-Encoding': 'identity',
                'Origin': 'https://www.amazon.com',
                'DNT': '1',
                'Connection': 'keep-alive',
                'Referer': 'https://www.amazon.com/',
                'Sec-Fetch-Dest': 'empty',
                'Sec-Fetch-Mode': 'cors',
                'Sec-Fetch-Site': 'cross-site',
                'Pragma': 'no-cache',
                'Cache-Control': 'no-cache'
            }
        }

    @property
    def client_config(self):
        return {
            'type': 'xyz',
            'url': '/api/tiles/hereapi/{z}/{x}/{y}',
            'tileSize': 512
        }
