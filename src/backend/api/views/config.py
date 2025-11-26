from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.const_strings import CONST_INTERNAL_TAGS


@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including system tag prefixes.
    
    Returns:
        JSON object with systemTagPrefixes list
    """
    return JsonResponse({
        'systemTagPrefixes': CONST_INTERNAL_TAGS
    })
