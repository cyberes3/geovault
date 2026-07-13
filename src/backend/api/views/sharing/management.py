"""Shared management operations for sharing (create, list, delete)"""
import uuid

from django.db.models import Q
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare, FeatureShare, FeatureStore, Collection
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, not_found_response
from api.validation.decorators import validate_payload
from api.validation.payloads.sharing import UnifiedSharePayload
from api.views.sharing.utils import build_share_url, generate_unique_share_id
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from website.map_share_social.preview_warmup import trigger_social_preview_warmup_async
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()

# Every share type persists to a different model with different identifying fields, so
# this can't be fully data-driven — but every model shares the same (share_id, user,
# created_at, access_count, include_tags, allow_downloads) shape, which the helpers
# below (`_share_summary`, `delete_share`) key off of generically.
_SHARE_MODELS = (TagShare, CollectionShare, FeatureShare)


def _share_summary(request, share, share_type: str, **extra_fields) -> dict:
    """Build the common response/listing fields shared by every share type, plus
    whatever type-specific fields the caller supplies (e.g. `tag=`, `feature_id=`)."""
    return {
        'share_id': share.share_id,
        'share_type': share_type,
        'url': build_share_url(request, share.share_id),
        'created_at': share.created_at.isoformat(),
        'access_count': share.access_count,
        'include_tags': share.include_tags,
        'allow_downloads': share.allow_downloads,
        **extra_fields,
    }


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
    - include_tags: boolean (optional, default=False) - Include tags in the shared feature(s)
    - feature_id: int (required if share_type is 'feature') - The feature ID to share
    """
    share_type = validated_data['share_type']
    allow_downloads = validated_data.get('allow_downloads', False)
    
    if share_type == 'tag':
        tag = validated_data.get('tag')
        if not tag:
            return error_response('tag is required when share_type is "tag"', code=400)
        include_tags = validated_data.get('include_tags', False)
        
        # Validate tag length
        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        if len(tag) > tag_max_length:
            return error_response(f'Tag name exceeds maximum length of {tag_max_length} characters', code=400)
        
        # Verify that the tag exists in the user's main-map features (extension-scoped
        # features, e.g. `places`, are never shareable through this endpoint).
        tag_exists = FeatureStore.objects.owned_by(request.user).main_map().filter(
            Q(geojson__properties__tags__contains=[tag]) |
            Q(geojson__properties__system_tags__contains=[tag])
        ).exists()
        
        if not tag_exists:
            return not_found_response('Tag not found in your data')
        
        # Create new share
        tag_share = TagShare.objects.create(
            share_id=generate_unique_share_id(),
            tag=tag,
            user=request.user,
            include_tags=include_tags,
            allow_downloads=allow_downloads
        )
        trigger_social_preview_warmup_async(tag_share.share_id)

        return success_response(_share_summary(request, tag_share, 'tag', tag=tag_share.tag))
    
    elif share_type == 'collection':
        collection_id_str = validated_data.get('collection_id')
        if not collection_id_str:
            return error_response('collection_id is required when share_type is "collection"', code=400)
        include_tags = validated_data.get('include_tags', False)
        collection_id = uuid.UUID(collection_id_str)
        
        # Verify collection exists and belongs to user
        collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
        
        # Create new share
        collection_share = CollectionShare.objects.create(
            share_id=generate_unique_share_id(),
            collection=collection,
            user=request.user,
            include_tags=include_tags,
            allow_downloads=allow_downloads
        )
        trigger_social_preview_warmup_async(collection_share.share_id)

        return success_response(_share_summary(
            request, collection_share, 'collection',
            collection_id=str(collection.id), collection_name=collection.name,
        ))
    
    elif share_type == 'feature':
        feature_id = validated_data.get('feature_id')
        if feature_id is None:
            return error_response('feature_id is required when share_type is "feature"', code=400)
        include_tags = validated_data.get('include_tags', False)
        
        # Verify feature exists, belongs to user, and is a main-map feature
        feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

        # Check if a share already exists for this feature
        existing_share = FeatureShare.objects.filter(feature=feature, user=request.user).first()
        if existing_share:
            return success_response(_share_summary(request, existing_share, 'feature', feature_id=feature.id))
        
        # Create new share
        feature_share = FeatureShare.objects.create(
            share_id=generate_unique_share_id(),
            feature=feature,
            user=request.user,
            include_tags=include_tags,
            allow_downloads=allow_downloads
        )
        trigger_social_preview_warmup_async(feature_share.share_id)

        return success_response(_share_summary(request, feature_share, 'feature', feature_id=feature.id))
    
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
    shares_list = []

    for share in TagShare.objects.filter(user=request.user):
        shares_list.append(_share_summary(request, share, 'tag', tag=share.tag))

    for share in CollectionShare.objects.filter(user=request.user).select_related('collection'):
        shares_list.append(_share_summary(
            request, share, 'collection',
            collection_id=str(share.collection.id), collection_name=share.collection.name,
        ))

    for share in FeatureShare.objects.filter(user=request.user).select_related('feature'):
        feature_name = share.feature.geojson.get('properties', {}).get('name', 'Unnamed Feature')
        shares_list.append(_share_summary(
            request, share, 'feature',
            feature_id=share.feature.id, feature_name=feature_name,
        ))

    # Sort by created_at descending (newest first)
    shares_list.sort(key=lambda x: x['created_at'], reverse=True)

    return success_response({
        'shares': shares_list
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
def delete_share(request, share_id):
    """
    Delete a share (tag, collection, or feature share).
    Automatically detects the share type.
    """
    for model in _SHARE_MODELS:
        share = model.objects.filter(share_id=share_id, user=request.user).first()
        if share:
            share.delete()
            return success_response({'msg': 'Share deleted successfully'})

    return error_response('Share not found', code=404)
