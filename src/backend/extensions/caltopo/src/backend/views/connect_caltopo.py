"""
CalTopo authentication endpoints.
"""
import json
import traceback
from typing import Dict, Any

from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field, ConfigDict
from requests.exceptions import ReadTimeout, Timeout

from api.utils.responses import error_response, success_response
from api.validation.decorators import validate_payload
from extensions.caltopo.src.backend.models import CalTopoUser
from extensions.caltopo.src.backend.services.caltopo_api import get_caltopo_session, CalTopoTimeoutError
from extensions.caltopo.src.backend.utils.caltopo_helpers import perform_caltopo_call
from geo_lib.logging.console import get_tagged_logger
from website.auth_decorators import api_or_login_required_401

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
    
    POST /api/extensions/caltopo/connect/
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
    try:
        session = get_caltopo_session(request.user)
    except CalTopoUser.DoesNotExist:
        return error_response('Failed to create CalTopo session with provided credentials', code=400)

    # Verify credentials work - wrap getAccountData call to handle timeouts
    def verify_account_data():
        """Wrapper to convert ReadTimeout/Timeout to CalTopoTimeoutError."""
        try:
            return session.getAccountData()
        except (ReadTimeout, Timeout) as e:
            raise CalTopoTimeoutError("CalTopo API request timed out") from e

    try:
        account_data, error_resp = perform_caltopo_call(verify_account_data)
        if error_resp:
            # Delete credentials if verification fails
            caltopo_user.delete()
            return error_resp
    except Exception:
        # Delete credentials if verification fails (non-timeout exceptions)
        caltopo_user.delete()
        # Log detailed error internally
        _logger.warning(f'Failed to connect to Caltopo: {traceback.format_exc()}')
        # Return generic error message to user (don't expose API internals)
        return error_response('Invalid CalTopo credentials. Please verify your account ID, credential code, and credential key are correct.', code=400)

    # If we get here, verification succeeded (account_data may be None, but that's OK)

    return success_response({
        'msg': 'CalTopo credentials saved and verified successfully',
        'connected': True
    }, status=201 if created else 200)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_caltopo_status(request: HttpRequest) -> JsonResponse:
    """
    Check if the current user has connected CalTopo and validate credentials.
    
    GET /api/extensions/caltopo/status/
    
    Returns:
        - status: 'not_connected' (no credentials stored)
        - status: 'invalid' (credentials exist but are invalid)
        - status: 'timeout' (credentials exist but CalTopo API timed out)
        - status: 'connected' (credentials exist and are valid)
    """
    try:
        caltopo_user = CalTopoUser.objects.get(user=request.user)
    except CalTopoUser.DoesNotExist:
        return success_response({
            'connected': False,
            'status': 'not_connected'
        })

    # Validate credentials by attempting to get account data
    try:
        session = get_caltopo_session(request.user)
    except CalTopoUser.DoesNotExist:
        # This shouldn't happen since we already checked for caltopo_user above
        # But handle it gracefully just in case
        return success_response({
            'connected': False,
            'status': 'not_connected'
        })

    # Verify credentials work - check for timeout separately
    try:
        def verify_account_data():
            """Wrapper to convert ReadTimeout/Timeout to CalTopoTimeoutError."""
            try:
                return session.getAccountData()
            except (ReadTimeout, Timeout) as e:
                raise CalTopoTimeoutError("CalTopo API request timed out") from e

        account_data, error_resp = perform_caltopo_call(verify_account_data)
        if error_resp:
            # Check if it's a timeout error by examining the error response
            try:
                error_data = json.loads(error_resp.content)
                if error_data.get('details', {}).get('error_code') == 'CALTOPO_TIMEOUT':
                    return success_response({
                        'connected': False,
                        'status': 'timeout'
                    })
            except (json.JSONDecodeError, AttributeError, TypeError):
                pass

            # Credentials exist but validation failed - invalid credentials
            return success_response({
                'connected': False,
                'status': 'invalid'
            })
    except CalTopoTimeoutError:
        # Direct timeout exception (shouldn't happen with perform_caltopo_call, but just in case)
        return success_response({
            'connected': False,
            'status': 'timeout'
        })
    except Exception:
        # Other exceptions - invalid credentials
        _logger.warning(f'Error validating CalTopo credentials: {traceback.format_exc()}')
        return success_response({
            'connected': False,
            'status': 'invalid'
        })

    # Credentials exist and are valid
    return success_response({
        'connected': True,
        'status': 'connected'
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
def disconnect_caltopo(request: HttpRequest) -> JsonResponse:
    """
    Disconnect CalTopo by deleting the user's CalTopo credentials.
    
    POST /api/extensions/caltopo/disconnect/
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
