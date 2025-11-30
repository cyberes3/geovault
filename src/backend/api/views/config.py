from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.const_strings import CONST_INTERNAL_TAGS, TAG_PRIORITIES


@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including system tag prefixes and tag priorities.
    
    Returns:
        JSON object with systemTagPrefixes list and tagPriorities mapping
    """
    return JsonResponse({
        'systemTagPrefixes': CONST_INTERNAL_TAGS,
        'tagPriorities': TAG_PRIORITIES
    })
