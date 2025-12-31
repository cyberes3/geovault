import uuid

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import Collection
from api.utils.authorization import get_object_or_404_for_user
from api.utils.format_encoding import create_bbox_response
from api.utils.responses import handle_404
from api.views.features.bbox_utils import _validate_bbox_params, get_features_in_bbox, _build_bbox_response
from geo_lib.website.auth import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_geojson_data(request):
    """
    API endpoint to fetch GeoJSON data for a given bounding box.

    Query parameters:
    - bbox: comma-separated bounding box (min_lon,min_lat,max_lon,max_lat)
    - zoom: zoom level (integer, 1-20)
    - collection: optional collection ID to filter features by
    """
    # Validate bbox and zoom parameters
    validation_result = _validate_bbox_params(request)
    if isinstance(validation_result, JsonResponse):
        return validation_result
    bbox, zoom_level = validation_result

    # Get optional collection parameter
    collection_id = None
    collection_str = request.GET.get('collection')
    if collection_str:
        try:
            collection_id = uuid.UUID(collection_str)
            # Verify collection belongs to user
            get_object_or_404_for_user(Collection, request.user, id=collection_id)
        except (ValueError, TypeError):
            return JsonResponse({
                'error': 'Invalid collection ID. Expected UUID',
                'code': 400
            }, status=400)

    # Fetch data from database with optimized single query
    query_result = get_features_in_bbox(bbox, request.user.id, collection_id=collection_id)
    features = query_result.features
    total_features_in_bbox = query_result.total_count
    fallback_used = query_result.fallback_used

    # Build response using helper function
    response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used)

    return create_bbox_response(response_data, request)
