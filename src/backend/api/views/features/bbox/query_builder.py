"""Django ORM query builders for bbox/collection/tag-scoped feature lookups."""
import uuid

from django.db.models import QuerySet

from api.models import FeatureStore, Collection
from geo_lib.collections.membership import CollectionMembership
from geo_lib.tags.tag_query import TagQuery, TagQueryError


def _build_collection_query(user_id: int, collection_id: uuid.UUID) -> QuerySet:
    try:
        collection = Collection.objects.get(id=collection_id, user_id=user_id)
    except Collection.DoesNotExist:
        return FeatureStore.objects.none()
    return CollectionMembership.resolve(collection)


def _build_base_query(user_id: int, tag: str | None = None, collection_id: uuid.UUID | None = None, scope: str | None = None) -> QuerySet:
    if collection_id is not None:
        return _build_collection_query(user_id, collection_id)

    base_query = FeatureStore.objects.owned_by(user_id).with_geometry()
    base_query = base_query.main_map() if scope is None else base_query.in_scope(scope)

    if tag:
        try:
            query = TagQuery.parse([tag], match_mode='OR', prefix=True, scope=scope)
            base_query = base_query.filter(query.to_django_q(user_id))
        except TagQueryError:
            return FeatureStore.objects.none()

    return base_query.order_by('id')
