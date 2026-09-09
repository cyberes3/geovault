"""AND/OR + exact/prefix matcher over user ∪ system tags."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from django.db.models import Exists, OuterRef, Q

from api.models import FeatureTag
from geo_lib.tags.scope import index_scope
from geo_lib.tags.tag_set import TagSet, normalize_tag_text

MatchMode = Literal['AND', 'OR']

FEATURE_TAG_TABLE = 'api_featuretag'


class TagQueryError(ValueError):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def parse_match_mode(raw: str | None) -> MatchMode:
    mode = (raw or 'AND').upper()
    if mode not in ('AND', 'OR'):
        raise TagQueryError('match_mode must be either AND or OR')
    return mode  # type: ignore[return-value]


@dataclass(frozen=True)
class TagQuery:
    tags: tuple[str, ...]
    match_mode: MatchMode = 'AND'
    prefix: bool = True
    scope: str | None = None
    contains: bool = False

    @classmethod
    def parse(
        cls,
        tags: list[str],
        match_mode: str = 'AND',
        *,
        prefix: bool = True,
        scope: str | None = None,
    ) -> TagQuery:
        cleaned = tuple(
            normalize_tag_text(tag) for tag in tags if isinstance(tag, str) and tag.strip()
        )
        if not cleaned:
            raise TagQueryError('At least one tag is required')
        return cls(
            tags=cleaned,
            match_mode=parse_match_mode(match_mode),
            prefix=prefix,
            scope=index_scope(scope),
        )

    @classmethod
    def parse_search(cls, query: str, *, scope: str | None = None) -> TagQuery:
        cleaned = normalize_tag_text(query)
        if not cleaned:
            raise TagQueryError('Search text is required')
        return cls(
            tags=(cleaned,),
            match_mode='OR',
            prefix=False,
            contains=True,
            scope=index_scope(scope),
        )

    def matches(self, tag_set: TagSet) -> bool:
        haystack = tag_set.union()
        checks = [self._tag_matches(tag, haystack) for tag in self.tags]
        if self.match_mode == 'AND':
            return all(checks)
        return any(checks)

    def _tag_matches(self, needle: str, haystack: tuple[str, ...]) -> bool:
        if self.contains:
            return any(needle in item for item in haystack)
        if self.prefix and needle.endswith(':'):
            return any(item.startswith(needle) for item in haystack)
        return needle in haystack

    def to_sql_predicate(self, *, feature_id_sql: str = 'id', tag_table: str = FEATURE_TAG_TABLE) -> tuple[str, list[Any]]:
        if not self.tags:
            return '', []

        conditions: list[str] = []
        params: list[Any] = []
        scope_sql, scope_params = self._scope_sql()

        for tag in self.tags:
            if self.contains:
                key_sql = 'ft.tag_key ILIKE %s'
                key_params: list[Any] = [f'%{tag}%']
            elif self.prefix and tag.endswith(':'):
                key_sql = 'ft.tag_key LIKE %s'
                key_params = [f'{tag}%']
            else:
                key_sql = 'ft.tag_key = %s'
                key_params = [tag]
            conditions.append(
                f"""EXISTS (
                    SELECT 1 FROM {tag_table} ft
                    WHERE ft.feature_id = {feature_id_sql}
                      AND {scope_sql}
                      AND {key_sql}
                )"""
            )
            params.extend([*scope_params, *key_params])

        join_op = ' AND ' if self.match_mode == 'AND' else ' OR '
        return f'({join_op.join(conditions)})', params

    def to_sql_clause(self, *, feature_id_sql: str = 'id', tag_table: str = FEATURE_TAG_TABLE) -> tuple[str, list[Any]]:
        predicate, params = self.to_sql_predicate(feature_id_sql=feature_id_sql, tag_table=tag_table)
        if not predicate:
            return '', []
        return f' AND {predicate}', params

    def to_django_q(self, user_id: int | None = None) -> Q:
        combined = Q()
        for tag in self.tags:
            subquery = FeatureTag.objects.filter(feature_id=OuterRef('pk'))
            if self.scope is None:
                subquery = subquery.filter(scope__isnull=True)
            else:
                subquery = subquery.filter(scope=self.scope)
            if user_id is not None:
                subquery = subquery.filter(user_id=user_id)
            if self.contains:
                subquery = subquery.filter(tag_key__icontains=tag)
            elif self.prefix and tag.endswith(':'):
                subquery = subquery.filter(tag_key__startswith=tag)
            else:
                subquery = subquery.filter(tag_key=tag)
            clause = Q(Exists(subquery))
            if not combined:
                combined = clause
            elif self.match_mode == 'AND':
                combined &= clause
            else:
                combined |= clause
        return combined

    def _scope_sql(self) -> tuple[str, list[Any]]:
        if self.scope is None:
            return 'ft.scope IS NULL', []
        return 'ft.scope = %s', [self.scope]
