from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.const_strings import CONST_INTERNAL_TAGS


@require_http_methods(["GET"])
def get_config(request):
    """
    API endpoint to get server configuration, including protected tags.
    
    Returns:
        JSON object with protectedTags list
    """
    return JsonResponse({
        'protectedTags': CONST_INTERNAL_TAGS
    })
