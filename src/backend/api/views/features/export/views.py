from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response
from api.views.features.export.handlers import (
    handle_bulk_share_download,
    handle_single_feature_download,
    handle_user_bulk_download,
)
from api.views.features.export.share_lookup import lookup_and_validate_share
from geo_lib.export.feature_export_helpers import parse_feature_id
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger()


@require_http_methods(["GET"])
def export_feature_kmz(request):
    """
    Export features as KMZ download.

    Routes:
    - Single feature: /api/export-kmz?feature=<id>[&share=<share_id>]
    - Bulk share: /api/export-kmz?share=<share_id>
    - Bulk tag/collection/all: /api/export-kmz?tag=<name> OR ?collection=<id> OR ?all=true

    For bulk share mode, exports all features in the share as a single KMZ file.
    If share_id is provided and the share allows downloads, public access is permitted.
    Otherwise, authentication is required and features must belong to the user.
    """
    raw_id = request.GET.get("feature")
    share_id = request.GET.get("share")
    tag_name = request.GET.get("tag")
    collection_id_str = request.GET.get("collection")
    export_all = request.GET.get("all")

    # Check if this is a bulk share download (share parameter without feature)
    if share_id and not raw_id:
        # Check if it's a feature share (single feature, not bulk)
        tag_share, collection_share, feature_share, share, error = lookup_and_validate_share(share_id)
        if error:
            return error

        # Feature shares are single feature downloads, not bulk
        if feature_share:
            return handle_single_feature_download(request, feature_share.feature.id, share_id)

        # Tag and collection shares are bulk downloads
        return handle_bulk_share_download(share_id)

    # Check for authenticated user bulk downloads (Tag or Collection or All)
    if (tag_name or collection_id_str or export_all == "true") and not raw_id:
        return handle_user_bulk_download(request, tag_name, collection_id_str, export_all)

    # Single feature mode
    feature_id = parse_feature_id(raw_id)
    if feature_id is None:
        return error_response("Invalid feature id", code=400)

    return handle_single_feature_download(request, feature_id, share_id)
