"""
CalTopo tile service headers.

This module provides HTTP headers for accessing CalTopo's public tile service.
These headers are used by general-purpose tile sources that happen to use CalTopo's tile infrastructure.
"""
CALTOPO_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64; rv:145.0) Gecko/20100101 Firefox/145.0',
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
