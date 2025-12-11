"""Shared management operations for sharing (list, delete)"""

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import TagShare, CollectionShare
from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_shares(request):
    """
    List all shares for the current user (both tag and collection shares).
    Returns list of shares with share_id, share_type, tag/collection info, created_at, access_count, url
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

    # Sort by created_at descending (newest first)
    shares_list.sort(key=lambda x: x['created_at'], reverse=True)

    return JsonResponse({
        'shares': shares_list
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
def delete_share(request, share_id):
    """
    Delete a share (either tag or collection share).
    Automatically detects if it's a tag or collection share.
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

    # Share not found
    return error_response('Share not found', code=404)
