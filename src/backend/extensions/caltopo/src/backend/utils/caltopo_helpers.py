"""
Utility functions for CalTopo integration.
"""
from typing import Optional, Tuple, Callable, Any

from django.http import HttpRequest, JsonResponse

from api.utils.responses import error_response
from extensions.caltopo.src.backend.models import CalTopoUser
from extensions.caltopo.src.backend.services.caltopo_api import CalTopoTimeoutError


def require_caltopo_connection(request: HttpRequest) -> Tuple[Optional[CalTopoUser], Optional[JsonResponse]]:
    """
    Check if the user has CalTopo connected.
    """
    try:
        caltopo_user = CalTopoUser.objects.get(user=request.user)
        return caltopo_user, None
    except CalTopoUser.DoesNotExist:
        return None, error_response(
            'CalTopo not connected. Please connect your CalTopo account first.',
            code=400
        )


def perform_caltopo_call(
        caltopo_func: Callable,
        *args,
        **kwargs
) -> Tuple[Optional[Any], Optional[JsonResponse]]:
    """
    Execute a CalTopo API function and handle timeout errors.
    
    This wrapper catches CalTopoTimeoutError and returns a standardized error response,
    eliminating the need to repeat try/except blocks in every view.
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

VALID_CALTOPO_FEATURE_CLASSES = {
        'Shape', 'Marker', 'AppTrack', 'LiveTrack', 'Folder',
        'MapMediaObject', 'OperationalPeriod', 'Assignment',
        'Clue', 'Resource', 'SmsLocationRequest'
    }

def is_valid_caltopo_feature_class(feature_class: str) -> bool:
    """
    Check if a feature class is valid for import.
    """
    return feature_class in VALID_CALTOPO_FEATURE_CLASSES
