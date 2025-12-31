"""Collection sharing operations"""
import uuid

from django.db.models import F
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, Collection
from api.utils.authorization import get_object_or_404_for_user
from api.utils.format_encoding import create_bbox_response
from api.utils.responses import handle_404
from api.validation.feature_updates import validate_payload, CollectionSharePayload
from api.views.features.bbox_utils import _build_bbox_response, get_features_in_bbox, _validate_bbox_params
from api.views.sharing.utils import validate_share_id
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CollectionSharePayload)
@handle_404
def create_collection_share(request, validated_data):
    """
    Create a new share link for a collection.
    Always uses UUID4 for share_id.
    
    POST body:
    - collection_id: string (required) - The collection ID to share
    - include_tags: boolean (optional, default=False) - Whether to include tags in the shared features
    """
    collection_id_str = validated_data['collection_id']
    include_tags = validated_data.get('include_tags', False)

    # Convert collection_id to UUID (Pydantic already validated it's valid)
    collection_id = uuid.UUID(collection_id_str)

    # Verify collection exists and belongs to user
    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Generate UUID4 share_id
    share_id = str(uuid.uuid4())
    # Ensure uniqueness (very unlikely but check anyway)
    while TagShare.objects.filter(share_id=share_id).exists() or CollectionShare.objects.filter(share_id=share_id).exists():
        share_id = str(uuid.uuid4())

    # Get allow_downloads from validated data
    allow_downloads = validated_data.get('allow_downloads', False)

    # Create new share
    collection_share = CollectionShare.objects.create(
        share_id=share_id,
        collection=collection,
        user=request.user,
        include_tags=include_tags,
        allow_downloads=allow_downloads
    )

    # Build full URL
    base_url = request.build_absolute_uri('/').rstrip('/')
    share_url = f"{base_url}/#/mapshare?id={collection_share.share_id}"

    return JsonResponse({
        'share_id': collection_share.share_id,
        'url': share_url,
        'created_at': collection_share.created_at.isoformat()
    })


@require_http_methods(["GET"])
def get_public_collection_share(request, share_id):
    """
    Public endpoint to get features for a shared collection within a bounding box.
    No authentication required.
    Returns GeoJSON FeatureCollection of features in the shared collection in the specified bbox.
    Increments access_count on each successful access.

    Query parameters:
    - bbox: comma-separated bounding box (min_lon,min_lat,max_lon,max_lat) - required
    - zoom: zoom level (integer, 1-20) - optional, defaults to 10
    """
    # Validate share_id format (must be UUID4)
    if not validate_share_id(share_id):
        return JsonResponse({
            'error': 'Invalid share link',
            'code': 404
        }, status=404)

    # Get the share
    share = CollectionShare.objects.filter(share_id=share_id).select_related('collection').first()

    if not share:
        # Return same error message to prevent information disclosure
        return JsonResponse({
            'error': 'Invalid share link',
            'code': 404
        }, status=404)

    # Validate bbox and zoom parameters
    validation_result = _validate_bbox_params(request)
    if isinstance(validation_result, JsonResponse):
        return validation_result
    bbox, zoom_level = validation_result

    # Fetch data from database using collection query
    query_result = get_features_in_bbox(bbox, share.user.id, collection_id=share.collection.id, public_safe=True, include_tags=share.include_tags, allow_downloads=share.allow_downloads)
    features = query_result.features
    total_features_in_bbox = query_result.total_count
    fallback_used = query_result.fallback_used

    # Build response using helper function, including collection name for frontend display
    response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used, collection_name=share.collection.name)

    # Increment access count atomically only on successful response
    CollectionShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

    return create_bbox_response(response_data, request)
