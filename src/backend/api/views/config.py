from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS, TAG_PRIORITIES
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
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
    maptiler_api_key = settings.MAPTILER_API_KEY
    use_proxy = settings.MAPTILER_PROXY_TILES

    if maptiler_api_key:
        maptiler_config = {
            'proxy_tiles': use_proxy
        }
        # Only expose API key if not using proxy (proxy uses server-side key)
        if not use_proxy:
            maptiler_config['apiKey'] = maptiler_api_key

        config['maptiler'] = maptiler_config

    response = JsonResponse(config)
    # Cache for 1 day (86400 seconds)
    response['Cache-Control'] = 'private, max-age=86400'
    return response
