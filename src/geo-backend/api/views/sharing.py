import re
import traceback
import uuid
from typing import Tuple

from django.db.models import F
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, Collection, FeatureStore
from api.views.bbox_query import BboxQueryResult, _build_bbox_response, _get_features_in_bbox, _validate_bbox_params
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


def _get_public_share_features_in_bbox(bbox: Tuple[float, float, float, float], user_id: int, tag: str, zoom_level: int) -> BboxQueryResult:
    """
    Get features within bounding box that have a specific tag.
    Handles world-wide extents that cross the International Date Line.
    Returns both the features and the total count in a single optimized operation.
    Features are returned with public-safe properties (excludes _id and tags).
    
    This is a wrapper around the consolidated _get_features_in_bbox() function.
    """
    return _get_features_in_bbox(bbox, user_id, zoom_level, tag=tag, public_safe=True)


def _validate_share_id(share_id: str) -> bool:
    """
    Validate share_id format.
    Must be a valid UUID4 format (36 characters with hyphens).
    """
    if not share_id or not isinstance(share_id, str):
        return False
    # UUID4 format: 8-4-4-4-12 hexadecimal characters
    uuid_pattern = r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    return bool(re.match(uuid_pattern, share_id.lower()))


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def create_share(request):
    """
    Create a new share link for a tag.
    Always uses UUID4 for share_id (no customization allowed).
    
    POST body:
    - tag: string (required) - The tag to share
    """
    try:
        import json
        data = json.loads(request.body)
        tag = data.get('tag', '').strip()

        if not tag:
            return JsonResponse({
                'success': False,
                'error': 'tag parameter is required',
                'code': 400
            }, status=400)

        # Validate tag length
        if len(tag) > 255:
            return JsonResponse({
                'success': False,
                'error': 'Tag name exceeds maximum length of 255 characters',
                'code': 400
            }, status=400)

        # Verify that the tag exists in the user's features
        if not FeatureStore.objects.filter(user=request.user, geojson__properties__tags__contains=[tag]).exists():
            return JsonResponse({
                'success': False,
                'error': 'Tag not found in your data',
                'code': 404
            }, status=404)

        # Generate UUID4 share_id
        share_id = str(uuid.uuid4())
        # Ensure uniqueness (very unlikely but check anyway)
        while TagShare.objects.filter(share_id=share_id).exists() or CollectionShare.objects.filter(share_id=share_id).exists():
            share_id = str(uuid.uuid4())

        # Create new share (always use UUID4, no use_tag_as_id option)
        tag_share = TagShare.objects.create(
            share_id=share_id,
            tag=tag,
            user=request.user,
            use_tag_as_id=False
        )

        # Build full URL
        base_url = request.build_absolute_uri('/').rstrip('/')
        share_url = f"{base_url}/#/mapshare?id={tag_share.share_id}"

        return JsonResponse({
            'success': True,
            'share_id': tag_share.share_id,
            'url': share_url,
            'created_at': tag_share.created_at.isoformat()
        })

    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error creating share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to create share',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def list_shares(request):
    """
    List all shares for the current user (both tag and collection shares).
    Returns list of shares with share_id, share_type, tag/collection info, created_at, access_count, url
    Sorted by created_at descending (newest first).
    """
    try:
        base_url = request.build_absolute_uri('/').rstrip('/')
        shares_list = []
        
        # Get tag shares
        tag_shares = TagShare.objects.filter(user=request.user)
        for share in tag_shares:
            share_url = f"{base_url}/#/mapshare?id={share.share_id}"
            shares_list.append({
                'share_id': share.share_id,
                'share_type': 'tag',
                'tag': share.tag,
                'created_at': share.created_at.isoformat(),
                'access_count': share.access_count,
                'url': share_url,
                'use_tag_as_id': share.use_tag_as_id
            })
        
        # Get collection shares
        collection_shares = CollectionShare.objects.filter(user=request.user).select_related('collection')
        for share in collection_shares:
            share_url = f"{base_url}/#/mapshare?id={share.share_id}"
            shares_list.append({
                'share_id': share.share_id,
                'share_type': 'collection',
                'collection_id': str(share.collection.id),
                'collection_name': share.collection.name,
                'created_at': share.created_at.isoformat(),
                'access_count': share.access_count,
                'url': share_url,
                'include_tags': share.include_tags
            })
        
        # Sort by created_at descending (newest first)
        shares_list.sort(key=lambda x: x['created_at'], reverse=True)

        return JsonResponse({
            'success': True,
            'shares': shares_list
        })

    except Exception:
        logger.error(f"Error listing shares: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to list shares',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["DELETE"])
def delete_share(request, share_id):
    """
    Delete a share by share_id (works for both tag and collection shares).
    Only allows deletion of shares owned by the current user.
    The server automatically determines whether it's a tag share or collection share.
    """
    try:
        # Validate share_id format
        if not _validate_share_id(share_id):
            return JsonResponse({
                'success': False,
                'error': 'Invalid share ID',
                'code': 400
            }, status=400)

        # Try to find as tag share first
        share = TagShare.objects.filter(share_id=share_id, user=request.user).first()
        
        # If not found, try collection share
        if not share:
            share = CollectionShare.objects.filter(share_id=share_id, user=request.user).first()
        
        if not share:
            # Return generic error to prevent information disclosure
            return JsonResponse({
                'success': False,
                'error': 'Share not found or access denied',
                'code': 404
            }, status=404)

        share.delete()

        return JsonResponse({
            'success': True,
            'message': 'Share deleted successfully'
        })

    except Exception:
        logger.error(f"Error deleting share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to delete share',
            'code': 500
        }, status=500)


