"""Tag sharing operations"""
from django.views.decorators.http import require_http_methods

from api.models import TagShare
from api.utils.responses import success_response
from api.views.sharing.public_share import invalid_share_response, resolve_public_bbox_share, resolve_public_share_info
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger()


@require_http_methods(["GET"])
def get_public_share_info(request, share_id):
    """
    Public endpoint to get information about any share (tag, collection, or feature).
    No authentication required.
    Returns share type and basic info for display purposes without revealing data.
    Does not increment access_count.
    """
    info = resolve_public_share_info(share_id)
    if info is None:
        # Share not found - return same error message to prevent information disclosure
        return invalid_share_response()
    return success_response(info)


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
    return resolve_public_bbox_share(request, TagShare, share_id, lambda share: {'tags': [share.tag]})
