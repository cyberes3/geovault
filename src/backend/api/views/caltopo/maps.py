"""
CalTopo maps and features endpoints.
"""
from typing import Dict, Any
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import CalTopoUser, ImportQueue, FeatureStore
from api.utils.rate_limit import caltopo_rate_limit
from api.utils.responses import error_response, success_response, not_found_response
from api.utils.caltopo_helpers import require_caltopo_connection, handle_caltopo_call
from geo_lib.services.caltopo_service import list_maps, get_map_features
from geo_lib.website.auth import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limit('list_maps')
def list_caltopo_maps(request: HttpRequest) -> JsonResponse:
    """
    List all available CalTopo maps for the current user.
    
    GET /api/caltopo/maps/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp
    
    maps, error_resp = handle_caltopo_call(list_maps, request.user)
    if error_resp:
        return error_resp
    
    return success_response({
        'maps': maps,
        'count': len(maps)
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limit('get_map_features')
def get_caltopo_map_features(request: HttpRequest, map_id: str) -> JsonResponse:
    """
    Get all features from a specific CalTopo map.
    
    GET /api/caltopo/maps/{map_id}/features/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp
    
    features, error_resp = handle_caltopo_call(get_map_features, request.user, map_id)
    if error_resp:
        return error_resp
    
    if features is None:
        return not_found_response(f'Map {map_id} not found or access denied')
    
    # Check if this map is already in the import queue
    filename = f'caltopo_map_{map_id}.geojson'
    is_in_queue = ImportQueue.objects.filter(
        user=request.user,
        original_filename=filename,
        imported=False,
        unparsable=False
    ).exists()
    
    # Check which features have already been imported
    # We need to verify that the feature actually exists in FeatureStore,
    # not just that it's in the mapping (in case user deleted it)
    
    # Get all feature IDs that are mapped for this map
    mapped_feature_ids = {}
    if map_id in caltopo_user.imported_features:
        mapped_feature_ids = caltopo_user.imported_features[map_id]
    
    # Verify which mapped features actually exist in FeatureStore
    existing_feature_store_ids = set()
    if mapped_feature_ids:
        existing_feature_store_ids = set(
            FeatureStore.objects.filter(
                user=request.user,
                id__in=mapped_feature_ids.values()
            ).values_list('id', flat=True)
        )
    
    # Build a set of CalTopo feature IDs that are actually imported (exist in FeatureStore)
    # Also clean up stale mappings for features that no longer exist
    imported_caltopo_feature_ids = set()
    needs_cleanup = False
    cleaned_mapping = mapped_feature_ids.copy()
    
    for caltopo_feature_id, feature_store_id in mapped_feature_ids.items():
        if feature_store_id in existing_feature_store_ids:
            imported_caltopo_feature_ids.add(caltopo_feature_id)
        else:
            # Feature was deleted, remove from mapping
            cleaned_mapping.pop(caltopo_feature_id, None)
            needs_cleanup = True
    
    # Update the mapping if we found stale entries
    if needs_cleanup:
        caltopo_user.imported_features[map_id] = cleaned_mapping
        caltopo_user.save(update_fields=['imported_features'])
    
    # Add import status to each feature
    features_with_status = []
    for feature in features:
        feature_id = feature.get('id', '')
        is_imported = feature_id in imported_caltopo_feature_ids
        feature_with_status = feature.copy()
        feature_with_status['is_imported'] = is_imported
        features_with_status.append(feature_with_status)
    
    return success_response({
        'map_id': map_id,
        'features': features_with_status,
        'count': len(features_with_status),
        'is_in_queue': is_in_queue
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@caltopo_rate_limit('get_map_details')
def get_caltopo_map_details(request: HttpRequest, map_id: str) -> JsonResponse:
    """
    Get details about a specific CalTopo map.
    
    GET /api/caltopo/maps/{map_id}/
    """
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp
    
    maps, error_resp = handle_caltopo_call(list_maps, request.user)
    if error_resp:
        return error_resp
    
    # Find the map
    map_details = None
    for map_item in maps:
        if map_item.get('id') == map_id:
            map_details = map_item
            break
    
    if not map_details:
        return not_found_response(f'Map {map_id} not found')
    
    return success_response({
        'map': map_details
    })