@require_http_methods(["GET"])
def get_public_share_info(request, share_id):
    """
    Public endpoint to get basic metadata about a share link.
    No authentication required.
    Returns share type (tag or collection) and relevant metadata.
    
    Returns JSON with:
    - share_type: 'tag' or 'collection'
    - For tag shares: tag (tag name)
    - For collection shares: collection_name, collection_id, include_tags
    """
    try:
        # Validate share_id format (must be UUID4)
        if not _validate_share_id(share_id):
            return JsonResponse({
                'success': False,
                'error': 'Invalid share link',
                'code': 404
            }, status=404)

        # Try to find as tag share first
        tag_share = TagShare.objects.filter(share_id=share_id).first()
        
        if tag_share:
            return JsonResponse({
                'success': True,
                'share_type': 'tag',
                'tag': tag_share.tag
            })
        
        # If not found, try collection share
        collection_share = CollectionShare.objects.filter(share_id=share_id).select_related('collection').first()
        
        if collection_share:
            return JsonResponse({
                'success': True,
                'share_type': 'collection',
                'collection_name': collection_share.collection.name,
                'collection_id': str(collection_share.collection.id),
                'include_tags': collection_share.include_tags
            })
        
        # Share not found
        return JsonResponse({
            'success': False,
            'error': 'Invalid share link',
            'code': 404
        }, status=404)

    except Exception:
        logger.error(f"Error getting public share info: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get share info',
            'code': 500
        }, status=500)


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
    try:
        # Validate share_id format (must be UUID4)
        if not _validate_share_id(share_id):
            return JsonResponse({
                'success': False,
                'error': 'Invalid share link',
                'code': 404
            }, status=404)

        # Get the share
        share = TagShare.objects.filter(share_id=share_id).first()
        
        if not share:
            # Return same error message to prevent information disclosure
            return JsonResponse({
                'success': False,
                'error': 'Invalid share link',
                'code': 404
            }, status=404)

        # Validate bbox and zoom parameters
        validation_result = _validate_bbox_params(request)
        if isinstance(validation_result, JsonResponse):
            return validation_result
        bbox, zoom_level = validation_result

        # Fetch data from database with optimized single query
        query_result = _get_public_share_features_in_bbox(bbox, share.user.id, share.tag, zoom_level)
        features = query_result.features
        total_features_in_bbox = query_result.total_count
        fallback_used = query_result.fallback_used

        # Build response using helper function, including tag for frontend display
        response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used, tag=share.tag)

        # Increment access count atomically only on successful response
        TagShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting public share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get shared features',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def create_collection_share(request):
    """
    Create a new share link for a collection.
    Always uses UUID4 for share_id.
    
    POST body:
    - collection_id: string (required) - The collection ID to share
    - include_tags: boolean (optional, default=False) - Whether to include tags in the shared features
    """
    try:
        import json
        data = json.loads(request.body)
        collection_id_str = data.get('collection_id', '').strip()
        include_tags = data.get('include_tags', False)

        if not collection_id_str:
            return JsonResponse({
                'success': False,
                'error': 'collection_id parameter is required',
                'code': 400
            }, status=400)

        # Validate and convert collection_id to UUID
        try:
            collection_id = uuid.UUID(collection_id_str)
        except ValueError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid collection ID format',
                'code': 400
            }, status=400)

        # Verify collection exists and belongs to user
        try:
            collection = Collection.objects.get(id=collection_id, user=request.user)
        except Collection.DoesNotExist:
            return JsonResponse({
                'success': False,
                'error': 'Collection not found or access denied',
                'code': 404
            }, status=404)

        # Generate UUID4 share_id
        share_id = str(uuid.uuid4())
        # Ensure uniqueness (very unlikely but check anyway)
        while TagShare.objects.filter(share_id=share_id).exists() or CollectionShare.objects.filter(share_id=share_id).exists():
            share_id = str(uuid.uuid4())

        # Create new share
        collection_share = CollectionShare.objects.create(
            share_id=share_id,
            collection=collection,
            user=request.user,
            include_tags=include_tags
        )

        # Build full URL
        base_url = request.build_absolute_uri('/').rstrip('/')
        share_url = f"{base_url}/#/mapshare?id={collection_share.share_id}"

        return JsonResponse({
            'success': True,
            'share_id': collection_share.share_id,
            'url': share_url,
            'created_at': collection_share.created_at.isoformat()
        })

    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error creating collection share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to create collection share',
            'code': 500
        }, status=500)




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
    try:
        # Validate share_id format (must be UUID4)
        if not _validate_share_id(share_id):
            return JsonResponse({
                'success': False,
                'error': 'Invalid share link',
                'code': 404
            }, status=404)

        # Get the share
        share = CollectionShare.objects.filter(share_id=share_id).select_related('collection').first()
        
        if not share:
            # Return same error message to prevent information disclosure
            return JsonResponse({
                'success': False,
                'error': 'Invalid share link',
                'code': 404
            }, status=404)

        # Validate bbox and zoom parameters
        validation_result = _validate_bbox_params(request)
        if isinstance(validation_result, JsonResponse):
            return validation_result
        bbox, zoom_level = validation_result

        # Fetch data from database using collection query
        query_result = _get_features_in_bbox(bbox, share.user.id, zoom_level, collection_id=share.collection.id, public_safe=True, include_tags=share.include_tags)
        features = query_result.features
        total_features_in_bbox = query_result.total_count
        fallback_used = query_result.fallback_used

        # Build response using helper function, including collection name for frontend display
        response_data = _build_bbox_response(features, total_features_in_bbox, zoom_level, fallback_used, collection_name=share.collection.name)

        # Increment access count atomically only on successful response
        CollectionShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

        return JsonResponse(response_data)

    except Exception:
        logger.error(f"Error getting public collection share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get shared features',
            'code': 500
        }, status=500)

