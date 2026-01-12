from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from website.extension_loader import _registry

@require_http_methods(["GET"])
def list_extensions(request):
    """
    Returns a list of all active extensions and their frontend entry points.
    """
    if _registry:
        extensions = _registry.get_active_extensions()
    else:
        extensions = []
        
    return JsonResponse(extensions, safe=False)
