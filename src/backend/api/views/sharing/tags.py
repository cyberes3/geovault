"""Tag sharing operations"""
import uuid

from django.db.models import F, Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, FeatureShare, FeatureStore
from api.utils.format_encoding import create_bbox_response
from api.utils.responses import error_response, not_found_response
from api.validation.feature_updates import validate_payload, TagSharePayload
from api.views.features.bbox_utils import _build_bbox_response, _validate_bbox_params, get_features_in_bbox
from api.views.sharing.utils import validate_share_id, build_share_url
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(TagSharePayload)
def create_share(request, validated_data):
    """
    Create a new share link for a tag.
    Always uses UUID4 for share_id (no customization allowed).
    
    POST body:
    - tag: string (required) - The tag to share
    """
    tag = validated_data['tag'].strip()

    # Validate tag length
    tag_max_length = get_required_setting('TAG_MAX_LENGTH')
    if len(tag) > tag_max_length:
        return error_response(f'Tag name exceeds maximum length of {tag_max_length} characters', code=400)

    # Verify that the tag exists in the user's features (check both user tags and system tags)
    tag_exists = FeatureStore.objects.filter(
        user=request.user
    ).filter(
        Q(geojson__properties__tags__contains=[tag]) |
        Q(geojson__properties__system_tags__contains=[tag])
    ).exists()

    if not tag_exists:
        return not_found_response('Tag not found in your data')

    # Generate UUID4 share_id
    share_id = str(uuid.uuid4())
    # Ensure uniqueness (very unlikely but check anyway)
    while TagShare.objects.filter(share_id=share_id).exists() or CollectionShare.objects.filter(share_id=share_id).exists():
        share_id = str(uuid.uuid4())

    # Get allow_downloads from validated data
    allow_downloads = validated_data.get('allow_downloads', False)

    # Create new share (always use UUID4)
    tag_share = TagShare.objects.create(
        share_id=share_id,
        tag=tag,
        user=request.user,
        allow_downloads=allow_downloads
    )

    # Build full URL using configured site domain
    share_url = build_share_url(request, tag_share.share_id)

    return JsonResponse({
        'share_id': tag_share.share_id,
        'url': share_url,
        'created_at': tag_share.created_at.isoformat()
    })


@require_http_methods(["GET"])
def get_public_share_info(request, share_id):
    """
    Public endpoint to get information about any share (tag, collection, or feature).
    No authentication required.
    Returns share type and basic info for display purposes without revealing data.
    Does not increment access_count.
    """
    # Validate share_id format (must be UUID4)
    if not validate_share_id(share_id):
        return JsonResponse({
            'error': 'Invalid share link',
            'code': 404
        }, status=404)

    # Try to find the share in any of the three tables
    tag_share = TagShare.objects.filter(share_id=share_id).first()
    if tag_share:
        return JsonResponse({
            'share_type': 'tag',
            'tag': tag_share.tag,
            'created_at': tag_share.created_at.isoformat(),
            'allow_downloads': tag_share.allow_downloads
        })

    collection_share = CollectionShare.objects.filter(share_id=share_id).select_related('collection').first()
    if collection_share:
        return JsonResponse({
            'share_type': 'collection',
            'collection_name': collection_share.collection.name,
            'collection_id': str(collection_share.collection.id),
            'created_at': collection_share.created_at.isoformat(),
            'include_tags': collection_share.include_tags,
            'allow_downloads': collection_share.allow_downloads
        })

    feature_share = FeatureShare.objects.filter(share_id=share_id).select_related('feature').first()
    if feature_share:
        # Get feature name from geojson properties
        feature_name = feature_share.feature.geojson.get('properties', {}).get('name', 'Unnamed Feature')
        return JsonResponse({
            'share_type': 'feature',
            'feature_name': feature_name,
            'feature_id': feature_share.feature.id,
            'created_at': feature_share.created_at.isoformat(),
            'allow_downloads': feature_share.allow_downloads
        })

    # Share not found - return same error message to prevent information disclosure
    return JsonResponse({
        'error': 'Invalid share link',
        'code': 404
    }, status=404)


@require_http_methods(["GET"])
def get_public_share(request, share_id):
    """
    Public endpoint to get features for a shared tag within a bounding box.
    No authentication required.
    Returns GeoJSON FeatureCollection of features with the shared tag in the specified bbox.
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
    share = TagShare.objects.filter(share_id=share_id).first()

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

    # Fetch data from database
    query_result = get_features_in_bbox(bbox, share.user.id, tags=[share.tag], public_safe=True, allow_downloads=share.allow_downloads)
    features = query_result.features
    total_features_in_bbox = query_result.total_count
    fallback_used = query_result.fallback_used

    # Build response using helper function
    response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used)

    # Increment access count atomically only on successful response
    TagShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

    return create_bbox_response(response_data, request)
