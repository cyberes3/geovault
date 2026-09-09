from django.views.decorators.http import require_http_methods

from api.sharing.export_service import ShareExportService
from api.utils.responses import error_response
from api.views.features.export.share_lookup import lookup_and_validate_share
from geo_lib.export.feature_export_helpers import parse_feature_id


@require_http_methods(["GET"])
def export_feature_kmz(request):
    """
    Export features as KMZ download.

    Routes:
    - Single feature: /api/export-kmz?feature=<id>[&share=<share_id>]
    - Bulk share: /api/export-kmz?share=<share_id>
    - Bulk tag/collection/all: /api/export-kmz?tag=<name> OR ?collection=<id> OR ?all=true

    Share tokens are resolved once and passed through ShareExportService.
    """
    raw_id = request.GET.get("feature")
    share_id = request.GET.get("share")
    tag_name = request.GET.get("tag")
    collection_id_str = request.GET.get("collection")
    export_all = request.GET.get("all")

    if share_id:
        share, error = lookup_and_validate_share(share_id)
        if error:
            return error
        feature_id = None
        if raw_id:
            feature_id = parse_feature_id(raw_id)
            if feature_id is None:
                return error_response("Invalid feature id", code=400)
        return ShareExportService.export_share_kmz(share, feature_id=feature_id)

    if raw_id:
        feature_id = parse_feature_id(raw_id)
        if feature_id is None:
            return error_response("Invalid feature id", code=400)
        return ShareExportService.export_owner_kmz(request, feature_id=feature_id)

    if tag_name or collection_id_str or export_all == "true":
        return ShareExportService.export_owner_kmz(
            request,
            tag_name=tag_name,
            collection_id_str=collection_id_str,
            export_all=export_all,
        )

    return error_response("Invalid feature id", code=400)
