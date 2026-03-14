import hashlib
import json
import os
import re
import traceback
from datetime import datetime, timedelta
from pathlib import Path

import requests
from django.http import HttpResponse, JsonResponse

from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.settings_utils import get_required_setting, get_setting
from geo_lib.tile_sources.registry import get_tile_source, get_tile_sources_for_client
from geo_lib.utils.secure_path import is_path_under_base, secure_filename
from website.config_loader import get_config_loader

_logger = get_tagged_logger()


def _etag_for_bytes(data):
    """Return a strong ETag (W/"hex") for binary data."""
    return 'W/"' + hashlib.md5(data).hexdigest() + '"'


def _etag_for_json(obj):
    """Return a strong ETag (W/"hex") for a JSON-serializable object (canonical sort)."""
    payload = json.dumps(obj, sort_keys=True, separators=(',', ':'))
    return 'W/"' + hashlib.md5(payload.encode('utf-8')).hexdigest() + '"'


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

    # Check if this source requires a proxy
    if not tile_source.get('requires_proxy', False):
        return HttpResponse('Service does not require proxy', status=400)

    # Get proxy configuration
    proxy_config = tile_source.get('proxy_config', {})
    url_template = tile_source.get('url_template')

    if not url_template:
        return HttpResponse('Service configuration error: missing url_template', status=500)

    # Calculate HTTP cache max-age from cache_expiry_days (convert days to seconds)
    # OSMF / openmaps.fr require at least 7 days for OSM/OpenTopoMap/OpenHikingMap
    cache_expiry_days = get_required_setting('TILE_CACHE_EXPIRY_DAYS')
    if service in ('osm', 'opentopomap', 'openhikingmap'):
        cache_expiry_days = max(cache_expiry_days, 7)
    cache_max_age_seconds = cache_expiry_days * 24 * 60 * 60

    # Determine file extension from URL template (fallback, will be updated from response if needed)
    url_extension = 'tile'
    if url_template:
        # Extract extension from URL template (e.g., .png, .webp, .jpg, .pbf)
        if '.pbf' in url_template:
            url_extension = 'pbf'
        elif '.png' in url_template:
            url_extension = 'png'
        elif '.webp' in url_template:
            url_extension = 'webp'
        elif '.jpg' in url_template or '.jpeg' in url_template:
            url_extension = 'jpg'

    tile_data = None

    if get_setting('TILE_CACHE_ENABLED', False):
        # Only check for the extension from URL template (no fallback)
        if url_extension != 'tile':
            cache_path = get_tile_cache_path(service, z, x, y, url_extension)
            if cache_path is not None and is_tile_cached(cache_path, service):
                tile_data = read_tile_from_cache(cache_path)
                if not tile_data:
                    return HttpResponse('Cached file not found', status=400)
        
        if tile_data:
            _logger.debug(f"Tile cache hit: {service}/{z}/{x}/{y}")
            etag = _etag_for_bytes(tile_data)
            if _matches_if_none_match(request, etag):
                resp_304 = HttpResponse(status=304)
                _apply_cache_headers(resp_304, f'public, max-age={cache_max_age_seconds}', etag=etag)
                return resp_304
            content_type_map = {
                'pbf': 'application/x-protobuf',
                'png': 'image/png',
                'webp': 'image/webp',
                'jpg': 'image/jpeg',
                'tile': 'application/octet-stream'
            }
            content_type = content_type_map.get(url_extension, 'image/png')
            http_response = HttpResponse(tile_data, content_type=content_type)
            _apply_cache_headers(http_response, f'public, max-age={cache_max_age_seconds}', etag=etag)
            return http_response

    # Cache miss or cache disabled - fetch from external service
    tile_url = url_template.format(z=z, x=x, y=y)

    try:
        # Create request with headers from proxy_config (copy so we don't mutate cached config)
        headers = dict(proxy_config.get('headers', {}))
        # For OSM/OpenTopoMap/OpenHikingMap, send a valid Referer when in browser (tile servers expect it)
        if service in ('osm', 'opentopomap', 'openhikingmap'):
            referer = request.META.get('HTTP_REFERER', '').strip()
            if not referer:
                referer = request.build_absolute_uri('/')
            if referer:
                headers['Referer'] = referer

        # Use requests library with streaming for better performance
        response = requests.get(tile_url, headers=headers, stream=True, timeout=10)

        if response.status_code != 200:
            return HttpResponse(f'Upstream error: {response.status_code}', status=response.status_code)

        # Get Content-Type from response, parse to extract just the MIME type
        raw_content_type = response.headers.get('Content-Type', '')
        # Extract MIME type (remove parameters like charset, boundary, etc.)
        if raw_content_type:
            content_type = raw_content_type.split(';')[0].strip()
        else:
            # Fallback only if no Content-Type header at all
            content_type = 'image/png'

        # Determine file extension from Content-Type header for caching
        if 'application/x-protobuf' in content_type or 'application/vnd.mapbox-vector-tile' in content_type:
            url_extension = 'pbf'
        elif 'image/webp' in content_type:
            url_extension = 'webp'
        elif 'image/png' in content_type:
            url_extension = 'png'
        elif 'image/jpeg' in content_type or 'image/jpg' in content_type:
            url_extension = 'jpg'

        # Read tile data
        tile_data = response.content

        # Save to cache if enabled (use correct extension based on Content-Type)
        if get_setting('TILE_CACHE_ENABLED', False):
            try:
                cache_path = get_tile_cache_path(service, z, x, y, url_extension)
                if cache_path is not None:
                    save_tile_to_cache(cache_path, tile_data)
                _logger.debug(f"Tile cached: {service}/{z}/{x}/{y}")
            except Exception as e:
                # Log cache save error but don't fail the request
                _logger.warning(f"Failed to cache tile {service}/{z}/{x}/{y}: {e}")

        # Return the tile with appropriate headers (ETag and optional 304)
        etag = _etag_for_bytes(tile_data)
        if _matches_if_none_match(request, etag):
            resp_304 = HttpResponse(status=304)
            _apply_cache_headers(resp_304, f'public, max-age={cache_max_age_seconds}', etag=etag)
            resp_304['Access-Control-Allow-Origin'] = '*'
            return resp_304
        http_response = HttpResponse(tile_data, content_type=content_type)
        _apply_cache_headers(http_response, f'public, max-age={cache_max_age_seconds}', etag=etag)
        http_response['Access-Control-Allow-Origin'] = '*'
        return http_response

    except:
        _logger.error(f"Unexpected error fetching tile {service}/{z}/{x}/{y}: {traceback.format_exc()}")
        return HttpResponse(f'Unexpected error', status=500)


