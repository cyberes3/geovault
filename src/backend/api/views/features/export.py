import uuid
from typing import Optional, Union

from django.db.models import Q, QuerySet
from django.http import HttpResponse, JsonResponse
from django.utils.text import slugify
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore, Collection
from api.utils.authorization import get_object_or_404_for_user
from api.views.features.bbox_utils import _build_base_query, _build_collection_query, strip_private_tags
from api.views.sharing.utils import find_share_by_id, validate_share_id
from geo_lib.export.feature_export_helpers import parse_feature_id
from geo_lib.export.geojson_preprocessor import prepare_geojson_for_kmz
from geo_lib.export.geojson_to_kmz import geojson_to_kmz_bytes
from geo_lib.export.share_export import build_share_feature_collection, prepare_kmz_options_for_share
from geo_lib.export.single_feature_export import prepare_kmz_options_for_feature
from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.secure_path import secure_filename
from website.settings_utils import get_required_setting

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
        tag_share, collection_share, feature_share, share, error_response = _lookup_and_validate_share(share_id)
        if error_response:
            return error_response
        
        # Feature shares are single feature downloads, not bulk
        if feature_share:
            return _handle_single_feature_download(request, feature_share.feature.id, share_id)
        
        # Tag and collection shares are bulk downloads
        return _handle_bulk_share_download(share_id)

    # Check for authenticated user bulk downloads (Tag or Collection or All)
    if (tag_name or collection_id_str or export_all == "true") and not raw_id:
        return _handle_user_bulk_download(request, tag_name, collection_id_str, export_all)

    # Single feature mode
    feature_id = parse_feature_id(raw_id)
    if feature_id is None:
        return JsonResponse(
            {"error": "Invalid feature id", "code": 400},
            status=400,
        )

    return _handle_single_feature_download(request, feature_id, share_id)


def _lookup_and_validate_share(share_id: str):
    """
    Look up and validate a share by share_id.

    Args:
        share_id: Share ID to look up

    Returns:
        Tuple (tag_share, collection_share, feature_share, share, error_response) where one of 
        tag_share/collection_share/feature_share is not None and share is the actual share object. 
        error_response is None on success.

    Raises:
        JsonResponse with appropriate error if validation or lookup fails
    """
    # Validate share_id format
    if not validate_share_id(share_id):
        # Security: Use generic error message to prevent information disclosure
        return None, None, None, None, JsonResponse(
            {"error": "Invalid request", "code": 400},
            status=400,
        )

    # Look up the share across all 3 share type tables
    share, share_type = find_share_by_id(share_id)
    if share is None:
        # Security: Use generic error message to prevent information disclosure about share existence
        return None, None, None, None, JsonResponse(
            {"error": "Invalid request", "code": 404},
            status=404,
        )

    # Check if downloads are allowed
    if not share.allow_downloads:
        return None, None, None, None, JsonResponse(
            {"error": "Access denied", "code": 403},
            status=403,
        )

    tag_share = share if share_type == 'tag' else None
    collection_share = share if share_type == 'collection' else None
    feature_share = share if share_type == 'feature' else None
    return tag_share, collection_share, feature_share, share, None


def _create_kmz_response(kmz_bytes: bytes, filename: str) -> HttpResponse:
    """
    Create an HttpResponse for KMZ file download.

    Args:
        kmz_bytes: KMZ file contents as bytes
        filename: Filename for the download

    Returns:
        HttpResponse with appropriate headers for KMZ download
    """
    safe_filename = secure_filename(filename)
    if len(safe_filename) > 255:
        if "." in safe_filename:
            name, ext = safe_filename.rsplit(".", 1)
            max_name_len = 255 - len(ext) - 1
            safe_filename = (name[:max_name_len] + "." + ext) if max_name_len > 0 else safe_filename[:255]
        else:
            safe_filename = safe_filename[:255]
    response = HttpResponse(
        kmz_bytes,
        content_type="application/vnd.google-earth.kmz",
    )
    response["Content-Disposition"] = f'attachment; filename="{safe_filename}"'
    return response


def _queryset_to_kmz_response(features: QuerySet, name: str) -> HttpResponse:
    """
    Convert a queryset of Features to a KMZ response.

    Args:
        features: QuerySet of FeatureStore objects
        name: Name to use for the KMZ file and internal document

    Returns:
        HttpResponse containing the KMZ file or JsonResponse on error
    """
    # Convert features to GeoJSON list
    geojson_features = []
    for feature in features:
        # No need for public_safe or allowing downloads check here since it's the owner
        geojson_data = feature.geojson
        if geojson_data:
            # Add id to properties if not present
            if 'properties' not in geojson_data:
                geojson_data['properties'] = {}
            geojson_data['properties']['database_id'] = feature.id

            # Pre-process GeoJSON (fix icon paths)
            prepared_geojson = prepare_geojson_for_kmz(geojson_data, str(get_required_setting('BASE_DIR')), get_required_setting('ICON_STORAGE_DIR'))
            geojson_features.append(prepared_geojson)

    if not geojson_features:
        return JsonResponse(
            {"error": "No features found", "code": 404},
            status=404,
        )

    feature_collection = {
        "type": "FeatureCollection",
        "features": geojson_features
    }

    # Convert to KMZ
    options = prepare_kmz_options_for_share(name, str(get_required_setting('BASE_DIR')))
    kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

    # Build filename
    slug = slugify(name) or "export"
    filename = f"{slug}.kmz"

    return _create_kmz_response(kmz_bytes, filename)


