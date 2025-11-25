import json
import traceback

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import UserSettings
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["GET"])
def get_user_settings(request):
    """
    Get all user settings for the current user.
    """
    try:
        # Get or create UserSettings for the user
        user_settings, created = UserSettings.objects.get_or_create(user=request.user)
        
        return JsonResponse({
            'success': True,
            'settings': user_settings.settings,
            'created_at': user_settings.created_at.isoformat(),
            'updated_at': user_settings.updated_at.isoformat()
        })
    
    except Exception:
        logger.error(f"Error getting user settings: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get user settings',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["PUT", "PATCH"])
def update_user_setting(request):
    """
    Update a single user setting.
    
    PUT/PATCH body:
    - key: string (required) - The setting key to update
    - value: any (required) - The setting value
    """
    try:
        data = json.loads(request.body)
        key = data.get('key')
        value = data.get('value')
        
        # Validate key is provided and is a string
        if not key:
            return JsonResponse({
                'success': False,
                'error': 'key is required',
                'code': 400
            }, status=400)
        
        if not isinstance(key, str):
            return JsonResponse({
                'success': False,
                'error': 'key must be a string',
                'code': 400
            }, status=400)
        
        # Get or create UserSettings for the user
        user_settings, created = UserSettings.objects.get_or_create(user=request.user)
        
        # Update the setting
        if user_settings.settings is None:
            user_settings.settings = {}
        
        user_settings.settings[key] = value
        user_settings.save()
        
        return JsonResponse({
            'success': True,
            'settings': user_settings.settings,
            'updated_at': user_settings.updated_at.isoformat()
        })
    
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error updating user setting: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update user setting',
            'code': 500
        }, status=500)