def get_tile_sources(request):
    """
    API endpoint to get all available tile sources with their configurations.

    Returns JSON response with tile source configurations for the client.
    Public (no auth) so the public tracker share view can offer layer switching.
    Not cached so clients always get current server config.
    """
    sources = get_tile_sources_for_client()
    payload = {'sources': sources}
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
    # Get the tile source
    source_id = f'maptiler-{map_id}'
    tile_source = get_tile_source(source_id)
    
    if not tile_source:
        return HttpResponse('Map not found', status=404)
    
    # Check if this source requires a proxy
    if not tile_source.get('requires_proxy', False):
        return HttpResponse('Map does not require proxy', status=400)
    
    # Get the original style URL from the source's original config
    # We need to fetch it from MapTiler to get the actual style.json
    proxy_config = tile_source.get('proxy_config', {})
    config_loader = get_config_loader()
    api_key = config_loader.get_with_env_override(
        'maptiler.api_key',
        'MAPTILER_API_KEY',
        None
    )

    if not api_key:
        return HttpResponse('MapTiler API key not configured', status=500)
    
    style_url = f'https://api.maptiler.com/maps/{map_id}/style.json?key={api_key}'
    
    try:
        # Fetch the style.json from MapTiler
        headers = proxy_config.get('headers', {})
        response = requests.get(style_url, headers=headers, timeout=10)
        
        if response.status_code != 200:
            return HttpResponse(f'Upstream error: {response.status_code}', status=response.status_code)
        
        # Parse the style.json
        style_data = response.json()
        
        # Replace tile URLs in sources to use proxy endpoints
        # MapTiler style.json has sources with "tiles" arrays containing URLs
        if 'sources' in style_data:
            for source_name, source_config in style_data['sources'].items():
                if 'tiles' in source_config and isinstance(source_config['tiles'], list):
                    # Replace each tile URL with proxy URL
                    # MapTiler tile URLs look like: https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=...
                    # We need to extract the z/x/y pattern and replace with proxy endpoint
                    new_tiles = []
                    for tile_url in source_config['tiles']:
                        # Extract the tile path pattern (e.g., /tiles/v3/{z}/{x}/{y}.pbf)
                        # MapTiler uses patterns like: https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf
                        # Replace with our proxy endpoint
                        proxy_url = f'/api/tiles/{source_id}/{{z}}/{{x}}/{{y}}'
                        new_tiles.append(proxy_url)
                    source_config['tiles'] = new_tiles
        
        # Return the modified style.json (ETag and optional 304)
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
    except:
        _logger.error(f"Unexpected error fetching style.json for {map_id}: {traceback.format_exc()}")
        return HttpResponse(f'Unexpected error', status=500)


