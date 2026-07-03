import json
import traceback
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from geo_lib.websocket.force_disconnect import WebSocketForceDisconnector
from users.models import ApiKey
from users.api_keys import create_user_api_key, validate_api_key

_logger = get_tagged_logger(__name__)


@api_or_login_required_401(allow_api_keys=False)  # API keys cannot manage other API keys
@require_http_methods(["GET"])
def list_api_keys(request):
    """List all API keys for the current user. GET /api/user/api-keys/"""
    """List all API keys for the current user."""
    try:
        api_keys = ApiKey.objects.filter(
            user=request.user,
            is_active=True
        ).order_by('-created_at')
        
        keys_data = []
        for key in api_keys:
            keys_data.append({
                'id': key.id,
                'name': key.name,
                'key_prefix': key.key_prefix,
                'created_at': key.created_at.isoformat(),
                'last_used_at': key.last_used_at.isoformat() if key.last_used_at else None,
            })
        
        return JsonResponse({
            'api_keys': keys_data
        })
    except Exception:
        _logger.error("Failed to list API keys:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to list API keys'}, status=500)


@api_or_login_required_401(allow_api_keys=False)  # API keys cannot create other API keys
@require_http_methods(["POST"])
def create_api_key(request):
    """Create a new API key for the current user. POST /api/user/api-keys/create/"""
    try:
        data = json.loads(request.body)
        name = data.get('name', '').strip()
        
        # Use default name if not provided
        if not name:
            name = 'Unnamed'
        
        # Create the API key
        api_key, raw_key = create_user_api_key(request.user, name)
        
        # Return the key data including the raw key (shown only once)
        return JsonResponse({
            'id': api_key.id,
            'name': api_key.name,
            'key_prefix': api_key.key_prefix,
            'created_at': api_key.created_at.isoformat(),
            'last_used_at': api_key.last_used_at.isoformat() if api_key.last_used_at else None,
            'raw_key': raw_key  # Only returned on creation
        }, status=201)
    except json.JSONDecodeError:
        return JsonResponse({
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception:
        _logger.error("Failed to create API key:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to create API key'}, status=500)


@api_or_login_required_401(allow_api_keys=False)  # API keys cannot delete other API keys
@require_http_methods(["DELETE"])
def delete_api_key(request, key_id):
    """Delete (deactivate) an API key for the current user."""
    try:
        key_id = int(key_id)
    except (ValueError, TypeError):
        return JsonResponse({
            'error': 'Invalid key ID'
        }, status=400)
    
    try:
        api_key = ApiKey.objects.get(id=key_id, user=request.user)
        
        # Soft delete by setting is_active to False
        api_key.is_active = False
        api_key.save(update_fields=['is_active'])

        # A WebSocket connected via this key's Authorization header stays authenticated until
        # it reconnects otherwise, since WS auth only runs once at connect() time.
        WebSocketForceDisconnector.disconnect_user(request.user.id, reason="api_key_deleted")

        return JsonResponse({
            'message': 'API key deleted successfully'
        })
    except ApiKey.DoesNotExist:
        return JsonResponse({
            'error': 'API key not found'
        }, status=404)
    except Exception:
        _logger.error("Failed to delete API key:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to delete API key'}, status=500)


# Generic message for auth failures so we don't leak whether the key was missing or invalid
VALIDATE_API_KEY_UNAUTHORIZED_MESSAGE = 'Invalid or missing credentials'


@api_or_login_required_401(allow_api_keys=True)
@require_http_methods(["POST"])
def validate_api_key_endpoint(request):
    """
    Validate an API key.
    Can be called by either a logged-in user or by a client presenting an API key.
    Returns 401 with a generic message for missing or invalid key (no information leakage).
    """
    try:
        auth_header = request.META.get('HTTP_AUTHORIZATION', '')
        if not auth_header.startswith('Bearer '):
            return JsonResponse({
                'error': VALIDATE_API_KEY_UNAUTHORIZED_MESSAGE
            }, status=401)

        token = auth_header[7:].strip()

        result = validate_api_key(token)

        if result is None:
            return JsonResponse({
                'error': VALIDATE_API_KEY_UNAUTHORIZED_MESSAGE
            }, status=401)

        user, api_key = result

        return JsonResponse({
            'valid': True,
            'key_name': api_key.name,
            'created_at': api_key.created_at.isoformat(),
            'last_used_at': api_key.last_used_at.isoformat() if api_key.last_used_at else None,
        })
    except Exception:
        _logger.error("Failed to validate API key:\n%s", traceback.format_exc())
        return JsonResponse({
            'valid': False,
            'error': 'Failed to validate API key'
        }, status=500)

