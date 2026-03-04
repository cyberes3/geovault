"""
MapTiler maps tile source configuration.

This module dynamically generates MapTiler maps configurations as tile sources.
MapTiler maps are vector tile sources that can be used directly without a proxy.
"""

import functools

import requests

from geo_lib.tile_sources.base import TileSource
from website.config_loader import get_config_loader


class MapTilerMapTileSource(TileSource):
    """Individual MapTiler map tile source."""

    def __init__(self, map_id, api_key, site_domain, use_proxy=False, hidden=False):
        self._map_id = map_id
        self._api_key = api_key
        self._site_domain = site_domain
        self._use_proxy = use_proxy
        self._hidden = hidden
        self._display_name = _fetch_map_name(map_id, api_key, site_domain)

    @property
    def id(self):
        return f'maptiler-{self._map_id}'

    @property
    def name(self):
        return self._display_name

    @property
    def type(self):
        return 'maptiler'

    @property
    def requires_proxy(self):
        return self._use_proxy

    @property
    def hidden(self):
        return self._hidden

    @property
    def url_template(self):
        """URL template for vector tiles when proxying.
        MapTiler vector tiles use a pattern like: https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key={key}
        But the actual tile URLs come from the style.json, so we use a generic pattern."""
        if self._use_proxy:
            # This is a generic pattern - actual tile URLs will be in the style.json
            # The proxy endpoint will handle extracting the correct URL from the style
            return f'https://api.maptiler.com/tiles/v3/{{z}}/{{x}}/{{y}}.pbf?key={self._api_key}'
        return None

    @property
    def proxy_config(self):
        """Proxy configuration with Origin header for MapTiler."""
        if self._use_proxy:
            return {
                'headers': {
                    'Origin': self._site_domain
                }
            }
        return None

    @property
    def client_config(self):
        style_url = f'https://api.maptiler.com/maps/{self._map_id}/style.json?key={self._api_key}'
        if self._use_proxy:
            style_url = f'/api/tiles/style/{self._map_id}'
        return {
            'type': 'maptiler',
            'style_url': style_url,
            'map_id': self._map_id,
            'attribution': '© MapTiler © OpenStreetMap contributors'
        }


def generate_maptiler_sources():
    """
    Generate MapTiler map tile source instances based on configuration.
    
    Returns:
        List of MapTilerMapTileSource instances. Returns empty list if API key 
        is not configured or no maps are configured.
    """
    config = get_config_loader()

    # Get MapTiler API key
    api_key = config.get_with_env_override(
        'maptiler.api_key',
        'MAPTILER_API_KEY',
        None
    )

    # If no API key, skip registration
    if not api_key:
        return []

    # Get list of map IDs
    map_ids = config.get_list('maptiler.maps', [])

    # If no maps configured, skip registration
    if not map_ids:
        return []

    # Get site domain for MapTiler API requests
    site_domain = config.get_str('site.domain', '')

    # Get proxy setting
    use_proxy = config.get_bool('maptiler.proxy_tiles', False)

    # Get list of map IDs to hide from the basemap selector
    hidden_map_ids = config.get_list('maptiler.hidden_maps', [])
    hidden_set = {m for m in hidden_map_ids if m and isinstance(m, str)}

    # Build tile sources for each map
    sources = []
    for map_id in map_ids:
        if not map_id or not isinstance(map_id, str):
            continue

        sources.append(MapTilerMapTileSource(
            map_id, api_key, site_domain, use_proxy, hidden=map_id in hidden_set
        ))

    return sources


@functools.lru_cache(maxsize=None)
def _fetch_map_name(map_id, api_key, site_domain):
    """
    Fetch the display name for a MapTiler map from its style.json.

    Cached for the lifetime of the server to avoid repeated API calls.

    Args:
        map_id: MapTiler map ID
        api_key: MapTiler API key
        site_domain: Site domain for MapTiler API requests (just the domain, no protocol)

    Returns:
        Display name from the style.json, or a formatted fallback name
    """
    try:
        style_url = f'https://api.maptiler.com/maps/{map_id}/style.json?key={api_key}'
        # MapTiler expects just the domain in Origin header to match their allowed origins list
        headers = {
            'Origin': site_domain
        }
        response = requests.get(style_url, headers=headers, timeout=5)

        if response.status_code == 200:
            style_data = response.json()
            # Get the name from the style.json
            if 'name' in style_data:
                return f"MapTiler {style_data['name']}"
    except Exception as e:
        # Log error but continue with fallback name
        print(f"Warning: Could not fetch name for map '{map_id}': {e}")

    # Fallback: format the map ID as a display name
    return f"MapTiler {map_id.replace('-', ' ').title()}"
