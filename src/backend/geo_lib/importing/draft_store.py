from dataclasses import dataclass
from typing import Any, Iterable

from django.db.models import Q, QuerySet

from api.models import ImportDraftFeature, ImportQueue
from geo_lib.duplicates.identity import FeatureRef
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.duplicates.wire import DuplicateCountsWire, DuplicateVerdictWire
from geo_lib.importing.geometry import feature_to_geos, spatial_sort_tuple
from geo_lib.perf.batch_writer import BatchWriter
from geo_lib.perf.query_budget import QueryBudget


@dataclass
class DraftPage:
    items: list[ImportDraftFeature]
    page: int
    page_size: int
    total_items: int
    total_pages: int
    draft_count: int
    skip_intent: SkipIntent
    duplicate_counts: DuplicateCountsWire


class ImportDraftStore:
    def __init__(self, queue: ImportQueue):
        self.queue = queue

    def replace_features(
        self,
        features: list[dict[str, Any]],
        verdicts: dict[str, DuplicateVerdict],
        skip_intent: SkipIntent,
    ) -> int:
        ImportDraftFeature.objects.filter(queue=self.queue).delete()
        ordered = sorted(enumerate(features), key=lambda item: (*spatial_sort_tuple(item[1]), item[0]))
        drafts: list[ImportDraftFeature] = []
        for spatial_index, (file_index, feature) in enumerate(ordered):
            properties = feature.get('properties') or {}
            digest = properties.get('geojson_hash') or ''
            geometry = feature.get('geometry') or {}
            verdict = verdicts.get(digest, DuplicateVerdict.none())
            match = verdict.matches[0] if verdict.matches else None
            sort_key = spatial_sort_tuple(feature)
            drafts.append(ImportDraftFeature(
                queue=self.queue,
                user_id=self.queue.user_id,
                geojson=feature,
                geojson_hash=digest,
                geometry=feature_to_geos(feature),
                name=properties.get('name') or '',
                geometry_type=geometry.get('type') or '',
                sort_lat=-sort_key[0],
                sort_lon=sort_key[1],
                spatial_index=spatial_index,
                file_index=file_index,
                verdict_kind=verdict.kind.value,
                verdict_scope=verdict.scope.value,
                match_feature_store_id=match.feature_store_id if match else None,
                match_queue_id=match.draft_queue_id if match else None,
                match_spatial_index=match.spatial_index if match else None,
                match_name=match.name if match else '',
                match_geometry_type=match.geometry_type if match else '',
            ))
        BatchWriter(ImportDraftFeature).write(drafts)
        hash_count = sum(1 for verdict in verdicts.values() if verdict.kind == VerdictKind.HASH)
        geometry_count = sum(1 for verdict in verdicts.values() if verdict.kind == VerdictKind.GEOMETRY)
        self.queue.skip_intent = skip_intent.to_stored()
        self.queue.duplicate_counts = {'hash': hash_count, 'geometry': geometry_count}
        self.queue.save(update_fields=['skip_intent', 'duplicate_counts'])
        return len(drafts)

    def rewrite_verdicts(
        self,
        verdicts: dict[str, DuplicateVerdict],
        skip_intent: SkipIntent,
    ) -> None:
        drafts = list(ImportDraftFeature.objects.filter(queue=self.queue))
        for draft in drafts:
            verdict = verdicts.get(draft.geojson_hash, DuplicateVerdict.none())
            match = verdict.matches[0] if verdict.matches else None
            draft.verdict_kind = verdict.kind.value
            draft.verdict_scope = verdict.scope.value
            draft.match_feature_store_id = match.feature_store_id if match else None
            draft.match_queue_id = match.draft_queue_id if match else None
            draft.match_spatial_index = match.spatial_index if match else None
            draft.match_name = match.name if match else ''
            draft.match_geometry_type = match.geometry_type if match else ''
        ImportDraftFeature.objects.bulk_update(
            drafts,
            [
                'verdict_kind', 'verdict_scope', 'match_feature_store_id',
                'match_queue_id', 'match_spatial_index', 'match_name', 'match_geometry_type',
            ],
        )
        hash_count = sum(1 for verdict in verdicts.values() if verdict.kind == VerdictKind.HASH)
        geometry_count = sum(1 for verdict in verdicts.values() if verdict.kind == VerdictKind.GEOMETRY)
        self.queue.skip_intent = skip_intent.to_stored()
        self.queue.duplicate_counts = {'hash': hash_count, 'geometry': geometry_count}
        self.queue.save(update_fields=['skip_intent', 'duplicate_counts'])

    def queryset(self, hide_duplicates: bool = False) -> QuerySet:
        qs = ImportDraftFeature.objects.filter(queue=self.queue).order_by('spatial_index', 'id')
        if not hide_duplicates:
            return qs
        intent = SkipIntent.from_stored(self.queue.skip_intent)
        hidden_geometry = Q(verdict_kind=ImportDraftFeature.VERDICT_GEOMETRY)
        if intent.user_restored_geometry:
            hidden_geometry &= ~Q(geojson_hash__in=list(intent.user_restored_geometry))
        return qs.exclude(verdict_kind=ImportDraftFeature.VERDICT_HASH).exclude(hidden_geometry)

    def page(
        self,
        page: int,
        page_size: int,
        hide_duplicates: bool = False,
        budget: QueryBudget | None = None,
    ) -> DraftPage:
        if page < 1:
            page = 1
        if budget is not None:
            page_size = budget.clamp(page_size)
        elif page_size < 1:
            page_size = 50
        qs = self.queryset(hide_duplicates=hide_duplicates)
        total_items = qs.count()
        total_pages = (total_items + page_size - 1) // page_size if total_items else 0
        start = (page - 1) * page_size
        items = list(qs[start:start + page_size])
        counts = self.queue.duplicate_counts or {}
        return DraftPage(
            items=items,
            page=page,
            page_size=page_size,
            total_items=total_items,
            total_pages=total_pages,
            draft_count=self.feature_count(),
            skip_intent=SkipIntent.from_stored(self.queue.skip_intent),
            duplicate_counts=DuplicateCountsWire(
                hash=int(counts.get('hash') or 0),
                geometry=int(counts.get('geometry') or 0),
            ),
        )

    def candidates_for_apply(self, skip_intent: SkipIntent) -> list[ImportDraftFeature]:
        drafts = list(ImportDraftFeature.objects.filter(queue=self.queue).order_by('spatial_index', 'id'))
        selected: list[ImportDraftFeature] = []
        for draft in drafts:
            if skip_intent.should_skip(draft.geojson_hash, draft_to_verdict(draft)):
                continue
            selected.append(draft)
        return selected

    def feature_count(self) -> int:
        return ImportDraftFeature.objects.filter(queue=self.queue).count()

    def clear(self) -> None:
        ImportDraftFeature.objects.filter(queue=self.queue).delete()