_TILE_CACHE_EXTENSIONS = frozenset({'pbf', 'png', 'webp', 'jpg', 'tile'})
_MAX_ZOOM = 30


def get_tile_cache_path(service, z, x, y, extension='tile'):
    """
    Generate the cache file path for a tile.
    Validates z/x/y and extension, resolves the path, and ensures it stays under TILE_CACHE_DIR.

    Args:
        service: The tile service name
        z: Zoom level
        x: Tile X coordinate
        y: Tile Y coordinate
        extension: File extension (default: 'tile' for generic)

    Returns:
        Resolved Path under TILE_CACHE_DIR, or None if validation fails
    """
    try:
        z = int(z)
        x = int(x)
        y = int(y)
    except (TypeError, ValueError):
        return None
    if z < 0 or z > _MAX_ZOOM:
        return None
    max_tile = 2**z
    if x < 0 or x >= max_tile or y < 0 or y >= max_tile:
        return None
    if extension not in _TILE_CACHE_EXTENSIONS:
        return None
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    service = secure_filename(service)
    if not service:
        service = "tile_service"
    path = cache_dir / service / str(z) / str(x) / f"{y}.{extension}"
    try:
        resolved = path.resolve()
    except (OSError, RuntimeError):
        return None
    if not is_path_under_base(resolved, cache_dir):
        return None
    return resolved


def is_tile_cached(cache_path, service=None):
    """
    Check if a tile is cached and not expired.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path to the cached tile file
        service: Optional tile service id; if osm/opentopomap/openhikingmap, enforces OSMF minimum 7-day cache.

    Returns:
        True if cached and valid, False otherwise
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_dir):
        return False
    try:
        if not resolved.exists():
            return False
    except PermissionError:
        return False

    try:
        file_mtime = datetime.fromtimestamp(resolved.stat().st_mtime)
        expiry_days = get_required_setting('TILE_CACHE_EXPIRY_DAYS')
        if service in ('osm', 'opentopomap', 'openhikingmap'):
            expiry_days = max(expiry_days, 7)  # OSMF / openmaps.fr policy: at least 7 days
        expiry_time = timedelta(days=expiry_days)

        if datetime.now() - file_mtime > expiry_time:
            try:
                resolved.unlink()
            except OSError:
                pass
            return False

        return True
    except OSError:
        return False


def ensure_cache_directory(cache_path):
    """
    Ensure the cache directory structure exists with proper permissions.
    Path must resolve under TILE_CACHE_DIR; only the resolved path's parent is used for I/O.

    Args:
        cache_path: Path to the cache file (parent directories will be created)

    Returns:
        True if successful, False otherwise
    """
    cache_base = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_base):
        return False
    cache_dir = resolved.parent
    try:
        original_umask = os.umask(0o077)
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            os.chmod(cache_dir, 0o700)
        finally:
            os.umask(original_umask)
        return True
    except Exception:
        _logger.error(f"Failed to create cache directory {cache_dir}: {traceback.format_exc()}")
        return False


def save_tile_to_cache(cache_path, tile_data):
    """
    Save tile data to cache with proper permissions.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path where to save the tile
        tile_data: Binary tile data

    Returns:
        True if successful, False otherwise
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return False
    if not is_path_under_base(resolved, cache_dir):
        return False
    try:
        if not ensure_cache_directory(resolved):
            return False

        original_umask = os.umask(0o177)
        try:
            resolved.write_bytes(tile_data)
            os.chmod(resolved, 0o600)
        finally:
            os.umask(original_umask)

        return True
    except Exception:
        _logger.error(f"Failed to save tile to cache {resolved}: {traceback.format_exc()}")
        return False


def read_tile_from_cache(cache_path):
    """
    Read tile data from cache.
    Path must resolve under TILE_CACHE_DIR; only the resolved path is used for I/O.

    Args:
        cache_path: Path to the cached tile file

    Returns:
        Binary tile data, or None if read fails or path invalid
    """
    cache_dir = Path(get_required_setting('TILE_CACHE_DIR'))
    try:
        resolved = cache_path.resolve()
    except (OSError, RuntimeError):
        return None
    if not is_path_under_base(resolved, cache_dir):
        return None
    try:
        return resolved.read_bytes()
    except Exception:
        _logger.error(f"Failed to read tile from cache {resolved}: {traceback.format_exc()}")
        return None
