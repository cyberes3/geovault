"""Collection sharing operations"""
from django.views.decorators.http import require_http_methods

from api.models import CollectionShare
from api.views.sharing.public_share import resolve_public_bbox_share
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger()


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
    return resolve_public_bbox_share(
        request,
        CollectionShare,
        share_id,
        lambda share: {'collection_id': share.collection.id},
        extra_response_fields=lambda share: {'collection_name': share.collection.name},
    )
