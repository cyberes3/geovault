from django.contrib.auth.decorators import login_required
from django.shortcuts import render
from django.http import HttpResponse, JsonResponse
from urllib.request import urlopen, Request
from urllib.error import URLError
from geo_lib.tile_sources import get_tile_source, get_tile_sources_for_client


@login_required
def index(request):
    return render(request, "index.html")


@login_required
def standalone_map(request):
    return render(request, "standalone_map.html")


def tile_proxy(request, service, z, x, y):
    """
    Proxy tile requests to external tile servers to avoid CORS issues.
    
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
    
    # Format the tile URL
    tile_url = url_template.format(z=z, x=x, y=y)
    
    try:
        # Create request with headers from proxy_config
        headers = proxy_config.get('headers', {})
        req = Request(tile_url, headers=headers)
        
        # Fetch the tile
        with urlopen(req) as response:
            tile_data = response.read()
            content_type = response.headers.get('Content-Type', 'image/png')
            
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
