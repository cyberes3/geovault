from typing import Optional

from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel

from geo_lib.processing.tagging.const_strings import CONST_INTERNAL_TAGS, TAG_PRIORITIES
from website.auth_decorators import api_or_login_required_401


class MapTilerClientConfig(BaseModel):
    proxy_tiles: bool
    apiKey: Optional[str] = None


class ServerConfigResponse(BaseModel):
    systemTagPrefixes: list[str]
    tagPriorities: dict[str, int]
    showAttribution: bool = False
    maptiler: Optional[MapTilerClientConfig] = None


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including system tag prefixes and tag priorities.
    
    Returns:
        JSON object with systemTagPrefixes list, tagPriorities mapping, and optional maptiler config
    """
    maptiler: Optional[MapTilerClientConfig] = None
    maptiler_api_key = settings.MAPTILER_API_KEY
    use_proxy = settings.MAPTILER_PROXY_TILES
    if maptiler_api_key:
        maptiler = MapTilerClientConfig(
            proxy_tiles=use_proxy,
            apiKey=None if use_proxy else maptiler_api_key,
        )

    body = ServerConfigResponse(
        systemTagPrefixes=list(CONST_INTERNAL_TAGS),
        tagPriorities=dict(TAG_PRIORITIES),
        showAttribution=bool(getattr(settings, 'TILESOURCES_SHOW_ATTRIBUTION', False)),
        maptiler=maptiler,
    )
    response = JsonResponse(body.model_dump(exclude_none=True))
    # Cache for 1 day (86400 seconds)
    response['Cache-Control'] = 'private, max-age=86400'
    return response
