"""
Utility functions for CalTopo integration.
"""
from typing import Optional, Tuple, Callable, Any

from django.http import HttpRequest, JsonResponse

from api.models import CalTopoUser
from api.utils.responses import error_response
from geo_lib.services.caltopo_service import CalTopoTimeoutError


def require_caltopo_connection(request: HttpRequest) -> Tuple[Optional[CalTopoUser], Optional[JsonResponse]]:
    """
    Check if the user has CalTopo connected.
    
    Args:
        request: Django HttpRequest object
        
    Returns:
        Tuple of (caltopo_user, error_response):
        - If connected: (CalTopoUser instance, None)
        - If not connected: (None, error JsonResponse)
    """
    try:
        caltopo_user = CalTopoUser.objects.get(user=request.user)
        return caltopo_user, None
    except CalTopoUser.DoesNotExist:
        return None, error_response(
            'CalTopo not connected. Please connect your CalTopo account first.',
            code=400
        )


def handle_caltopo_call(
        caltopo_func: Callable,
        *args,
        **kwargs
) -> Tuple[Optional[Any], Optional[JsonResponse]]:
    """
    Execute a CalTopo service function and handle timeout errors.
    
    This wrapper catches CalTopoTimeoutError and returns a standardized error response,
    eliminating the need to repeat try/except blocks in every view.
    
    Args:
        caltopo_func: CalTopo service function to call (e.g., list_maps, get_map_features)
        *args: Positional arguments to pass to the function
        **kwargs: Keyword arguments to pass to the function
        
    Returns:
        Tuple of (result, error_response):
        - If successful: (function result, None)
        - If timeout: (None, error JsonResponse with CALTOPO_TIMEOUT code)
        - If other exception: exception is re-raised (not caught)
        
    Example:
        maps, error_resp = handle_caltopo_call(list_maps, request.user)
        if error_resp:
            return error_resp
        return success_response({'maps': maps})
    """
    try:
        result = caltopo_func(*args, **kwargs)
        return result, None
    except CalTopoTimeoutError:
        return None, error_response(
            "CalTopo request timed out.",
            code=504,
            details={"error_code": "CALTOPO_TIMEOUT"}
        )
