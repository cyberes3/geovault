from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, TAG_PRIORITIES
from website.config_loader import get_config_loader


@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including system tag prefixes and tag priorities.
    
    Returns:
        JSON object with systemTagPrefixes list, tagPriorities mapping, and optional maptiler config
    """
    config = {
        'systemTagPrefixes': CONST_INTERNAL_TAGS,
        'tagPriorities': TAG_PRIORITIES
    }
    
    # Add MapTiler settings if configured (only expose if API key is set)
    config_loader = get_config_loader()
    maptiler_api_key = config_loader.get_maptiler_api_key()
    use_proxy = config_loader.get_bool('maptiler.proxy_tiles', False)
    
    if maptiler_api_key:
        maptiler_config = {
            'proxy_tiles': use_proxy
        }
        # Only expose API key if not using proxy (proxy uses server-side key)
        if not use_proxy:
            maptiler_config['apiKey'] = maptiler_api_key
        
        config['maptiler'] = maptiler_config
    
    return JsonResponse(config)
