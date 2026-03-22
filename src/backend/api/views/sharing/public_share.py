"""Shared helpers for public share metadata and extents."""
from django.contrib.gis.db.models.aggregates import Extent
from django.db.models import Q

from api.models import TagShare, CollectionShare, FeatureShare, FeatureStore
from api.views.collections.utils import get_collection_feature_ids
from api.views.sharing.utils import validate_share_id


def resolve_public_share_info(share_id: str):
    """
    Resolve public share metadata for any share type.

    Returns:
        dict with share fields, or None when share_id is invalid/not found.
    """
    if not validate_share_id(share_id):
        return None

    tag_share = TagShare.objects.filter(share_id=share_id).first()
    if tag_share:
        return {
            "share_type": "tag",
            "tag": tag_share.tag,
            "created_at": tag_share.created_at.isoformat(),
            "allow_downloads": tag_share.allow_downloads,
        }

    collection_share = CollectionShare.objects.filter(share_id=share_id).select_related("collection").first()
    if collection_share:
        return {
            "share_type": "collection",
            "collection_name": collection_share.collection.name,
            "collection_id": str(collection_share.collection.id),
            "created_at": collection_share.created_at.isoformat(),
            "include_tags": collection_share.include_tags,
            "allow_downloads": collection_share.allow_downloads,
        }

    feature_share = FeatureShare.objects.filter(share_id=share_id).select_related("feature").first()
    if feature_share:
        feature_name = feature_share.feature.geojson.get("properties", {}).get("name", "Unnamed Feature")
        return {
            "share_type": "feature",
            "feature_name": feature_name,
            "feature_id": feature_share.feature.id,
            "created_at": feature_share.created_at.isoformat(),
            "allow_downloads": feature_share.allow_downloads,
        }

    return None


def resolve_public_share_extent(share_id: str):
    """
    Resolve public share geographic extent as (min_lon, min_lat, max_lon, max_lat).

    Returns:
        4-tuple extent or None if not found/no geometry.
    """
    if not validate_share_id(share_id):
        return None

    tag_share = TagShare.objects.filter(share_id=share_id).first()
    if tag_share:
        extent = (
            FeatureStore.objects.filter(user=tag_share.user)
            .filter(
                Q(geojson__properties__tags__contains=[tag_share.tag])
                | Q(geojson__properties__system_tags__contains=[tag_share.tag])
            )
            .exclude(geometry__isnull=True)
            .aggregate(extent=Extent("geometry"))
            .get("extent")
        )
        return extent

    collection_share = CollectionShare.objects.filter(share_id=share_id).select_related("collection").first()
    if collection_share:
        feature_ids = get_collection_feature_ids(collection_share.collection)
        if not feature_ids:
            return None
        extent = (
            FeatureStore.objects.filter(user=collection_share.user, id__in=feature_ids)
            .exclude(geometry__isnull=True)
            .aggregate(extent=Extent("geometry"))
            .get("extent")
        )
        return extent

    feature_share = FeatureShare.objects.filter(share_id=share_id).select_related("feature").first()
    if feature_share and feature_share.feature.geometry:
        return feature_share.feature.geometry.extent

    return None
