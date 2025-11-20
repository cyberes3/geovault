import re
import traceback
import uuid

from django.db.models import F
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


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

        # Generate UUID4 share_id
        share_id = str(uuid.uuid4())
        # Ensure uniqueness (very unlikely but check anyway)
        while TagShare.objects.filter(share_id=share_id).exists():
            share_id = str(uuid.uuid4())

        # Check if user already has a share for this tag
        existing_share = TagShare.objects.filter(user=request.user, tag=tag).first()
        if existing_share:
            # Return existing share instead of creating duplicate
            base_url = request.build_absolute_uri('/').rstrip('/')
            share_url = f"{base_url}/#/mapshare?id={existing_share.share_id}"
            return JsonResponse({
                'success': True,
                'share_id': existing_share.share_id,
                'url': share_url,
                'created_at': existing_share.created_at.isoformat(),
                'message': 'Share already exists for this tag'
            })

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
    List all shares for the current user.
    Returns list of shares with share_id, tag, created_at, access_count, url
    """
    try:
        shares = TagShare.objects.filter(user=request.user).order_by('-created_at')

        base_url = request.build_absolute_uri('/').rstrip('/')
        shares_list = []
        for share in shares:
            share_url = f"{base_url}/#/mapshare?id={share.share_id}"
            shares_list.append({
                'share_id': share.share_id,
                'tag': share.tag,
                'created_at': share.created_at.isoformat(),
                'access_count': share.access_count,
                'url': share_url,
                'use_tag_as_id': share.use_tag_as_id
            })

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
@require_http_methods(["DELETE"])
def delete_share(request, share_id):
    """
    Delete a share by share_id.
    Only allows deletion of shares owned by the current user.
    """
    try:
        # Validate share_id format
        if not _validate_share_id(share_id):
            return JsonResponse({
                'success': False,
                'error': 'Invalid share ID',
                'code': 400
            }, status=400)

        share = TagShare.objects.filter(share_id=share_id, user=request.user).first()
        
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
def get_public_share(request, share_id):
    """
    Public endpoint to get features for a shared tag.
    No authentication required.
    Returns GeoJSON FeatureCollection of features with the shared tag.
    Increments access_count on each successful access.
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

        # Get all features for the user that have this tag
        features = FeatureStore.objects.filter(user=share.user)

        # Dictionary to store features with the tag
        geojson_features = []

        # Iterate through all features
        for feature in features:
            geojson_data = feature.geojson
            if not geojson_data or 'properties' not in geojson_data:
                continue

            properties = geojson_data.get('properties', {})
            tags = properties.get('tags', [])

            if not isinstance(tags, list):
                continue

            # Check if this feature has the shared tag
            if share.tag not in tags:
                continue

            # Filter out protected tags for public view
            filtered_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)

            # Create feature properties (exclude internal IDs for public view)
            feature_properties = properties.copy()
            # Don't include database ID in public view
            if '_id' in feature_properties:
                del feature_properties['_id']

            # Create GeoJSON feature
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": feature_properties,
                "geojson_hash": feature.file_hash
            }

            geojson_features.append(geojson_feature)

        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }

        # Increment access count atomically only on successful response
        TagShare.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

        return JsonResponse({
            'success': True,
            'data': geojson_data,
            'feature_count': len(geojson_features),
            'tag': share.tag
        })

    except Exception:
        logger.error(f"Error getting public share: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get shared features',
            'code': 500
        }, status=500)

