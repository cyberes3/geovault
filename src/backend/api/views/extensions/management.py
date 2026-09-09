from django.views.decorators.http import require_http_methods

from api.utils.responses import list_response
from website.extensions.registry import get_extension_registry


@require_http_methods(["GET"])
def list_extensions(request):
    """
    Returns enabled extensions and their frontend entry points.
    Public so guest share routes can register without a session.
    """
    registry = get_extension_registry()
    items = registry.get_loaded_extensions()
    return list_response(items, page=1, page_size=max(len(items), 1), total_items=len(items))
