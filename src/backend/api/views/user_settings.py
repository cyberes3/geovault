import json
import traceback

from django.http import JsonResponse, HttpResponse
from django.views.decorators.http import require_http_methods

from api.models import UserSettings, FeatureStore
from api.validation.user_settings import validate_settings
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import api_or_login_required_401

logger = get_access_logger()


def deep_merge(base: dict, update: dict) -> dict:
    """
    Deep merge two dictionaries efficiently without deepcopy.
    
    Args:
        base: Base dictionary to merge into
        update: Dictionary with updates to merge
        
    Returns:
        New dictionary with merged values
    """
    result = base.copy()  # Shallow copy of top level
    
    for key, value in update.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            # Recursively merge nested dicts
            result[key] = deep_merge(result[key], value)
        else:
            # Direct assignment for non-dict values or new keys
            result[key] = value
    
    return result


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_user_settings(request):
    """
    Get all user settings for the current user.
    Settings are returned as-is from database for performance.
    Validation only occurs on write operations.
    """
    try:
        # Get or create UserSettings for the user
        user_settings, created = UserSettings.objects.get_or_create(user=request.user)
        
        # Return raw settings from database (no validation on read for performance)
        settings_dict = user_settings.settings or {}

        # Normalize hidden_features to a list of strings
        raw_hidden = getattr(user_settings, 'hidden_features', []) or []
        if not isinstance(raw_hidden, list):
            hidden_feature_ids = []
        else:
            hidden_feature_ids = [str(fid) for fid in raw_hidden if isinstance(fid, (str, int))]

        # Fetch feature names for hidden features to avoid frontend making individual API calls
        hidden_features_with_names = _get_hidden_features_with_names(request.user, hidden_feature_ids)

        return JsonResponse({
            'settings': settings_dict,
            'hidden_features': hidden_features_with_names,
        })
    
    except Exception:
        logger.error(f"Error getting user settings: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to get user settings',
            'code': 500
        }, status=500)


@api_or_login_required_401()
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
        
        # Update the settings (do not modify hidden_features here)
        user_settings.settings = validated_settings
        user_settings.save(update_fields=['settings'])
        
        # Skip fetching hidden_features since this endpoint only updates settings
        # Hidden features are managed by separate endpoints and don't change here
        return JsonResponse({
            'settings': validated_settings,
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


def _normalize_hidden_features(hidden_list):
    """
    Ensure hidden_features is always a list of string IDs.
    """
    if not isinstance(hidden_list, list):
        return []
    return [str(fid) for fid in hidden_list if isinstance(fid, (str, int))]


def _get_hidden_features_with_names(user, hidden_feature_ids):
    """
    Fetch feature names and geometry types for a list of hidden feature IDs.
    Returns a list of dicts with 'id', 'name', and 'geometry_type' keys.
    """
    hidden_features_with_names = []
    if hidden_feature_ids:
        # Query all hidden features at once
        features = FeatureStore.objects.filter(
            user=user,
            id__in=[int(fid) for fid in hidden_feature_ids if fid.isdigit()]
        ).only('id', 'geojson')
        
        # Build a map of id -> {name, geometry_type}
        feature_info = {}
        for feature in features:
            feature_id = str(feature.id)
            feature_name = None
            geometry_type = None
            
            if feature.geojson:
                if 'properties' in feature.geojson:
                    feature_name = feature.geojson['properties'].get('name')
                if 'geometry' in feature.geojson and feature.geojson['geometry']:
                    geometry_type = feature.geojson['geometry'].get('type')
            
            feature_info[feature_id] = {
                'name': feature_name,
                'geometry_type': geometry_type
            }
        
        # Build the list with names and geometry types included
        for fid in hidden_feature_ids:
            info = feature_info.get(fid, {})
            hidden_features_with_names.append({
                'id': fid,
                'name': info.get('name'),
                'geometry_type': info.get('geometry_type')
            })
    
    return hidden_features_with_names


@api_or_login_required_401()
@require_http_methods(["POST"])
def clear_hidden_features(request):
    """
    Clear all hidden feature IDs for the current user.
    """
    try:
        user_settings, _ = UserSettings.objects.get_or_create(user=request.user)
        current_hidden = _normalize_hidden_features(getattr(user_settings, "hidden_features", []))
        
        # Skip database write if already empty
        if not current_hidden:
            return HttpResponse(status=204)
        
        user_settings.hidden_features = []
        user_settings.save(update_fields=["hidden_features"])

        # No body needed; frontend maintains its own cache of hidden features.
        # Return 204 No Content to indicate success.
        return HttpResponse(status=204)

    except Exception:
        logger.error(f"Error clearing hidden features: {traceback.format_exc()}")
        return JsonResponse({
            "error": "Failed to clear hidden features",
            "code": 500,
        }, status=500)


@api_or_login_required_401()
@require_http_methods(["POST"])
def bulk_update_hidden_features(request):
    """
    Bulk update hidden features list by adding and/or removing multiple feature IDs.
    Body: {
        "add": [list of feature IDs to add],
        "remove": [list of feature IDs to remove]
    }
    """
    try:
        data = json.loads(request.body or "{}")
        add_ids = data.get("add", [])
        remove_ids = data.get("remove", [])

        if not isinstance(add_ids, list):
            add_ids = []
        if not isinstance(remove_ids, list):
            remove_ids = []

        # Normalize to string IDs and convert to sets for O(1) lookups
        add_ids_set = {str(fid) for fid in add_ids if fid and isinstance(fid, (str, int))}
        remove_ids_set = {str(fid) for fid in remove_ids if fid and isinstance(fid, (str, int))}

        # Early return if nothing to do
        if not add_ids_set and not remove_ids_set:
            return HttpResponse(status=204)

        user_settings, _ = UserSettings.objects.get_or_create(user=request.user)
        current_hidden = _normalize_hidden_features(getattr(user_settings, "hidden_features", []))

        # Convert to set for O(1) operations
        current_hidden_set = set(current_hidden)

        # Remove IDs first (set difference)
        if remove_ids_set:
            current_hidden_set -= remove_ids_set

        # Add new IDs (set union)
        if add_ids_set:
            current_hidden_set |= add_ids_set

        # Convert back to list (preserve order by keeping existing order, then appending new ones)
        # This maintains backward compatibility with list-based storage
        result_list = [fid for fid in current_hidden if fid in current_hidden_set]
        # Add any new IDs that weren't in the original list
        for fid in add_ids_set:
            if fid not in result_list:
                result_list.append(fid)

        user_settings.hidden_features = result_list
        user_settings.save(update_fields=["hidden_features"])

        # No response body needed; frontend uses an optimistic local cache.
        # Return 204 No Content to indicate success.
        return HttpResponse(status=204)

    except json.JSONDecodeError:
        return JsonResponse({
            "error": "Invalid JSON in request body",
            "code": 400,
        }, status=400)
    except Exception:
        logger.error(f"Error bulk updating hidden features: {traceback.format_exc()}")
        return JsonResponse({
            "error": "Failed to bulk update hidden features",
            "code": 500,
        }, status=500)

