"""
Utility functions for CalTopo integration.
"""
from typing import Optional, Tuple
from django.http import HttpRequest, JsonResponse

from api.models import CalTopoUser
from api.utils.responses import error_response


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

