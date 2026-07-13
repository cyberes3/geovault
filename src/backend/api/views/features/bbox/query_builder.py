"""Django ORM query builders for bbox/collection/tag-scoped feature lookups."""
import uuid

from django.db.models import QuerySet, Q

from api.models import FeatureStore, Collection
from api.views.collections.utils import get_collection_feature_ids


def _build_collection_query(user_id: int, collection_id: uuid.UUID) -> QuerySet:
    """
    Build query for features in a collection.
    Returns features matching ANY of the collection's tags (OR logic) OR in feature_ids.

    Args:
        user_id: User ID to filter features by
        collection_id: Collection ID to filter features by

    Returns:
        QuerySet ready for further filtering
    """
    try:
        collection = Collection.objects.get(id=collection_id, user_id=user_id)
    except Collection.DoesNotExist:
        # Return empty queryset if collection doesn't exist
        return FeatureStore.objects.none()

    # Get feature IDs using the shared function to avoid code duplication
    feature_ids_set = get_collection_feature_ids(collection)

    # Start with base user filter
    base_query = FeatureStore.objects.owned_by(user_id).with_geometry()

    # Filter by the combined set of feature IDs
    if feature_ids_set:
        return base_query.filter(id__in=feature_ids_set).order_by('id')
    else:
        # No features match the collection criteria
        return FeatureStore.objects.none()


def _build_base_query(user_id: int, tag: str | None = None, collection_id: uuid.UUID | None = None, scope: str | None = None) -> QuerySet:
    """
    Build base query for features with user filter, geometry exclusion, optional tag filter,
    optional collection filter, optional scope filter, and ordering.

    Args:
        user_id: User ID to filter features by
        tag: Optional tag to filter features by (if None, no tag filter is applied)
        collection_id: Optional collection ID to filter features by (if None, no collection filter is applied)
        scope: Optional scope to filter features by (if None, defaults to filtering for scope__isnull=True)

    Returns:
        QuerySet ready for further filtering
    """
    # Collection filter takes precedence if provided. Collections can contain features from any scope.
    if collection_id is not None:
        return _build_collection_query(user_id, collection_id)

    base_query = FeatureStore.objects.owned_by(user_id).with_geometry()

    # Filter by scope (default to main map scope which is null)
    base_query = base_query.main_map() if scope is None else base_query.in_scope(scope)

    # Add tag filter if provided (search in both tags and system_tags)
    if tag:
        base_query = base_query.filter(
            Q(geojson__properties__tags__contains=[tag]) |
            Q(geojson__properties__system_tags__contains=[tag])
        )

    # Order by id to ensure consistent results when slicing
    return base_query.order_by('id')
