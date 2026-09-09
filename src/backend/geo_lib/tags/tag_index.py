"""Distinct tags, features-for-tag, and existence checks on FeatureTag."""

from __future__ import annotations

from django.db.models import Count, QuerySet

from api.models import FeatureStore, FeatureTag
from geo_lib.contracts.envelope import ListPage
from geo_lib.perf.list_projection import ListProjection
from geo_lib.processing.tagging.const_strings import get_tag_priority
from geo_lib.tags.scope import NAMESPACE_SYSTEM, NAMESPACE_USER, index_scope

_projection = ListProjection()


def _scope_filter(qs: QuerySet, scope: str | None) -> QuerySet:
    scoped = index_scope(scope)
    if scoped is None:
        return qs.filter(scope__isnull=True)
    return qs.filter(scope=scoped)


class TagIndex:
    @staticmethod
    def catalog(
        user_id: int,
        *,
        scope: str | None = None,
        search: str = '',
        page: int = 1,
        page_size: int = 10,
    ) -> ListPage:
        qs = _scope_filter(FeatureTag.objects.filter(user_id=user_id), scope)
        if search:
            qs = qs.filter(tag_key__icontains=search)

        grouped = list(
            qs.values('tag_key', 'namespace').annotate(count=Count('feature_id', distinct=True))
        )

        def sort_key(row: dict) -> tuple:
            kind = row['namespace']
            tag = row['tag_key']
            priority = get_tag_priority(tag) if kind == NAMESPACE_SYSTEM else 0
            # user first, then system by priority (0 last), then name
            kind_order = 0 if kind == NAMESPACE_USER else 1
            priority_order = 99 if priority == 0 and kind == NAMESPACE_SYSTEM else priority
            return (kind_order, priority_order, tag.lower())

        grouped.sort(key=sort_key)
        total = len(grouped)
        start = (page - 1) * page_size
        page_rows = grouped[start:start + page_size]
        items = [
            {
                'name': row['tag_key'],
                'kind': row['namespace'],
                'count': row['count'],
            }
            for row in page_rows
        ]
        return ListPage.of(items, page, page_size, total)

    @staticmethod
    def user_names(user_id: int, *, prefix: str = '', scope: str | None = None) -> list[str]:
        qs = _scope_filter(
            FeatureTag.objects.filter(user_id=user_id, namespace=NAMESPACE_USER),
            scope,
        )
        if prefix:
            qs = qs.filter(tag_key__istartswith=prefix)
        return list(qs.values_list('tag_key', flat=True).distinct().order_by('tag_key'))

    @staticmethod
    def exists(user_id: int, tag_key: str, *, scope: str | None = None) -> bool:
        return _scope_filter(
            FeatureTag.objects.filter(user_id=user_id, tag_key=tag_key),
            scope,
        ).exists()

    @staticmethod
    def features_for_tag(
        user_id: int,
        tag_key: str,
        *,
        scope: str | None = None,
        page: int = 1,
        page_size: int = 10,
    ) -> ListPage:
        feature_ids = list(
            _scope_filter(
                FeatureTag.objects.filter(user_id=user_id, tag_key=tag_key),
                scope,
            ).values_list('feature_id', flat=True).distinct()
        )
        qs = FeatureStore.objects.filter(id__in=feature_ids).order_by('id')
        total = qs.count()
        start = (page - 1) * page_size
        page_features = list(qs[start:start + page_size])
        items = []
        for feature in page_features:
            properties = feature.geojson.get('properties') if isinstance(feature.geojson, dict) else {}
            geometry = feature.geojson.get('geometry') if isinstance(feature.geojson, dict) else {}
            items.append(_projection.project(feature.id, properties, geometry))
        return ListPage.of(items, page, page_size, total)
