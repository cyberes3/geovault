from typing import Any

from api.models import DatabaseLogging, FeatureStore, ImportDraftFeature, ImportQueue
from geo_lib.duplicates.adapters.feature_store import build_library_hash_index
from geo_lib.duplicates.verdict import VerdictKind
from geo_lib.importing.apply_plan import ApplyPlan
from geo_lib.importing.context import ImportContext
from geo_lib.importing.draft_store import ImportDraftStore, draft_to_verdict
from geo_lib.importing.errors import ImportBatchWriteError, ImportStageError
from geo_lib.importing.geometry import feature_to_geos
from geo_lib.importing.runtime import JobRuntime
from geo_lib.perf.batch_writer import BatchWriteError, BatchWriter
from geo_lib.processing.hooks import execute_import_hooks
from geo_lib.processing.import_operations.styling import apply_bulk_operations, strip_icon_properties
from geo_lib.processing.import_operations.websocket import broadcast_item_imported


class ApplyStage:
    name = 'apply'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        raise ImportStageError("ApplyStage.run is not used; call apply_queue")


def apply_queue(plan: ApplyPlan, runtime: JobRuntime) -> dict[str, Any]:
    runtime.checkpoint('before apply')
    queue = ImportQueue.objects.get(id=plan.queue_id, user_id=plan.user_id)
    if queue.imported or queue.queue_status == ImportQueue.STATUS_IMPORTED:
        raise ImportStageError('Item already imported')
    if queue.queue_status in (ImportQueue.STATUS_CANCELED, ImportQueue.STATUS_FAILED, ImportQueue.STATUS_PROCESSING):
        raise ImportStageError(f'Item cannot be imported ({queue.queue_status})')
    store = ImportDraftStore(queue)
    all_drafts = list(ImportDraftFeature.objects.filter(queue=queue).order_by('spatial_index', 'id'))
    skipped_hash: list[str] = []
    skipped_geometry: list[str] = []
    selected: list[ImportDraftFeature] = []
    for draft in all_drafts:
        verdict = draft_to_verdict(draft)
        if plan.skip_intent.should_skip(draft.geojson_hash, verdict):
            if verdict.kind == VerdictKind.HASH:
                skipped_hash.append(draft.geojson_hash)
            elif verdict.kind == VerdictKind.GEOMETRY:
                skipped_geometry.append(draft.geojson_hash)
            continue
        selected.append(draft)
    drafts = selected
    library_hashes = build_library_hash_index(plan.user_id)
    features_to_create: list[FeatureStore] = []
    for draft in drafts:
        digest = draft.geojson_hash
        if library_hashes.lookup(digest) is not None:
            skipped_hash.append(digest)
            continue
        feature = dict(draft.geojson)
        if plan.bulk_operations:
            processed = apply_bulk_operations([feature], plan.bulk_operations)
            feature = processed[0] if processed else feature
        if not plan.import_custom_icons:
            feature = strip_icon_properties(feature)
        features_to_create.append(FeatureStore(
            geojson=feature,
            geojson_hash=digest,
            geometry=feature_to_geos(feature) or draft.geometry,
            source=queue,
            user_id=plan.user_id,
            scope=None,
        ))
    runtime.checkpoint('before apply write')
    try:
        created = BatchWriter(FeatureStore).write(features_to_create)
    except BatchWriteError as exc:
        colliding = [
            feature.geojson_hash
            for feature in features_to_create
            if library_hashes.lookup(feature.geojson_hash) is not None or _hash_exists(plan.user_id, feature.geojson_hash)
        ]
        remaining = [feature for feature in features_to_create if feature.geojson_hash not in set(colliding)]
        if colliding and remaining:
            try:
                created = BatchWriter(FeatureStore).write(remaining)
            except BatchWriteError as retry_exc:
                raise ImportBatchWriteError(retry_exc.message) from retry_exc
            skipped_hash.extend(colliding)
        elif colliding and not remaining:
            created = []
            skipped_hash.extend(colliding)
        else:
            raise ImportBatchWriteError(exc.message) from exc
    if not created:
        raise ImportStageError("No features were imported")
    queue.imported = True
    queue.queue_status = ImportQueue.STATUS_IMPORTED
    if queue.log_id:
        DatabaseLogging.objects.filter(log_id=queue.log_id).delete()
        queue.log_id = None
    store.clear()
    queue.save()
    execute_import_hooks(queue, plan.user_id, created)
    broadcast_item_imported(plan.user_id, queue.id)
    return {
        'imported': len(created),
        'duplicates_skipped': {
            'hash': skipped_hash,
            'geometry': skipped_geometry,
        },
    }


def _hash_exists(user_id: int, geojson_hash: str) -> bool:
    return FeatureStore.objects.owned_by(user_id).main_map().filter(geojson_hash=geojson_hash).exists()
