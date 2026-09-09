"""Helpers for creating ImportQueue rows with ImportDraftFeature drafts."""

from django.contrib.gis.geos import Point

from api.models import ImportDraftFeature, ImportQueue
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash


def create_draft(queue, feature, spatial_index=0, **kwargs):
    properties = feature.setdefault('properties', {})
    digest = properties.get('geojson_hash') or generate_geojson_hash(feature)
    properties['geojson_hash'] = digest
    geometry = feature.get('geometry') or {}
    coords = geometry.get('coordinates') or []
    geos = None
    if geometry.get('type') == 'Point' and len(coords) >= 2:
        geos = Point(coords[0], coords[1], coords[2] if len(coords) > 2 else 0.0, srid=4326)
    fields = {
        'queue': queue,
        'user': queue.user,
        'geojson': feature,
        'geojson_hash': digest,
        'geometry': geos,
        'name': properties.get('name') or '',
        'geometry_type': geometry.get('type') or '',
        'spatial_index': spatial_index,
    }
    fields.update(kwargs)
    return ImportDraftFeature.objects.create(**fields)


def _normalize_verdict(dup):
    match_type = dup.get('match_type')
    kind = str(getattr(match_type, 'value', match_type) or ImportDraftFeature.VERDICT_HASH)
    source = dup.get('source')
    source = str(getattr(source, 'value', source) or 'feature_store')
    scope = (
        ImportDraftFeature.SCOPE_LIBRARY
        if source == 'feature_store'
        else ImportDraftFeature.SCOPE_DRAFT_QUEUE
    )
    return kind, scope


def queue_with_drafts(user=None, features=None, **kwargs):
    user = kwargs.pop('user', user)
    features = kwargs.pop('features', features)
    skipped_ids = kwargs.pop('skipped', None)
    skip_intent = kwargs.pop('skip_intent', None)
    duplicate_features = kwargs.pop('duplicate_features', None)
    kwargs.setdefault('original_filename', 'test.kml')
    kwargs.setdefault('raw_file', '<kml></kml>')
    kwargs.setdefault('queue_status', ImportQueue.STATUS_READY)
    queue = ImportQueue.objects.create(user=user, **kwargs)

    verdict_by_hash = {}
    for dup in duplicate_features or []:
        feature = dup.get('feature') or {}
        digest = (feature.get('properties') or {}).get('geojson_hash')
        if digest:
            verdict_by_hash[digest] = _normalize_verdict(dup)

    for index, feature in enumerate(features or []):
        properties = feature.setdefault('properties', {})
        digest = properties.get('geojson_hash') or generate_geojson_hash(feature)
        properties['geojson_hash'] = digest
        kind, scope = verdict_by_hash.get(
            digest,
            (ImportDraftFeature.VERDICT_NONE, ImportDraftFeature.SCOPE_NONE),
        )
        create_draft(
            queue,
            feature,
            spatial_index=index,
            verdict_kind=kind,
            verdict_scope=scope,
        )

    if isinstance(skip_intent, SkipIntent):
        skip_intent = skip_intent.to_stored()
    if skip_intent is None:
        verdicts = {}
        for digest, (kind, scope) in verdict_by_hash.items():
            if kind not in (VerdictKind.HASH.value, VerdictKind.GEOMETRY.value):
                continue
            verdicts[digest] = DuplicateVerdict(
                kind=VerdictKind(kind),
                scope=(
                    VerdictScope.LIBRARY
                    if scope == ImportDraftFeature.SCOPE_LIBRARY
                    else VerdictScope.DRAFT_QUEUE
                ),
            )
        intent = SkipIntent.from_verdicts(verdicts)
        if skipped_ids:
            intent.user_skipped.update(skipped_ids)
        skip_intent = intent.to_stored()

    queue.skip_intent = skip_intent
    queue.duplicate_counts = {
        'hash': sum(1 for kind, _scope in verdict_by_hash.values() if kind == 'hash'),
        'geometry': sum(1 for kind, _scope in verdict_by_hash.values() if kind == 'geometry'),
    }
    queue.save(update_fields=['skip_intent', 'duplicate_counts'])
    return queue


def draft_geojson(queue):
    return list(
        ImportDraftFeature.objects.filter(queue=queue)
        .order_by('spatial_index', 'id')
        .values_list('geojson', flat=True)
    )


def skipped_hashes(queue):
    return SkipIntent.from_stored(queue.skip_intent).to_wire()['skipped']


def draft_verdict_count(queue, kind=None):
    qs = ImportDraftFeature.objects.filter(queue=queue).exclude(
        verdict_kind=ImportDraftFeature.VERDICT_NONE
    )
    if kind:
        qs = qs.filter(verdict_kind=kind)
    return qs.count()


def draft_duplicate_infos(queue):
    infos = []
    drafts = (
        ImportDraftFeature.objects.filter(queue=queue)
        .exclude(verdict_kind=ImportDraftFeature.VERDICT_NONE)
        .order_by('spatial_index', 'id')
    )
    for draft in drafts:
        source = (
            'feature_store'
            if draft.verdict_scope == ImportDraftFeature.SCOPE_LIBRARY
            else 'cross_queue'
        )
        existing = []
        match_id = draft.match_feature_store_id or draft.match_queue_id
        if match_id is not None:
            existing.append({
                'id': match_id,
                'name': draft.match_name,
                'type': draft.match_geometry_type,
                'feature_index': draft.match_spatial_index,
            })
        infos.append({
            'feature': draft.geojson,
            'match_type': draft.verdict_kind,
            'source': source,
            'existing_features': existing,
        })
    return infos