def draft_to_verdict(draft: ImportDraftFeature) -> DuplicateVerdict:
    kind = VerdictKind(draft.verdict_kind)
    scope = VerdictScope(draft.verdict_scope)
    matches: tuple[FeatureRef, ...] = ()
    if kind != VerdictKind.NONE:
        matches = (FeatureRef(
            feature_store_id=draft.match_feature_store_id,
            draft_queue_id=draft.match_queue_id,
            spatial_index=draft.match_spatial_index,
            name=draft.match_name,
            geometry_type=draft.match_geometry_type,
        ),)
    return DuplicateVerdict(kind=kind, scope=scope, matches=matches)


def draft_to_wire_feature(draft: ImportDraftFeature) -> dict[str, Any]:
    feature = dict(draft.geojson)
    feature['duplicate_verdict'] = DuplicateVerdictWire.from_verdict(draft_to_verdict(draft)).model_dump(mode='json')
    return feature


def serialize_page(page: DraftPage) -> dict[str, Any]:
    return {
        'data': [draft_to_wire_feature(item) for item in page.items],
        'pagination': {
            'page': page.page,
            'page_size': page.page_size,
            'total_features': page.total_items,
            'total_pages': page.total_pages,
            'has_next': page.page < page.total_pages,
            'has_previous': page.page > 1,
        },
        'duplicate_counts': page.duplicate_counts.model_dump(mode='json'),
        'skip_intent': page.skip_intent.to_wire(),
        'feature_count': page.draft_count,
    }


def iter_draft_geojson(drafts: Iterable[ImportDraftFeature]) -> list[dict[str, Any]]:
    return [draft.geojson for draft in drafts]
