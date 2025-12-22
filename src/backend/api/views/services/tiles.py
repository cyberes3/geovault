import json
import os
import re
import traceback
from datetime import datetime, timedelta
from pathlib import Path

import requests
from django.conf import settings
from django.http import HttpResponse, JsonResponse

from geo_lib.logging.console import get_tagged_logger
from geo_lib.tile_sources.registry import get_tile_source, get_tile_sources_for_client
from website.config_loader import get_config_loader

_logger = get_tagged_logger()


def tile_proxy(request, service, z, x, y):
    """
    Proxy tile requests to external tile servers to avoid CORS issues.
    Supports disk caching to avoid repeatedly fetching the same tiles.

    Args:
        service: The tile service name (e.g., 'mb_topo')
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
    cache_max_age_seconds = settings.TILE_CACHE_EXPIRY_DAYS * 24 * 60 * 60

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

    # Check cache if enabled
    tile_data = None
    cache_path = None

    if settings.TILE_CACHE_ENABLED:
        # Try common extensions for cached files (including .pbf for vector tiles)
        for ext in ['pbf', 'webp', 'png', 'jpg', 'tile']:
            test_cache_path = get_tile_cache_path(service, z, x, y, ext)
            if is_tile_cached(test_cache_path):
                tile_data = read_tile_from_cache(test_cache_path)
                if tile_data:
                    cache_path = test_cache_path
                    url_extension = ext
                    break
        if tile_data:
            _logger.debug(f"Tile cache hit: {service}/{z}/{x}/{y}")
            # Determine content type from extension
            content_type_map = {
                'pbf': 'application/x-protobuf',
                'png': 'image/png',
                'webp': 'image/webp',
                'jpg': 'image/jpeg',
                'tile': 'application/octet-stream'
            }
            content_type = content_type_map.get(url_extension, 'image/png')
            http_response = HttpResponse(tile_data, content_type=content_type)
            http_response['Cache-Control'] = f'public, max-age={cache_max_age_seconds}'
            http_response['Access-Control-Allow-Origin'] = '*'
            return http_response

    # Cache miss or cache disabled - fetch from external service
    tile_url = url_template.format(z=z, x=x, y=y)

    try:
        # Create request with headers from proxy_config
        headers = proxy_config.get('headers', {})

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
        if settings.TILE_CACHE_ENABLED:
            try:
                cache_path = get_tile_cache_path(service, z, x, y, url_extension)
                save_tile_to_cache(cache_path, tile_data)
                _logger.debug(f"Tile cached: {service}/{z}/{x}/{y}")
            except Exception as e:
                # Log cache save error but don't fail the request
                _logger.warning(f"Failed to cache tile {service}/{z}/{x}/{y}: {e}")

        # Return the tile with appropriate headers
        http_response = HttpResponse(tile_data, content_type=content_type)
        http_response['Cache-Control'] = f'public, max-age={cache_max_age_seconds}'
        http_response['Access-Control-Allow-Origin'] = '*'  # Allow cross-origin requests
        return http_response

    except:
        _logger.error(f"Unexpected error fetching tile {service}/{z}/{x}/{y}: {traceback.format_exc()}")
        return HttpResponse(f'Unexpected error', status=500)


def get_tile_sources(request):
    """
    API endpoint to get all available tile sources with their configurations.

    Returns JSON response with tile source configurations for the client.
    Cached for 1 day (86400 seconds).
    """
    sources = get_tile_sources_for_client()
    response = JsonResponse({'sources': sources})
    # Cache for 1 day (86400 seconds)
    response['Cache-Control'] = 'public, max-age=86400'
    return response


def style_proxy(request, map_id):
    """
    Proxy MapTiler style.json requests and modify tile URLs to use proxy endpoints.
    
    Args:
        map_id: The MapTiler map ID (e.g., 'topo-v4')
    """
    # Get the tile source
    source_id = f'maptiler_{map_id}'
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
    site_domain = config_loader.get_str('site.domain', '')
    
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
        
        # Return the modified style.json
        http_response = JsonResponse(style_data)
        # Cache style.json for 1 hour (3600 seconds) - styles don't change often
        http_response['Cache-Control'] = 'public, max-age=3600'
        http_response['Access-Control-Allow-Origin'] = '*'
        return http_response
        
    except json.JSONDecodeError:
        _logger.error(f"Invalid JSON in style.json for {map_id}")
        return HttpResponse('Invalid style.json', status=500)
    except Exception as e:
        _logger.error(f"Unexpected error fetching style.json for {map_id}: {traceback.format_exc()}")
        return HttpResponse(f'Unexpected error', status=500)


def get_tile_cache_path(service, z, x, y, extension='tile'):
    """
    Generate the cache file path for a tile.
    
    Args:
        service: The tile service name
        z: Zoom level
        x: Tile X coordinate
        y: Tile Y coordinate
        extension: File extension (default: 'tile' for generic)
    
    Returns:
        Path object for the cache file
    """
    cache_dir = Path(settings.TILE_CACHE_DIR)
    # Validate service name to prevent directory traversal
    service = service.replace('/', '_').replace('..', '_')
    return cache_dir / service / str(z) / str(x) / f"{y}.{extension}"


def is_tile_cached(cache_path):
    """
    Check if a tile is cached and not expired.
    
    Args:
        cache_path: Path to the cached tile file
    
    Returns:
        True if cached and valid, False otherwise
    """
    if not cache_path.exists():
        return False

    try:
        # Check if file is expired
        file_mtime = datetime.fromtimestamp(cache_path.stat().st_mtime)
        expiry_time = timedelta(days=settings.TILE_CACHE_EXPIRY_DAYS)

        if datetime.now() - file_mtime > expiry_time:
            # File expired, remove it
            try:
                cache_path.unlink()
            except OSError:
                pass
            return False

        return True
    except OSError:
        return False


def ensure_cache_directory(cache_path):
    """
    Ensure the cache directory structure exists with proper permissions.
    
    Args:
        cache_path: Path to the cache file (parent directories will be created)
    
    Returns:
        True if successful, False otherwise
    """
    cache_dir = cache_path.parent
    try:
        # Create directory structure with 0o700 permissions
        original_umask = os.umask(0o077)  # Restrict permissions to owner only
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            # Ensure directory has correct permissions
            os.chmod(cache_dir, 0o700)
        finally:
            os.umask(original_umask)
        return True
    except:
        _logger.error(f"Failed to create cache directory {cache_dir}: {traceback.format_exc()}")
        return False


def save_tile_to_cache(cache_path, tile_data):
    """
    Save tile data to cache with proper permissions.
    
    Args:
        cache_path: Path where to save the tile
        tile_data: Binary tile data
    
    Returns:
        True if successful, False otherwise
    """
    try:
        # Ensure parent directories exist
        if not ensure_cache_directory(cache_path):
            return False

        # Write file with restricted permissions
        original_umask = os.umask(0o177)  # Restrict to owner read/write only (0o600)
        try:
            cache_path.write_bytes(tile_data)
            # Ensure file has correct permissions
            os.chmod(cache_path, 0o600)
        finally:
            os.umask(original_umask)

        return True
    except:
        _logger.error(f"Failed to save tile to cache {cache_path}: {traceback.format_exc()}")
        return False


def read_tile_from_cache(cache_path):
    """
    Read tile data from cache.
    
    Args:
        cache_path: Path to the cached tile file
    
    Returns:
        Binary tile data, or None if read fails
    """
    try:
        return cache_path.read_bytes()
    except:
        _logger.error(f"Failed to read tile from cache {cache_path}: {traceback.format_exc()}")
        return None
