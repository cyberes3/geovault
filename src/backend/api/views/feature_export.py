import traceback
import uuid
from typing import Optional, Tuple, Union

from django.conf import settings
from django.db.models import Q, QuerySet
from django.http import HttpResponse, JsonResponse
from django.utils.text import slugify
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore, TagShare, CollectionShare, Collection
from api.views.bbox_query import _build_base_query, _build_collection_query
from api.views.sharing import _validate_share_id
from geo_lib.export import (
    geojson_to_kmz_bytes,
    parse_feature_id,
    prepare_geojson_for_kmz,
    build_share_feature_collection,
    prepare_kmz_options_for_share,
    prepare_kmz_options_for_feature,
)
from geo_lib.logging.console import get_access_logger

logger = get_access_logger()


def _lookup_and_validate_share(share_id: str):
    """
    Look up and validate a share by share_id.
    
    Args:
        share_id: Share ID to look up
        
    Returns:
        Tuple (tag_share, collection_share, share, error_response) where one of tag_share/collection_share
        is None and share is the actual share object. error_response is None on success.
        
    Raises:
        JsonResponse with appropriate error if validation or lookup fails
    """
    # Validate share_id format
    if not _validate_share_id(share_id):
        # Security: Use generic error message to prevent information disclosure
        return None, None, None, JsonResponse(
            {"error": "Invalid request", "code": 400},
            status=400,
        )

    # Look up the share
    tag_share = TagShare.objects.filter(share_id=share_id).first()
    collection_share = None
    if not tag_share:
        collection_share = CollectionShare.objects.filter(share_id=share_id).select_related('collection').first()

    share = tag_share or collection_share
    if not share:
        # Security: Use generic error message to prevent information disclosure about share existence
        return None, None, None, JsonResponse(
            {"error": "Invalid request", "code": 404},
            status=404,
        )

    # Check if downloads are allowed
    if not share.allow_downloads:
        return None, None, None, JsonResponse(
            {"error": "Access denied", "code": 403},
            status=403,
        )

    return tag_share, collection_share, share, None


def _sanitize_filename(filename: str, max_length: int = 255) -> str:
    """
    Sanitize and validate filename for safe download.
    
    Args:
        filename: Original filename
        max_length: Maximum allowed filename length (default 255 for most filesystems)
        
    Returns:
        Sanitized filename, truncated if necessary
    """
    # Remove any path separators and control characters
    sanitized = filename.replace("/", "_").replace("\\", "_")
    # Remove any null bytes
    sanitized = sanitized.replace("\x00", "")
    
    # Truncate if too long (leave room for extension)
    if len(sanitized) > max_length:
        # Try to preserve extension
        if "." in sanitized:
            name, ext = sanitized.rsplit(".", 1)
            max_name_len = max_length - len(ext) - 1
            if max_name_len > 0:
                sanitized = name[:max_name_len] + "." + ext
            else:
                sanitized = sanitized[:max_length]
        else:
            sanitized = sanitized[:max_length]
    
    return sanitized


def _create_kmz_response(kmz_bytes: bytes, filename: str) -> HttpResponse:
    """
    Create an HttpResponse for KMZ file download.
    
    Args:
        kmz_bytes: KMZ file contents as bytes
        filename: Filename for the download
        
    Returns:
        HttpResponse with appropriate headers for KMZ download
    """
    # Security: Sanitize and validate filename length
    safe_filename = _sanitize_filename(filename)
    
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
            geojson_data['properties']['_id'] = feature.id
            
            # Pre-process GeoJSON (fix icon paths)
            prepared_geojson = prepare_geojson_for_kmz(geojson_data, str(settings.BASE_DIR), settings.ICON_STORAGE_DIR)
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
    options = prepare_kmz_options_for_share(name, str(settings.BASE_DIR))
    kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

    # Build filename
    slug = slugify(name) or "export"
    filename = f"{slug}.kmz"

    return _create_kmz_response(kmz_bytes, filename)


