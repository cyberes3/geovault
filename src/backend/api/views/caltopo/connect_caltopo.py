"""
CalTopo authentication endpoints.
"""
import traceback
from typing import Dict, Any
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field, ConfigDict

from api.models import CalTopoUser
from api.utils.responses import error_response, success_response
from api.validation.feature_updates import validate_payload
from geo_lib.logging.console import get_tagged_logger
from geo_lib.services.caltopo_service import get_caltopo_session
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger('CalTopoAuth')


class CalTopoConnectPayload(BaseModel):
    """Pydantic model for CalTopo connection request."""
    model_config = ConfigDict(extra='forbid')

    account_id: str = Field(min_length=6, max_length=6, description="6-character CalTopo account ID")
    credential_id: str = Field(min_length=12, max_length=12, description="12-character CalTopo credential code")
    credential_key: str = Field(description="CalTopo credential key")


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CalTopoConnectPayload)
def connect_caltopo(request: HttpRequest, validated_data: Dict[str, Any]) -> JsonResponse:
    """
    Save or update CalTopo credentials for the current user.
    
    POST /api/caltopo/connect/
    Body: {
        "account_id": "abc123",
        "credential_id": "123456789012",
        "credential_key": "key..."
    }
    """
    account_id = validated_data['account_id']
    credential_id = validated_data['credential_id']
    credential_key = validated_data['credential_key']

    # Update or create CalTopoUser record
    caltopo_user, created = CalTopoUser.objects.update_or_create(
        user=request.user,
        defaults={
            'account_id': account_id,
            'credential_id': credential_id,
            'credential_key': credential_key
        }
    )

    # Test the connection by trying to get account data
    session = get_caltopo_session(request.user)
    if not session:
        return error_response('Failed to create CalTopo session with provided credentials', code=400)

    # Verify credentials work
    try:
        session.getAccountData()
    except Exception as e:
        # Delete credentials if verification fails
        caltopo_user.delete()
        # Log detailed error internally
        _logger.warning(f'Failed to connect to Caltopo: {traceback.format_exc()}')
        # Return generic error message to user (don't expose API internals)
        return error_response('Invalid CalTopo credentials. Please verify your account ID, credential code, and credential key are correct.', code=400)

    return success_response({
        'msg': 'CalTopo credentials saved and verified successfully',
        'connected': True
    }, status=201 if created else 200)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_caltopo_status(request: HttpRequest) -> JsonResponse:
    """
    Check if the current user has connected CalTopo.
    
    GET /api/caltopo/status/
    """
    try:
        CalTopoUser.objects.get(user=request.user)
        return success_response({
            'connected': True
        })
    except CalTopoUser.DoesNotExist:
        return success_response({
            'connected': False
        })


@api_or_login_required_401()
@require_http_methods(["POST"])
def disconnect_caltopo(request: HttpRequest) -> JsonResponse:
    """
    Disconnect CalTopo by deleting the user's CalTopo credentials.
    
    POST /api/caltopo/disconnect/
    """
    try:
        caltopo_user = CalTopoUser.objects.get(user=request.user)
        caltopo_user.delete()
        return success_response({
            'msg': 'CalTopo disconnected successfully',
            'connected': False
        })
    except CalTopoUser.DoesNotExist:
        return success_response({
            'msg': 'CalTopo was not connected',
            'connected': False
        })
