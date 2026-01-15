from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from geo_lib.website.auth import api_or_login_required_401
from website.extensions.extension_loader import get_extension_registry

@api_or_login_required_401()
@require_http_methods(["GET"])
def list_extensions(request):
    """
    Returns a list of all active extensions and their frontend entry points.
    Requires authentication.
    """
    registry = get_extension_registry()
    extensions = registry.get_loaded_extensions()
        
    return JsonResponse(extensions, safe=False)
