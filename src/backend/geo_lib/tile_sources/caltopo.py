"""
CalTopo tile service headers.

This module provides HTTP headers for accessing CalTopo's public tile service.
These headers are used by general-purpose tile sources that happen to use CalTopo's tile infrastructure.

No `User-Agent` entry: `geo_lib.tile_upstream.build_tile_upstream_headers()` always sets the
standard outbound `User-Agent` for tile requests, overriding any per-source value.
"""
CALTOPO_HEADERS = {
    'Accept': '*/*',
    'Accept-Language': 'en-US,en;q=0.5',
    'Accept-Encoding': 'identity',
    'Origin': 'https://caltopo.com',
    'DNT': '1',
    'Connection': 'keep-alive',
    'Referer': 'https://caltopo.com/map.html',
    'Sec-Fetch-Dest': 'empty',
    'Sec-Fetch-Mode': 'cors',
    'Sec-Fetch-Site': 'cross-site',
    'Pragma': 'no-cache',
    'Cache-Control': 'no-cache'
}
