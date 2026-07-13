"""
Tile-serving views: proxying tiles/styles from external tile servers (with disk caching) and
listing available tile sources for the client. All public (no auth) so unauthenticated public
tracker/map shares can render basemap tiles.

Orchestrates the pure library logic in `geo_lib.tile_sources` (disk cache, upstream fetch,
MapTiler style URLs) and is responsible for everything HTTP-specific: status codes,
conditional-request (ETag/If-None-Match) handling, and Cache-Control headers.
"""
import hashlib
import json
import traceback

import requests
from django.http import HttpResponse, JsonResponse

from geo_lib.logging.console import get_tagged_logger
from geo_lib.tile_sources.maptiler import build_maptiler_style_url, get_maptiler_api_key
from geo_lib.tile_sources.registry import get_tile_source, get_tile_sources_for_client
from geo_lib.tile_sources.tile_cache import (
    cache_expiry_days_for_service,
    get_tile_cache_path,
    is_tile_cached,
    read_tile_from_cache,
    save_tile_to_cache,
)
from geo_lib.tile_sources.tile_fetch import UpstreamTileError, fetch_tile_from_upstream
from geo_lib.tile_upstream import build_tile_upstream_headers
from website.settings_utils import get_setting

_logger = get_tagged_logger()

_CONTENT_TYPE_BY_EXTENSION = {
    'pbf': 'application/x-protobuf',
    'png': 'image/png',
    'webp': 'image/webp',
    'jpg': 'image/jpeg',
    'tile': 'application/octet-stream',
}


def _etag_for_bytes(data):
    """Return a strong ETag (W/"hex") for binary data."""
    return 'W/"' + hashlib.md5(data).hexdigest() + '"'


def _etag_for_json(obj):
    """Return a strong ETag (W/"hex") for a JSON-serializable object (canonical sort)."""
    payload = json.dumps(obj, sort_keys=True, separators=(',', ':'))
    return 'W/"' + hashlib.md5(payload.encode('utf-8')).hexdigest() + '"'


def _style_has_text_layers(style_data):
    layers = style_data.get('layers', [])
    if not isinstance(layers, list):
        return False
    for layer in layers:
        if not isinstance(layer, dict) or layer.get('type') != 'symbol':
            continue
        layout = layer.get('layout')
        if not isinstance(layout, dict) or 'text-field' not in layout:
            continue
        text_field = layout.get('text-field')
        if isinstance(text_field, str) and not text_field.strip():
            continue
        return True
    return False


def _ensure_style_glyphs(style_data):
    if _style_has_text_layers(style_data) and not str(style_data.get('glyphs') or '').strip():
        style_data['glyphs'] = '/api/fonts/{fontstack}/{range}.pbf'


def _client_map_config_errors(sources):
    errors = []
    has_maplibre_style = any(
        source.get('type') == 'maptiler' and
        source.get('client_config', {}).get('style_url')
        for source in sources
    )
    if not has_maplibre_style:
        errors.append({
            'code': 'maplibre_not_configured',
            'message': 'Map setup is incomplete. Code: maplibre_not_configured.',
        })
    return errors


def _matches_if_none_match(request, etag):
    """Return True if request has If-None-Match header matching the given ETag."""
    raw = request.META.get('HTTP_IF_NONE_MATCH', '').strip()
    if not raw:
        return False
    # Header can be a single value or comma-separated list; * matches any
    if raw == '*':
        return True
    for candidate in (v.strip().strip('"') for v in raw.split(',')):
        if candidate == etag or candidate == etag.strip('W/"').rstrip('"'):
            return True
    return False


def _apply_cache_headers(response, cache_control, etag=None):
    """Set Cache-Control and optionally ETag; clear cookies for cacheability."""
    response['Cache-Control'] = cache_control
    if etag is not None:
        response['ETag'] = etag
    response.cookies.clear()
    if response.has_header('Set-Cookie'):
        del response['Set-Cookie']


