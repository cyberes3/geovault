"""Live collection membership: tag rules OR pinned features, main_map only."""

from django.db.models import Q, QuerySet

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule, FeatureStore
from geo_lib.tags.tag_query import TagQuery, TagQueryError


class CollectionMembership:
    @staticmethod
    def resolve(collection: Collection) -> QuerySet:
        base = FeatureStore.objects.owned_by(collection.user).main_map().with_geometry()
        rule_tags = list(
            CollectionTagRule.objects.filter(collection=collection).values_list('tag', flat=True)
        )
        pin_ids = list(
            CollectionFeatureMembership.objects.filter(collection=collection).values_list('feature_id', flat=True)
        )

        tag_q = Q()
        if rule_tags:
            try:
                tag_q = TagQuery.parse(rule_tags, match_mode='OR', prefix=False, scope=None).to_django_q(
                    collection.user_id
                )
            except TagQueryError:
                tag_q = Q()

        pin_q = Q(id__in=pin_ids) if pin_ids else Q()
        if not tag_q and not pin_q:
            return base.none()
        if tag_q and pin_q:
            return base.filter(tag_q | pin_q).distinct().order_by('id')
        if tag_q:
            return base.filter(tag_q).distinct().order_by('id')
        return base.filter(pin_q).order_by('id')

    @staticmethod
    def feature_ids(collection: Collection) -> set[int]:
        return set(CollectionMembership.resolve(collection).values_list('id', flat=True))

    @staticmethod
    def count(collection: Collection) -> int:
        return CollectionMembership.resolve(collection).count()

    @staticmethod
    def counts_for(collections: list[Collection]) -> dict:
        return {collection.id: CollectionMembership.count(collection) for collection in collections}