def _handle_bulk_share_download(share_id: str) -> Union[HttpResponse, JsonResponse]:
    """Handle bulk download for a public share."""
    try:
        # Look up and validate the share
        tag_share, collection_share, share, error_response = _lookup_and_validate_share(share_id)
        if error_response:
            return error_response

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
            base_dir=str(settings.BASE_DIR),
            icon_storage_dir=settings.ICON_STORAGE_DIR,
        )

        if not feature_collection.get("features"):
            # Security: Use generic error message
            return JsonResponse(
                {"error": "Invalid request", "code": 404},
                status=404,
            )

        # Convert to KMZ
        options = prepare_kmz_options_for_share(share_name, str(settings.BASE_DIR))
        kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

        # Build filename
        slug = slugify(share_name) or "share"
        filename = f"{slug}-share.kmz"

        return _create_kmz_response(kmz_bytes, filename)

    except Exception:
        logger.error(
            f"Error exporting share {share_id} as KMZ: {traceback.format_exc()}"
        )
        return JsonResponse(
            {"error": "Failed to export share as KMZ", "code": 500},
            status=500,
        )


def _handle_user_bulk_download(request, tag_name: Optional[str], collection_id_str: Optional[str], export_all: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """Handle bulk download for an authenticated user."""
    # Authentication required
    if not request.user.is_authenticated:
        return JsonResponse(
            {"error": "Unauthorized", "code": 401},
            status=401,
        )

    try:
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
            try:
                collection = Collection.objects.get(id=collection_id, user=request.user)
                share_name = collection.name
            except Collection.DoesNotExist:
                return JsonResponse(
                    {"error": "Collection not found", "code": 404},
                    status=404,
                )

            # Build feature collection using collection query
            features = _build_collection_query(request.user.id, collection_id)
        
        else:
             return JsonResponse(
                {"error": "Invalid parameters", "code": 400},
                status=400,
            )

        return _queryset_to_kmz_response(features, share_name)

    except Exception:
        logger.error(
            f"Error exporting {'all features' if export_all == 'true' else (tag_name if tag_name else collection_id_str)} as KMZ: {traceback.format_exc()}"
        )
        return JsonResponse(
            {"error": "Failed to export KMZ", "code": 500},
            status=500,
        )


def _handle_single_feature_download(request, feature_id: int, share_id: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """Handle single feature download (authenticated or shared)."""
    try:
        # Check if this is a public share request for a single feature
        if share_id:
            # Look up and validate the share
            tag_share, collection_share, share, error_response = _lookup_and_validate_share(share_id)
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
        else:
            # Regular authenticated request
            if not request.user.is_authenticated:
                return JsonResponse(
                    {"error": "Unauthorized", "code": 401},
                    status=401,
                )
            # Ensure the feature belongs to the requesting user
            feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        geojson = feature.geojson or {}
        props = geojson.get("properties") or {}

        # Use feature name for document title / filename when available
        name = props.get("name") or f"feature-{feature.id}"

        # Pre-process GeoJSON to convert icon API URLs to filesystem paths
        geojson_for_kmz = prepare_geojson_for_kmz(geojson, str(settings.BASE_DIR), settings.ICON_STORAGE_DIR)

        # Enable icon embedding with BASE_DIR as the base path
        options = prepare_kmz_options_for_feature(name, str(settings.BASE_DIR))
        kmz_bytes = geojson_to_kmz_bytes(geojson_for_kmz, options=options)

        # Build a reasonably safe filename
        slug = slugify(name) or f"feature-{feature.id}"
        filename = f"{slug}.kmz"

        return _create_kmz_response(kmz_bytes, filename)

    except FeatureStore.DoesNotExist:
        # Security: Use generic error message
        return JsonResponse(
            {"error": "Invalid request", "code": 404},
            status=404,
        )
    except Exception:
        logger.error(
            f"Error exporting feature {feature_id} as KMZ: {traceback.format_exc()}"
        )
        return JsonResponse(
            {"error": "Failed to export feature as KMZ", "code": 500},
            status=500,
        )


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
