"""Shared management operations for sharing (create, list, delete)"""
import uuid

from django.db.models import F, Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, FeatureShare, FeatureStore, Collection
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, not_found_response
from api.validation.feature_updates import validate_payload, UnifiedSharePayload
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(UnifiedSharePayload)
def create_share(request, validated_data):
    """
    Unified endpoint to create a share link for a tag, collection, or feature.
    Always uses UUID4 for share_id.
    
    POST body:
    - share_type: string (required) - 'tag', 'collection', or 'feature'
    - allow_downloads: boolean (optional, default=False) - Whether to allow downloads
    - tag: string (required if share_type is 'tag') - The tag to share
    - collection_id: string (required if share_type is 'collection') - The collection ID to share
    - include_tags: boolean (optional, default=False) - Include tags in shared features (only for collections)
    - feature_id: int (required if share_type is 'feature') - The feature ID to share
    """
    share_type = validated_data['share_type']
    allow_downloads = validated_data.get('allow_downloads', False)
    base_url = request.build_absolute_uri('/').rstrip('/')
    
    if share_type == 'tag':
        tag = validated_data.get('tag')
        if not tag:
            return error_response('tag is required when share_type is "tag"', code=400)
        
        # Validate tag length
        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        if len(tag) > tag_max_length:
            return error_response(f'Tag name exceeds maximum length of {tag_max_length} characters', code=400)
        
        # Verify that the tag exists in the user's features
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
        while (TagShare.objects.filter(share_id=share_id).exists() or 
               CollectionShare.objects.filter(share_id=share_id).exists() or
               FeatureShare.objects.filter(share_id=share_id).exists()):
            share_id = str(uuid.uuid4())
        
        # Create new share
        tag_share = TagShare.objects.create(
            share_id=share_id,
            tag=tag,
            user=request.user,
            allow_downloads=allow_downloads
        )
        
        share_url = f"{base_url}/#/mapshare?id={tag_share.share_id}"
        return JsonResponse({
            'share_id': tag_share.share_id,
            'url': share_url,
            'created_at': tag_share.created_at.isoformat(),
            'allow_downloads': tag_share.allow_downloads
        })
    
    elif share_type == 'collection':
        collection_id_str = validated_data.get('collection_id')
        if not collection_id_str:
            return error_response('collection_id is required when share_type is "collection"', code=400)
        include_tags = validated_data.get('include_tags', False)
        collection_id = uuid.UUID(collection_id_str)
        
        # Verify collection exists and belongs to user
        collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
        
        # Generate UUID4 share_id
        share_id = str(uuid.uuid4())
        while (TagShare.objects.filter(share_id=share_id).exists() or 
               CollectionShare.objects.filter(share_id=share_id).exists() or
               FeatureShare.objects.filter(share_id=share_id).exists()):
            share_id = str(uuid.uuid4())
        
        # Create new share
        collection_share = CollectionShare.objects.create(
            share_id=share_id,
            collection=collection,
            user=request.user,
            include_tags=include_tags,
            allow_downloads=allow_downloads
        )
        
        share_url = f"{base_url}/#/mapshare?id={collection_share.share_id}"
        return JsonResponse({
            'share_id': collection_share.share_id,
            'url': share_url,
            'created_at': collection_share.created_at.isoformat(),
            'allow_downloads': collection_share.allow_downloads,
            'include_tags': collection_share.include_tags
        })
    
    elif share_type == 'feature':
        feature_id = validated_data.get('feature_id')
        if feature_id is None:
            return error_response('feature_id is required when share_type is "feature"', code=400)
        
        # Verify feature exists and belongs to user
        feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
        
        # Check if a share already exists for this feature
        existing_share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
        if existing_share:
            share_url = f"{base_url}/#/mapshare?id={existing_share.share_id}"
            return JsonResponse({
                'share_id': existing_share.share_id,
                'url': share_url,
                'created_at': existing_share.created_at.isoformat(),
                'allow_downloads': existing_share.allow_downloads
            })
        
        # Generate UUID4 share_id
        share_id = str(uuid.uuid4())
        while (TagShare.objects.filter(share_id=share_id).exists() or 
               CollectionShare.objects.filter(share_id=share_id).exists() or
               FeatureShare.objects.filter(share_id=share_id).exists()):
            share_id = str(uuid.uuid4())
        
        # Create new share
        feature_share = FeatureShare.objects.create(
            share_id=share_id,
            feature=feature,
            user=request.user,
            allow_downloads=allow_downloads
        )
        
        share_url = f"{base_url}/#/mapshare?id={feature_share.share_id}"
        return JsonResponse({
            'share_id': feature_share.share_id,
            'url': share_url,
            'created_at': feature_share.created_at.isoformat(),
            'allow_downloads': feature_share.allow_downloads
        })
    
    else:
        return error_response(f'Invalid share_type: {share_type}', code=400)


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_shares(request):
    """
    List all shares for the current user (tag, collection, and feature shares).
    Returns list of shares with share_id, share_type, tag/collection/feature info, created_at, access_count, url
    Sorted by created_at descending (newest first).
    """
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
            'allow_downloads': share.allow_downloads
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
            'include_tags': share.include_tags,
            'allow_downloads': share.allow_downloads
        })

    # Get feature shares
    feature_shares = FeatureShare.objects.filter(user=request.user).select_related('feature')
    for share in feature_shares:
        share_url = f"{base_url}/#/mapshare?id={share.share_id}"
        # Get feature name from geojson properties
        feature_name = share.feature.geojson.get('properties', {}).get('name', 'Unnamed Feature')
        shares_list.append({
            'share_id': share.share_id,
            'share_type': 'feature',
            'feature_id': share.feature.id,
            'feature_name': feature_name,
            'created_at': share.created_at.isoformat(),
            'access_count': share.access_count,
            'url': share_url,
            'allow_downloads': share.allow_downloads
        })

    # Sort by created_at descending (newest first)
    shares_list.sort(key=lambda x: x['created_at'], reverse=True)

    return JsonResponse({
        'shares': shares_list
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
def delete_share(request, share_id):
    """
    Delete a share (tag, collection, or feature share).
    Automatically detects the share type.
    """
    # Try to find and delete as tag share first
    tag_share = TagShare.objects.filter(share_id=share_id, user=request.user).first()
    if tag_share:
        tag_share.delete()
        return success_response({'msg': 'Share deleted successfully'})

    # Try to find and delete as collection share
    collection_share = CollectionShare.objects.filter(share_id=share_id, user=request.user).first()
    if collection_share:
        collection_share.delete()
        return success_response({'msg': 'Share deleted successfully'})

    # Try to find and delete as feature share
    feature_share = FeatureShare.objects.filter(share_id=share_id, user=request.user).first()
    if feature_share:
        feature_share.delete()
        return success_response({'msg': 'Share deleted successfully'})

    # Share not found
    return error_response('Share not found', code=404)
