import json
import traceback
from copy import deepcopy

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import UserSettings
from api.validation.user_settings import validate_settings
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


def deep_merge(base: dict, update: dict) -> dict:
    """
    Deep merge two dictionaries.
    
    Args:
        base: Base dictionary to merge into
        update: Dictionary with updates to merge
        
    Returns:
        New dictionary with merged values
    """
    result = deepcopy(base)
    
    for key, value in update.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    
    return result


@login_required_401
@require_http_methods(["GET"])
def get_user_settings(request):
    """
    Get all user settings for the current user.
    Settings are validated before being returned to ensure data integrity.
    """
    try:
        # Get or create UserSettings for the user
        user_settings, created = UserSettings.objects.get_or_create(user=request.user)
        
        # Validate all settings when reading from database
        settings_dict = user_settings.settings or {}
        is_valid, error_message, error_details, validated_settings = validate_settings(settings_dict)
        
        if not is_valid:
            logger.warning(
                f"Invalid settings for user {request.user.id}: {error_message}. Returning empty settings."
            )
            validated_settings = {}
        
        return JsonResponse({
            'settings': validated_settings or {}
        })
    
    except Exception:
        logger.error(f"Error getting user settings: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get user settings',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["PUT", "PATCH"])
def update_user_setting(request):
    """
    Update user settings with a partial nested JSON object.
    
    PUT/PATCH body: Partial nested JSON object (e.g., {"map": {"elevation_profile_source": "api"}})
    The provided settings will be deep merged with existing settings.
    """
    try:
        data = json.loads(request.body)
        
        # Validate that data is a dictionary
        if not isinstance(data, dict):
            return JsonResponse({
                'error': 'Request body must be a JSON object',
                'code': 400
            }, status=400)
        
        # Get or create UserSettings for the user
        user_settings, created = UserSettings.objects.get_or_create(user=request.user)
        
        # Get existing settings
        existing_settings = user_settings.settings or {}
        
        # Deep merge incoming settings with existing settings
        merged_settings = deep_merge(existing_settings, data)
        
        # Validate the merged settings
        is_valid, error_message, error_details, validated_settings = validate_settings(merged_settings)
        
        if not is_valid:
            response_data = {
                'error': error_message,
                'code': 400
            }
            # Add detailed error information if available
            if error_details:
                response_data['errors'] = error_details
            logger.warning(f"Setting validation failed for user {request.user.id}: {error_message}")
            return JsonResponse(response_data, status=400)
        
        # Update the settings
        user_settings.settings = validated_settings
        user_settings.save()
        
        return JsonResponse({
            'settings': validated_settings
        })
    
    except json.JSONDecodeError:
        return JsonResponse({
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error updating user setting: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to update user setting',
            'code': 500
        }, status=500)

