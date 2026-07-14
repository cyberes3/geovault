import uuid
from typing import Optional, Union

from django.db.models import Q
from django.http import HttpResponse, JsonResponse
from django.utils.text import slugify

from api.models import FeatureStore, Collection
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response
from api.views.features.bbox.query_builder import _build_base_query, _build_collection_query
from api.views.features.export.kmz_builder import build_kmz_response, queryset_to_kmz_response
from api.views.features.export.share_lookup import lookup_and_validate_share
from geo_lib.export.geojson_preprocessor import prepare_geojson_for_kmz
from geo_lib.export.geojson_to_kmz import geojson_to_kmz_bytes
from geo_lib.export.share_export import build_share_feature_collection, prepare_kmz_options_for_share
from geo_lib.export.single_feature_export import prepare_kmz_options_for_feature
from geo_lib.processing.tagging.const_strings import strip_private_tags
from website.settings_utils import get_required_setting


def handle_bulk_share_download(share_id: str) -> Union[HttpResponse, JsonResponse]:
    """
    Handle bulk download for a public share.
    """
    tag_share, collection_share, feature_share, share, error = lookup_and_validate_share(share_id)
    if error:
        return error

    # Feature shares don't support bulk download (only single feature)
    if feature_share:
        return error_response("Bulk download not supported for feature shares", code=400)

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
        return error_response("Invalid request", code=404)

    # Convert to KMZ
    options = prepare_kmz_options_for_share(share_name, str(get_required_setting('BASE_DIR')))
    kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

    # Build filename
    slug = slugify(share_name) or "share"
    filename = f"{slug}-share.kmz"

    return build_kmz_response(kmz_bytes, filename)


def handle_user_bulk_download(request, tag_name: Optional[str], collection_id_str: Optional[str], export_all: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """
    Handle bulk download for an authenticated user.
    """
    if not request.user.is_authenticated:
        return error_response("Unauthorized", code=401)

    if export_all == "true":
        # Build feature collection using base query (all user features)
        features = _build_base_query(request.user.id)
        share_name = "All Features"

    elif tag_name:
        # Verify tag exists on a main-map feature (extension-scoped features, e.g.
        # `places`, are never bulk-exportable through this endpoint -- matches the
        # scope `_build_base_query` below already applies when actually fetching features).
        tag_exists = FeatureStore.objects.owned_by(request.user).main_map().filter(
            Q(geojson__properties__tags__contains=[tag_name]) |
            Q(geojson__properties__system_tags__contains=[tag_name])
        ).exists()

        if not tag_exists:
            return error_response("Tag not found", code=404)

        # Build feature collection using base query
        features = _build_base_query(request.user.id, tag=tag_name)
        share_name = tag_name

    elif collection_id_str:
        # Validate UUID
        try:
            collection_id = uuid.UUID(collection_id_str)
        except ValueError:
            return error_response("Invalid collection ID", code=400)

        # Verify collection exists and belongs to user
        collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
        share_name = collection.name

        # Build feature collection using collection query
        features = _build_collection_query(request.user.id, collection_id)

    else:
        return error_response("Invalid parameters", code=400)

    return queryset_to_kmz_response(features, share_name)


def handle_single_feature_download(request, feature_id: int, share_id: Optional[str]) -> Union[HttpResponse, JsonResponse]:
    """
    Handle single feature download (authenticated or shared).
    """
    # Check if this is a public share request for a single feature
    if share_id:
        tag_share, collection_share, feature_share, share, error = lookup_and_validate_share(share_id)
        if error:
            return error

        # Get the feature and verify it's part of the share
        feature = FeatureStore.objects.filter(id=feature_id, user=share.user).first()
        if not feature:
            # Security: Use generic error message
            return error_response("Invalid request", code=404)

        # Verify the feature matches the share criteria using the same query logic
        if tag_share:
            # For tag shares, use the same query builder to check if feature matches
            matching_features = _build_base_query(share.user.id, tag=tag_share.tag).filter(id=feature_id)
            if not matching_features.exists():
                return error_response("Access denied", code=403)
        elif collection_share:
            # For collection shares, use the same query builder to check if feature matches
            matching_features = _build_collection_query(share.user.id, collection_share.collection.id).filter(id=feature_id)
            if not matching_features.exists():
                return error_response("Access denied", code=403)
        elif feature_share:
            # For feature shares, verify the feature ID matches the shared feature
            if feature_share.feature.id != feature_id:
                return error_response("Access denied", code=403)
    else:
        # Regular authenticated request
        if not request.user.is_authenticated:
            return error_response("Unauthorized", code=401)
        # Ensure the feature belongs to the requesting user and is a main-map feature
        feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

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

    return build_kmz_response(kmz_bytes, filename)
