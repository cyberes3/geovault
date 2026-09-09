"""
Apply-path tests for hash / geometry / cross-queue duplicates.

Uses ImportDraftFeature verdicts + SkipIntent + apply_queue (ImportPipeline.apply).
"""
import time
from types import SimpleNamespace

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase

from api.models import FeatureStore, ImportQueue
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.importing.apply_plan import ApplyPlan
from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.runtime import JobPhase, JobRuntime
from geo_lib.importing.stages.apply import apply_queue
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus
from tests.test_utils.import_queue import queue_with_drafts

User = get_user_model()


def _runtime():
    tracker = SimpleNamespace(
        get_job=lambda _job_id: SimpleNamespace(status=ProcessingStatus.PROCESSING),
        update_job_status=lambda *a, **k: None,
    )
    return JobRuntime(
        status_tracker=tracker,
        job_id='job-1',
        user_id=1,
        queue_id=1,
        phase=JobPhase.APPLY,
        started_at=time.time(),
        ceiling_seconds=600,
    )


def _apply(queue, extra_skipped=None, extra_restored=None):
    plan = ApplyPlan.from_queue(
        queue,
        extra_user_skipped=extra_skipped,
        extra_user_restored=extra_restored,
    )
    return apply_queue(plan, _runtime())


def _apply_expect_empty(queue, **kwargs):
    try:
        _apply(queue, **kwargs)
    except ImportStageError as exc:
        if 'No features were imported' in str(exc):
            return
        raise
    raise AssertionError('expected ImportStageError when no features import')


def _seed_library(user, feature):
    digest = feature['properties']['geojson_hash']
    coords = feature['geometry']['coordinates']
    return FeatureStore.objects.create(
        user=user,
        geojson=feature,
        geojson_hash=digest,
        geometry=Point(coords[0], coords[1], 0, srid=4326),
    )


def _point(name, lon, lat, description='A test point'):
    feature = {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat]},
        'properties': {'name': name, 'description': description},
    }
    feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
    return feature


class TestSingleImportWithDuplicates(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )
        self.base_feature = _point('Test Point', -122.4194, 37.7749)
        self.geom_duplicate = _point('Different Name', -122.4194, 37.7749, 'Different description')

    def test_single_import_auto_skips_geometry_duplicates(self):
        _seed_library(self.user, self.base_feature)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='test.kml',
            features=[self.geom_duplicate],
            duplicate_features=[{
                'source': 'feature_store',
                'match_type': 'geometry',
                'feature': self.geom_duplicate,
                'existing_features': [],
            }],
            imported=False,
        )
        _apply_expect_empty(import_item)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)

    def test_single_import_empty_user_skipped_still_auto_skips_geometry(self):
        _seed_library(self.user, self.base_feature)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='test2.kml',
            features=[self.geom_duplicate],
            duplicate_features=[{
                'source': 'feature_store',
                'match_type': 'geometry',
                'feature': self.geom_duplicate,
                'existing_features': [],
            }],
            skipped=[],
            imported=False,
        )
        _apply_expect_empty(import_item)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)

    def test_single_import_restored_geometry_is_imported(self):
        _seed_library(self.user, self.base_feature)
        digest = self.geom_duplicate['properties']['geojson_hash']
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='restore.kml',
            features=[self.geom_duplicate],
            duplicate_features=[{
                'source': 'feature_store',
                'match_type': 'geometry',
                'feature': self.geom_duplicate,
                'existing_features': [],
            }],
            skip_intent=SkipIntent(
                auto_skipped_geometry=set(),
                user_restored_geometry={digest},
            ).to_stored(),
            imported=False,
        )
        result = _apply(import_item)
        self.assertEqual(result['imported'], 1)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 2)

    def test_single_import_always_blocks_hash_duplicates(self):
        _seed_library(self.user, self.base_feature)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='test.kml',
            features=[self.base_feature],
            imported=False,
        )
        _apply_expect_empty(import_item)
        _apply_expect_empty(import_item, extra_skipped=[self.base_feature['properties']['geojson_hash']])
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)


class TestBulkImportWithDuplicates(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='bulk@example.com',
            password='testpass123',
            username='bulkuser',
        )
        self.base_feature = _point('Test Point', -122.4194, 37.7749)
        self.geom_duplicate = _point('Different Name', -122.4194, 37.7749, 'Different description')

    def test_bulk_import_auto_skips_geometry_duplicates(self):
        _seed_library(self.user, self.base_feature)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='bulk_test.kml',
            features=[self.geom_duplicate],
            duplicate_features=[{
                'source': 'feature_store',
                'match_type': 'geometry',
                'feature': self.geom_duplicate,
                'existing_features': [],
            }],
            skipped=[],
            imported=False,
        )
        _apply_expect_empty(import_item)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)

    def test_bulk_import_always_blocks_hash_duplicates(self):
        _seed_library(self.user, self.base_feature)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='bulk_hash_test.kml',
            features=[self.base_feature],
            imported=False,
        )
        _apply_expect_empty(import_item)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)


