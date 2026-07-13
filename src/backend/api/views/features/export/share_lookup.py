from typing import Optional, Tuple

from django.http import JsonResponse

from api.utils.responses import error_response
from api.views.sharing.utils import find_share_by_id, validate_share_id


def lookup_and_validate_share(share_id: str) -> Tuple[Optional[object], Optional[object], Optional[object], Optional[object], Optional[JsonResponse]]:
    """
    Look up and validate a share by share_id.

    Args:
        share_id: Share ID to look up

    Returns:
        Tuple (tag_share, collection_share, feature_share, share, error) where one of
        tag_share/collection_share/feature_share is not None and share is the actual share object.
        error is None on success, otherwise a JsonResponse the caller should return as-is.
    """
    # Validate share_id format
    if not validate_share_id(share_id):
        # Security: Use generic error message to prevent information disclosure
        return None, None, None, None, error_response("Invalid request", code=400)

    # Look up the share across all 3 share type tables
    share, share_type = find_share_by_id(share_id)
    if share is None:
        # Security: Use generic error message to prevent information disclosure about share existence
        return None, None, None, None, error_response("Invalid request", code=404)

    # Check if downloads are allowed
    if not share.allow_downloads:
        return None, None, None, None, error_response("Access denied", code=403)

    tag_share = share if share_type == 'tag' else None
    collection_share = share if share_type == 'collection' else None
    feature_share = share if share_type == 'feature' else None
    return tag_share, collection_share, feature_share, share, None
