"""JobRuntime cancel guard and ImportDraftStore hide_duplicates paging."""

import time
from types import SimpleNamespace

import pytest
from django.contrib.auth import get_user_model

from api.models import ImportQueue
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.importing.draft_store import ImportDraftStore
from geo_lib.importing.errors import ImportCancelled
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus

User = get_user_model()


def _point(name: str, lon: float, lat: float) -> dict:
    feature = {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat, 0.0]},
        'properties': {'name': name},
    }
    feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
    return feature


def _runtime(job=None) -> JobRuntime:
    tracker = SimpleNamespace(get_job=lambda _job_id: job, update_job_status=lambda *a, **k: None)
    return JobRuntime(
        status_tracker=tracker,
        job_id='job-1',
        user_id=1,
        queue_id=1,
        phase=JobPhase.PROCESS,
        started_at=time.time(),
        ceiling_seconds=600,
    )


class TestJobRuntime:
    def test_missing_job_is_not_cancelled(self):
        runtime = _runtime(None)
        assert runtime.check_cancelled() is False

    def test_canceled_job_is_cancelled(self):
        runtime = _runtime(SimpleNamespace(status=ProcessingStatus.CANCELED))
        assert runtime.check_cancelled() is True
        with pytest.raises(ImportCancelled):
            runtime.checkpoint('during persist')


@pytest.mark.django_db
def test_hide_duplicates_pages_only_importable_rows():
    user = User.objects.create_user(username='draft-hide', email='draft-hide@example.com', password='x')
    queue = ImportQueue.objects.create(
        user=user,
        original_filename='hide.kml',
        raw_file='<kml></kml>',
        queue_status=ImportQueue.STATUS_READY,
    )
    none_f = _point('keep', 0.0, 0.0)
    hash_f = _point('hash', 1.0, 1.0)
    geom_f = _point('geom', 2.0, 2.0)
    restored_f = _point('restored', 3.0, 3.0)
    verdicts = {
        none_f['properties']['geojson_hash']: DuplicateVerdict.none(),
        hash_f['properties']['geojson_hash']: DuplicateVerdict(
            kind=VerdictKind.HASH, scope=VerdictScope.LIBRARY
        ),
        geom_f['properties']['geojson_hash']: DuplicateVerdict(
            kind=VerdictKind.GEOMETRY, scope=VerdictScope.LIBRARY
        ),
        restored_f['properties']['geojson_hash']: DuplicateVerdict(
            kind=VerdictKind.GEOMETRY, scope=VerdictScope.LIBRARY
        ),
    }
    intent = SkipIntent.from_verdicts(verdicts)
    intent.user_restored_geometry.add(restored_f['properties']['geojson_hash'])
    intent.auto_skipped_geometry.discard(restored_f['properties']['geojson_hash'])
    store = ImportDraftStore(queue)
    store.replace_features([none_f, hash_f, geom_f, restored_f], verdicts, intent)
    hidden = store.page(1, 50, hide_duplicates=True)
    names = [item.name for item in hidden.items]
    assert 'keep' in names
    assert 'restored' in names
    assert 'hash' not in names
    assert 'geom' not in names


@pytest.mark.django_db
def test_apply_skipped_payload_uses_geometry_key():
    from geo_lib.importing.apply_plan import ApplyPlan
    from geo_lib.importing.stages.apply import apply_queue

    user = User.objects.create_user(username='apply-skip', email='apply-skip@example.com', password='x')
    queue = ImportQueue.objects.create(
        user=user,
        original_filename='skip.kml',
        raw_file='<kml></kml>',
        queue_status=ImportQueue.STATUS_READY,
    )
    keep_f = _point('keep-me', 6.0, 7.0)
    geom_f = _point('geom-skip', 4.0, 5.0)
    verdicts = {
        keep_f['properties']['geojson_hash']: DuplicateVerdict.none(),
        geom_f['properties']['geojson_hash']: DuplicateVerdict(
            kind=VerdictKind.GEOMETRY, scope=VerdictScope.LIBRARY
        ),
    }
    intent = SkipIntent.from_verdicts(verdicts)
    ImportDraftStore(queue).replace_features([keep_f, geom_f], verdicts, intent)
    plan = ApplyPlan.from_queue(queue)
    runtime = _runtime(SimpleNamespace(status=ProcessingStatus.PROCESSING))
    result = apply_queue(plan, runtime)
    assert result['imported'] == 1
    assert result['duplicates_skipped']['geometry'] == [geom_f['properties']['geojson_hash']]
    assert 'coord' not in result['duplicates_skipped']


@pytest.mark.django_db
def test_apply_rejects_canceled_queue():
    from geo_lib.importing.apply_plan import ApplyPlan
    from geo_lib.importing.errors import ImportStageError
    from geo_lib.importing.stages.apply import apply_queue

    user = User.objects.create_user(username='apply-cancel', email='apply-cancel@example.com', password='x')
    queue = ImportQueue.objects.create(
        user=user,
        original_filename='canceled.kml',
        raw_file='<kml></kml>',
        queue_status=ImportQueue.STATUS_CANCELED,
    )
    keep_f = _point('keep-me', 6.0, 7.0)
    ImportDraftStore(queue).replace_features(
        [keep_f],
        {keep_f['properties']['geojson_hash']: DuplicateVerdict.none()},
        SkipIntent(),
    )
    plan = ApplyPlan.from_queue(queue)
    runtime = _runtime(SimpleNamespace(status=ProcessingStatus.PROCESSING))
    with pytest.raises(ImportStageError, match='canceled'):
        apply_queue(plan, runtime)