def tile_proxy(request, service, z, x, y):
    """
    Proxy tile requests to external tile servers to avoid CORS issues.
    Supports disk caching to avoid repeatedly fetching the same tiles.
    Public (no auth) so public tracker share view can use proxied layers.

    Args:
        service: The tile service name (e.g., 'mb-topo')
        z: Zoom level
        x: Tile X coordinate
        y: Tile Y coordinate
    """
    tile_source = get_tile_source(service)

    if not tile_source:
        return HttpResponse('Service not found', status=404)

    if not tile_source.get('requires_proxy', False):
        return HttpResponse('Service does not require proxy', status=400)

    proxy_config = tile_source.get('proxy_config', {})
    url_template = tile_source.get('url_template')

    if not url_template:
        return HttpResponse('Service configuration error: missing url_template', status=500)

    cache_max_age_seconds = cache_expiry_days_for_service(service) * 24 * 60 * 60

    # Determine file extension from URL template (fallback, will be updated from response if needed)
    url_extension = 'tile'
    if '.pbf' in url_template:
        url_extension = 'pbf'
    elif '.png' in url_template:
        url_extension = 'png'
    elif '.webp' in url_template:
        url_extension = 'webp'
    elif '.jpg' in url_template or '.jpeg' in url_template:
        url_extension = 'jpg'

    if get_setting('TILE_CACHE_ENABLED', False) and url_extension != 'tile':
        cache_path = get_tile_cache_path(service, z, x, y, url_extension)
        if cache_path is not None and is_tile_cached(cache_path, service):
            tile_data = read_tile_from_cache(cache_path)
            if not tile_data:
                return HttpResponse('Cached file not found', status=400)

            _logger.debug(f"Tile cache hit: {service}/{z}/{x}/{y}")
            etag = _etag_for_bytes(tile_data)
            if _matches_if_none_match(request, etag):
                resp_304 = HttpResponse(status=304)
                _apply_cache_headers(resp_304, f'public, max-age={cache_max_age_seconds}', etag=etag)
                return resp_304
            content_type = _CONTENT_TYPE_BY_EXTENSION.get(url_extension, 'image/png')
            http_response = HttpResponse(tile_data, content_type=content_type)
            _apply_cache_headers(http_response, f'public, max-age={cache_max_age_seconds}', etag=etag)
            return http_response

    # Cache miss or cache disabled - fetch from external service
    tile_url = url_template.format(z=z, x=x, y=y)

    try:
        result = fetch_tile_from_upstream(service, tile_url, request, proxy_config, fallback_extension=url_extension)
    except UpstreamTileError as e:
        return HttpResponse(f'Upstream error: {e.status_code}', status=e.status_code)
    except Exception:
        _logger.error(f"Unexpected error fetching tile {service}/{z}/{x}/{y}: {traceback.format_exc()}")
        return HttpResponse('Unexpected error', status=500)

    if get_setting('TILE_CACHE_ENABLED', False):
        try:
            cache_path = get_tile_cache_path(service, z, x, y, result.extension)
            if cache_path is not None:
                save_tile_to_cache(cache_path, result.data)
            _logger.debug(f"Tile cached: {service}/{z}/{x}/{y}")
        except Exception as e:
            # Log cache save error but don't fail the request
            _logger.warning(f"Failed to cache tile {service}/{z}/{x}/{y}: {e}")

    etag = _etag_for_bytes(result.data)
    if _matches_if_none_match(request, etag):
        resp_304 = HttpResponse(status=304)
        _apply_cache_headers(resp_304, f'public, max-age={cache_max_age_seconds}', etag=etag)
        resp_304['Access-Control-Allow-Origin'] = '*'
        return resp_304
    http_response = HttpResponse(result.data, content_type=result.content_type)
    _apply_cache_headers(http_response, f'public, max-age={cache_max_age_seconds}', etag=etag)
    http_response['Access-Control-Allow-Origin'] = '*'
    return http_response


def get_tile_sources(request):
    """
    API endpoint to get all available tile sources with their configurations.

    Returns JSON response with tile source configurations for the client.
    Public (no auth) so the public tracker share view can offer layer switching.
    Not cached so clients always get current server config.
    """
    sources = get_tile_sources_for_client()
    payload = {
        'sources': sources,
        'map_config_errors': _client_map_config_errors(sources),
    }
    response = JsonResponse(payload)
    _apply_cache_headers(response, 'no-store, no-cache, must-revalidate, max-age=0')
    return response


def style_proxy(request, map_id):
    """
    Proxy MapTiler style.json requests and modify tile URLs to use proxy endpoints.
    Public (no auth) so the public tracker share view can use MapTiler base layers.

    Args:
        map_id: The MapTiler map ID (e.g., 'topo-v4')
    """
    source_id = f'maptiler-{map_id}'
    tile_source = get_tile_source(source_id)

    if not tile_source:
        return HttpResponse('Map not found', status=404)

    if not tile_source.get('requires_proxy', False):
        return HttpResponse('Map does not require proxy', status=400)

    # Fetch the original style.json from MapTiler so we can rewrite its tile URLs.
    proxy_config = tile_source.get('proxy_config', {})
    api_key = get_maptiler_api_key()

    if not api_key:
        return HttpResponse('MapTiler API key not configured', status=500)

    style_url = build_maptiler_style_url(map_id, api_key)

    try:
        headers = build_tile_upstream_headers(source_id, request, proxy_config)
        response = requests.get(style_url, headers=headers, timeout=10)

        if response.status_code != 200:
            return HttpResponse(f'Upstream error: {response.status_code}', status=response.status_code)

        style_data = response.json()

        # MapTiler style.json sources have "tiles" arrays with URLs like
        # https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=... — replace each with our
        # proxy endpoint instead.
        if 'sources' in style_data:
            for source_config in style_data['sources'].values():
                if 'tiles' in source_config and isinstance(source_config['tiles'], list):
                    proxy_url = f'/api/tiles/{source_id}/{{z}}/{{x}}/{{y}}'
                    source_config['tiles'] = [proxy_url for _ in source_config['tiles']]

        _ensure_style_glyphs(style_data)

        etag = _etag_for_json(style_data)
        if _matches_if_none_match(request, etag):
            resp_304 = HttpResponse(status=304)
            _apply_cache_headers(resp_304, 'public, max-age=3600', etag=etag)
            return resp_304
        http_response = JsonResponse(style_data)
        _apply_cache_headers(http_response, 'public, max-age=3600', etag=etag)
        return http_response

    except json.JSONDecodeError:
        _logger.error(f"Invalid JSON in style.json for {map_id}")
        return HttpResponse('Invalid style.json', status=500)
    except Exception:
        _logger.error(f"Unexpected error fetching style.json for {map_id}: {traceback.format_exc()}")
        return HttpResponse('Unexpected error', status=500)
