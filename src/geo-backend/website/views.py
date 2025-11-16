import os
from pathlib import Path
from datetime import datetime, timedelta
from django.contrib.auth.decorators import login_required
from django.shortcuts import render
from django.http import HttpResponse, JsonResponse
from django.conf import settings
from urllib.request import urlopen, Request
from urllib.error import URLError
from geo_lib.tile_sources import get_tile_source, get_tile_sources_for_client
from geo_lib.logging.console import get_tile_logger, get_access_logger

tile_logger = get_tile_logger()
access_logger = get_access_logger()


@login_required
def index(request):
    return render(request, "index.html")


@login_required
def standalone_map(request):
    return render(request, "standalone_map.html")


def get_tile_cache_path(service, z, x, y):
    """
    Generate the cache file path for a tile.
    
    Args:
        service: The tile service name
        z: Zoom level
        x: Tile X coordinate
        y: Tile Y coordinate
    
    Returns:
        Path object for the cache file
    """
    cache_dir = Path(settings.TILE_CACHE_DIR)
    # Validate service name to prevent directory traversal
    service = service.replace('/', '_').replace('..', '_')
    return cache_dir / service / str(z) / str(x) / f"{y}.png"


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
    try:
        cache_dir = cache_path.parent
        # Create directory structure with 0o700 permissions
        original_umask = os.umask(0o077)  # Restrict permissions to owner only
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            # Ensure directory has correct permissions
            os.chmod(cache_dir, 0o700)
        finally:
            os.umask(original_umask)
        return True
    except OSError as e:
        tile_logger.warning(f"Failed to create cache directory {cache_dir}: {e}")
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
    except OSError as e:
        tile_logger.warning(f"Failed to save tile to cache {cache_path}: {e}")
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
    except OSError as e:
        tile_logger.warning(f"Failed to read tile from cache {cache_path}: {e}")
        return None


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
    # Get tile source configuration from registry
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
    
    # Check cache if enabled
    tile_data = None
    cache_path = None
    
    if settings.TILE_CACHE_ENABLED:
        try:
            cache_path = get_tile_cache_path(service, z, x, y)
            if is_tile_cached(cache_path):
                tile_data = read_tile_from_cache(cache_path)
                if tile_data:
                    tile_logger.debug(f"Tile cache hit: {service}/{z}/{x}/{y}")
                    http_response = HttpResponse(tile_data, content_type='image/png')
                    http_response['Cache-Control'] = 'public, max-age=86400'  # Cache for 1 day
                    return http_response
        except Exception as e:
            # Log cache error but continue to fetch from source
            tile_logger.warning(f"Cache check failed for {service}/{z}/{x}/{y}: {e}")
    
    # Cache miss or cache disabled - fetch from external service
    tile_url = url_template.format(z=z, x=x, y=y)
    
    try:
        # Create request with headers from proxy_config
        headers = proxy_config.get('headers', {})
        req = Request(tile_url, headers=headers)
        
        # Fetch the tile
        with urlopen(req) as response:
            tile_data = response.read()
            content_type = response.headers.get('Content-Type', 'image/png')
            
            # Save to cache if enabled
            if settings.TILE_CACHE_ENABLED and cache_path:
                try:
                    save_tile_to_cache(cache_path, tile_data)
                    tile_logger.debug(f"Tile cached: {service}/{z}/{x}/{y}")
                except Exception as e:
                    # Log cache save error but don't fail the request
                    tile_logger.warning(f"Failed to cache tile {service}/{z}/{x}/{y}: {e}")
            
            # Return the tile with appropriate headers
            http_response = HttpResponse(tile_data, content_type=content_type)
            http_response['Cache-Control'] = 'public, max-age=86400'  # Cache for 1 day
            return http_response
            
    except URLError as e:
        return HttpResponse(f'Error fetching tile: {str(e)}', status=502)
    except Exception as e:
        return HttpResponse(f'Unexpected error: {str(e)}', status=500)


def get_tile_sources(request):
    """
    API endpoint to get all available tile sources with their configurations.
    
    Returns JSON response with tile source configurations for the client.
    """
    sources = get_tile_sources_for_client()
    return JsonResponse({'sources': sources})
