from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from website.extensions.extension_loader import get_extension_registry

@require_http_methods(["GET"])
def list_extensions(request):
    """
    Returns a list of all active extensions and their frontend entry points.
    Public (no auth required) so that extension routes (e.g. public share) can be
    registered when an unauthenticated user opens a share link.
    """
    registry = get_extension_registry()
    extensions = registry.get_loaded_extensions()
        
    return JsonResponse(extensions, safe=False)