class TestCrossQueueDuplicatesInImport(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='crossqueue@example.com',
            password='testpass123',
            username='crossqueueuser',
        )
        self.feature = _point('Cross Queue Test', -122.5, 37.8, 'Test')

    def test_bulk_import_skips_cross_queue_geometry_duplicates(self):
        older_feature = dict(self.feature)
        older_feature['properties'] = dict(self.feature['properties'])
        older_queue = queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            features=[older_feature],
            imported=False,
        )
        newer_feature = _point('Different Name', -122.5, 37.8, 'Different')
        newer_queue = queue_with_drafts(
            user=self.user,
            original_filename='newer.kml',
            features=[newer_feature],
            duplicate_features=[{
                'source': 'cross_queue',
                'match_type': 'geometry',
                'feature': newer_feature,
                'existing_features': [{'id': older_queue.id}],
            }],
            imported=False,
        )
        _apply_expect_empty(newer_queue)
        self.assertFalse(ImportQueue.objects.get(id=newer_queue.id).imported)

    def test_bulk_import_blocks_cross_queue_hash_duplicates(self):
        queue_with_drafts(
            user=self.user,
            original_filename='older.kml',
            features=[self.feature],
            imported=False,
        )
        newer_queue = queue_with_drafts(
            user=self.user,
            original_filename='newer.kml',
            features=[self.feature],
            duplicate_features=[{
                'source': 'cross_queue',
                'match_type': 'hash',
                'feature': self.feature,
                'existing_features': [],
            }],
            imported=False,
        )
        _apply_expect_empty(newer_queue)


class TestManualSkipBehavior(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='skiptest@example.com',
            password='testpass123',
            username='skipuser',
        )

    def test_single_import_respects_manual_skips(self):
        feature1 = _point('Feature 1', -122.1, 37.7)
        feature2 = _point('Feature 2', -122.2, 37.8)
        feature3 = _point('Feature 3', -122.3, 37.9)
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='skip_test.kml',
            features=[feature1, feature2, feature3],
            skipped=[feature2['properties']['geojson_hash']],
            imported=False,
        )
        result = _apply(import_item)
        self.assertEqual(result['imported'], 2)
        names = set(
            FeatureStore.objects.filter(user=self.user).values_list('geojson__properties__name', flat=True)
        )
        self.assertEqual(names, {'Feature 1', 'Feature 3'})

    def test_geometry_duplicate_restore_imports(self):
        base_feature = _point('Base Feature', -122.7, 38.3)
        _seed_library(self.user, base_feature)
        geom_dup = _point('Geometry Duplicate', -122.7, 38.3)
        digest = geom_dup['properties']['geojson_hash']
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='restore_test.kml',
            features=[geom_dup],
            duplicate_features=[{
                'source': 'feature_store',
                'match_type': 'geometry',
                'feature': geom_dup,
                'existing_features': [],
            }],
            skip_intent=SkipIntent.from_verdicts({
                digest: DuplicateVerdict(kind=VerdictKind.GEOMETRY, scope=VerdictScope.LIBRARY),
            }),
            imported=False,
        )
        # from_verdicts auto-skips; apply extra restore like the apply API
        result = _apply(import_item, extra_restored=[digest])
        self.assertEqual(result['imported'], 1)


class TestMixedDuplicatesInImport(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(
            email='mixed@example.com',
            password='testpass123',
            username='mixeduser',
        )

    def test_bulk_import_handles_mixed_duplicates_correctly(self):
        feature1 = _point('Hash Dup', -122.1, 37.7, 'Exact match')
        _seed_library(self.user, feature1)
        feature2_in_store = _point('Store Feature', -122.2, 37.8, 'Store')
        _seed_library(self.user, feature2_in_store)
        feature2 = _point('Geom Dup', -122.2, 37.8, 'Different')
        feature3 = _point('Unique', -122.3, 37.9, 'Not duplicate')
        import_item = queue_with_drafts(
            user=self.user,
            original_filename='mixed.kml',
            features=[feature1, feature2, feature3],
            duplicate_features=[
                {
                    'source': 'feature_store',
                    'match_type': 'hash',
                    'feature': feature1,
                    'existing_features': [],
                },
                {
                    'source': 'feature_store',
                    'match_type': 'geometry',
                    'feature': feature2,
                    'existing_features': [],
                },
            ],
            imported=False,
        )
        result = _apply(import_item)
        self.assertEqual(result['imported'], 1)
        self.assertEqual(result['duplicates_skipped']['hash'], [feature1['properties']['geojson_hash']])
        self.assertEqual(result['duplicates_skipped']['geometry'], [feature2['properties']['geojson_hash']])
        created = FeatureStore.objects.filter(user=self.user, geojson_hash=feature3['properties']['geojson_hash'])
        self.assertEqual(created.count(), 1)
