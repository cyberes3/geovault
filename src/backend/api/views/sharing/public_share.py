"""Shared helpers for public share metadata, extents, and bbox-based feature queries."""
from typing import Callable, Optional

from django.contrib.gis.db.models.aggregates import Extent
from django.db.models import F, Model, Q
from django.http import JsonResponse

from api.models import FeatureStore
from api.utils.format_encoding import create_bbox_response
from api.utils.responses import error_response
from api.views.collections.utils import get_collection_feature_ids
from api.views.features.bbox.execution import get_features_in_bbox
from api.views.features.bbox.params import _validate_bbox_params
from api.views.features.bbox.response import _build_bbox_response
from api.views.sharing.utils import find_share_by_id, validate_share_id


def invalid_share_response() -> JsonResponse:
    """Standard 404 for any invalid/unknown share_id, shared across all share types so a
    malformed ID and a well-formed-but-nonexistent one are indistinguishable to the caller."""
    return error_response('Invalid share link', code=404)


def resolve_public_bbox_share(
    request,
    model: type[Model],
    share_id: str,
    bbox_query_kwargs: Callable[[Model], dict],
    extra_response_fields: Optional[Callable[[Model], dict]] = None,
) -> JsonResponse:
    """
    Shared implementation for the tag and collection public "features in bbox" share
    endpoints, which are identical except for how they narrow `get_features_in_bbox`
    (by tag vs. by collection) and what extra display field they surface.

    Args:
        model: TagShare or CollectionShare
        share_id: share_id path parameter
        bbox_query_kwargs: given the fetched share row, returns the type-specific
            kwargs to pass to get_features_in_bbox (e.g. {'tags': [share.tag]})
        extra_response_fields: optional, given the share row, returns extra fields
            to merge into the response body (e.g. {'collection_name': ...})

    Returns:
        JsonResponse in the same shape both endpoints already returned.
    """
    if not validate_share_id(share_id):
        return invalid_share_response()

    share, share_type = find_share_by_id(share_id)
    if share is None or not isinstance(share, model):
        # Same error message as an invalid format, to avoid leaking share existence.
        return invalid_share_response()

    validation_result = _validate_bbox_params(request)
    if isinstance(validation_result, JsonResponse):
        return validation_result
    bbox, zoom_level = validation_result

    query_result = get_features_in_bbox(
        bbox,
        share.user.id,
        public_safe=True,
        include_tags=share.include_tags,
        allow_downloads=share.allow_downloads,
        **bbox_query_kwargs(share),
    )

    extra_fields = extra_response_fields(share) if extra_response_fields else {}
    response_data = _build_bbox_response(
        query_result.features, query_result.total_count, zoom_level, query_result.fallback_used, **extra_fields
    )

    # Increment access count atomically only on successful response
    model.objects.filter(share_id=share_id).update(access_count=F('access_count') + 1)

    return create_bbox_response(response_data, request)


def resolve_public_share_info(share_id: str):
    """
    Resolve public share metadata for any share type.

    Returns:
        dict with share fields, or None when share_id is invalid/not found.
    """
    if not validate_share_id(share_id):
        return None

    share, share_type = find_share_by_id(share_id)
    if share is None:
        return None

    common_fields = {
        "created_at": share.created_at.isoformat(),
        "include_tags": share.include_tags,
        "allow_downloads": share.allow_downloads,
    }

    if share_type == "tag":
        return {"share_type": "tag", "tag": share.tag, **common_fields}

    if share_type == "collection":
        return {
            "share_type": "collection",
            "collection_name": share.collection.name,
            "collection_id": str(share.collection.id),
            **common_fields,
        }

    feature_name = share.feature.geojson.get("properties", {}).get("name", "Unnamed Feature")
    return {
        "share_type": "feature",
        "feature_name": feature_name,
        "feature_id": share.feature.id,
        **common_fields,
    }


def resolve_public_share_extent(share_id: str):
    """
    Resolve public share geographic extent as (min_lon, min_lat, max_lon, max_lat).

    Returns:
        4-tuple extent or None if not found/no geometry.
    """
    if not validate_share_id(share_id):
        return None

    share, share_type = find_share_by_id(share_id)
    if share is None:
        return None

    if share_type == "tag":
        # Main-map only, matching the scope get_features_in_bbox already applies when
        # actually listing features for this same tag share.
        return (
            FeatureStore.objects.owned_by(share.user).main_map()
            .filter(
                Q(geojson__properties__tags__contains=[share.tag])
                | Q(geojson__properties__system_tags__contains=[share.tag])
            )
            .with_geometry()
            .aggregate(extent=Extent("geometry"))
            .get("extent")
        )

    if share_type == "collection":
        feature_ids = get_collection_feature_ids(share.collection)
        if not feature_ids:
            return None
        return (
            FeatureStore.objects.owned_by(share.user).filter(id__in=feature_ids)
            .with_geometry()
            .aggregate(extent=Extent("geometry"))
            .get("extent")
        )

    if share.feature.geometry:
        return share.feature.geometry.extent
    return None