def _handle_bulk_share_download(share_id: str) -> Union[HttpResponse, JsonResponse]:
    """
    Handle bulk download for a public share.
    """
    # Look up and validate the share
    tag_share, collection_share, feature_share, share, error_response = _lookup_and_validate_share(share_id)
    if error_response:
        return error_response

    # Feature shares don't support bulk download (only single feature)
    if feature_share:
        return JsonResponse(
            {"error": "Bulk download not supported for feature shares", "code": 400},
            status=400,
        )

    # Get share name
    if tag_share:
        share_name = tag_share.tag
    else:
        share_name = collection_share.collection.name

    # Build feature collection from share
    feature_collection = build_share_feature_collection(
        tag_share=tag_share,
        collection_share=collection_share,
        share=share,
        build_base_query_func=_build_base_query,
        build_collection_query_func=_build_collection_query,
        base_dir=str(get_required_setting('BASE_DIR')),
        icon_storage_dir=get_required_setting('ICON_STORAGE_DIR'),
    )

    if not feature_collection.get("features"):
        # Security: Use generic error message
        return JsonResponse(
            {"error": "Invalid request", "code": 404},
            status=404,
        )

    # Convert to KMZ
    options = prepare_kmz_options_for_share(share_name, str(get_required_setting('BASE_DIR')))
    kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

    # Build filename
    slug = slugify(share_name) or "share"
    filename = f"{slug}-share.kmz"

    return _create_kmz_response(kmz_bytes, filename)


def _handle_user_bulk_download(request, tag_name: Optional[str], collection_id_str: Optional[str], export_all: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """
    Handle bulk download for an authenticated user.
    """
    # Authentication required
    if not request.user.is_authenticated:
        return JsonResponse(
            {"error": "Unauthorized", "code": 401},
            status=401,
        )

    if export_all == "true":
        # Build feature collection using base query (all user features)
        features = _build_base_query(request.user.id)
        share_name = "All Features"

    elif tag_name:
        # Verify tag exists in user's data
        # We can check if any feature has this tag
        tag_exists = FeatureStore.objects.filter(
            user=request.user
        ).filter(
            Q(geojson__properties__tags__contains=[tag_name]) |
            Q(geojson__properties__system_tags__contains=[tag_name])
        ).exists()

        if not tag_exists:
            return JsonResponse(
                {"error": "Tag not found", "code": 404},
                status=404,
            )

        # Build feature collection using base query
        features = _build_base_query(request.user.id, tag=tag_name)
        share_name = tag_name

    elif collection_id_str:
        # Validate UUID
        try:
            collection_id = uuid.UUID(collection_id_str)
        except ValueError:
            return JsonResponse(
                {"error": "Invalid collection ID", "code": 400},
                status=400,
            )

        # Verify collection exists and belongs to user
        collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
        share_name = collection.name

        # Build feature collection using collection query
        features = _build_collection_query(request.user.id, collection_id)

    else:
        return JsonResponse(
            {"error": "Invalid parameters", "code": 400},
            status=400,
        )

    return _queryset_to_kmz_response(features, share_name)


def _handle_single_feature_download(request, feature_id: int, share_id: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """
    Handle single feature download (authenticated or shared).
    """
    # Check if this is a public share request for a single feature
    if share_id:
        # Look up and validate the share
        tag_share, collection_share, feature_share, share, error_response = _lookup_and_validate_share(share_id)
        if error_response:
            return error_response

        # Get the feature and verify it's part of the share
        feature = FeatureStore.objects.filter(id=feature_id, user=share.user).first()
        if not feature:
            # Security: Use generic error message
            return JsonResponse(
                {"error": "Invalid request", "code": 404},
                status=404,
            )

        # Verify the feature matches the share criteria using the same query logic
        if tag_share:
            # For tag shares, use the same query builder to check if feature matches
            matching_features = _build_base_query(share.user.id, tag=tag_share.tag).filter(id=feature_id)
            if not matching_features.exists():
                return JsonResponse(
                    {"error": "Access denied", "code": 403},
                    status=403,
                )
        elif collection_share:
            # For collection shares, use the same query builder to check if feature matches
            matching_features = _build_collection_query(share.user.id, collection_share.collection.id).filter(id=feature_id)
            if not matching_features.exists():
                return JsonResponse(
                    {"error": "Access denied", "code": 403},
                    status=403,
                )
        elif feature_share:
            # For feature shares, verify the feature ID matches the shared feature
            if feature_share.feature.id != feature_id:
                return JsonResponse(
                    {"error": "Access denied", "code": 403},
                    status=403,
                )
    else:
        # Regular authenticated request
        if not request.user.is_authenticated:
            return JsonResponse(
                {"error": "Unauthorized", "code": 401},
                status=401,
            )
        # Ensure the feature belongs to the requesting user
        feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)

    geojson = feature.geojson or {}
    props = geojson.get("properties") or {}

    if share_id and not share.include_tags:
        # Strip tags/system_tags before they can end up in the KMZ placemark
        # description (see _apply_properties_to_placemark) - same rule the map
        # view GeoJSON endpoints apply.
        props = dict(props)
        strip_private_tags(props)
        geojson = {**geojson, "properties": props}

    # Use feature name for document title / filename when available
    name = props.get("name") or f"feature-{feature.id}"

    # Pre-process GeoJSON to convert icon API URLs to filesystem paths
    geojson_for_kmz = prepare_geojson_for_kmz(geojson, str(get_required_setting('BASE_DIR')), get_required_setting('ICON_STORAGE_DIR'))

    # Enable icon embedding with BASE_DIR as the base path
    options = prepare_kmz_options_for_feature(name, str(get_required_setting('BASE_DIR')))
    kmz_bytes = geojson_to_kmz_bytes(geojson_for_kmz, options=options)

    # Build a reasonably safe filename
    slug = slugify(name) or f"feature-{feature.id}"
    filename = f"{slug}.kmz"

    return _create_kmz_response(kmz_bytes, filename)
