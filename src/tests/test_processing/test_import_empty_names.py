"""
Tests for importing features with empty names.

Validates that the import system properly handles features without names,
allowing users to display only icons or styled geometry.
"""
import time
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model

from api.models import ImportQueue, FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.processing.status_tracker import ProcessingStatus, status_tracker

User = get_user_model()


class TestImportEmptyNames(TransactionTestCase):
    """Test importing features with empty names."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='emptyname@example.com',
            password='testpass123',
            username='emptyname_user'
        )
    
    def _wait_for_job_completion(self, job_id: str, timeout: float = 30.0) -> dict:
        """Wait for job to complete with timeout."""
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value]:
                return job_status
            
            time.sleep(0.5)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")

    def test_import_point_with_empty_name(self):
        """Test importing a point feature with an empty name."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'name': '',
                'marker-color': '#00ff00',
                'tags': ['test']
            }
        }
        hash_val = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='empty_name_point.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify feature was created with empty name
        imported_features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(imported_features.count(), 1)
        
        feature_store = imported_features.first()
        self.assertEqual(feature_store.geojson['properties']['name'], '')
        self.assertEqual(feature_store.geojson['properties']['marker-color'], '#00ff00')
        
        print("✓ Test passed: import_point_with_empty_name")

    def test_import_line_with_empty_name(self):
        """Test importing a line feature with an empty name."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
            },
            'properties': {
                'name': '',
                'stroke': '#ff0000',
                'tags': ['test', 'line']
            }
        }
        hash_val = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='empty_name_line.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify feature was created with empty name
        imported_features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(imported_features.count(), 1)
        
        feature_store = imported_features.first()
        self.assertEqual(feature_store.geojson['properties']['name'], '')
        self.assertEqual(feature_store.geojson['properties']['stroke'], '#ff0000')
        
        print("✓ Test passed: import_line_with_empty_name")

    def test_import_polygon_with_empty_name(self):
        """Test importing a polygon feature with an empty name."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[
                    [-122.4194, 37.7749],
                    [-122.4094, 37.7749],
                    [-122.4094, 37.7849],
                    [-122.4194, 37.7849],
                    [-122.4194, 37.7749]
                ]]
            },
            'properties': {
                'name': '',
                'stroke': '#0000ff',
                'tags': ['test', 'polygon']
            }
        }
        hash_val = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='empty_name_polygon.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify feature was created with empty name
        imported_features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(imported_features.count(), 1)
        
        feature_store = imported_features.first()
        self.assertEqual(feature_store.geojson['properties']['name'], '')
        self.assertEqual(feature_store.geojson['properties']['stroke'], '#0000ff')
        self.assertEqual(feature_store.geojson['properties']['fill'], '#0000ff')
        
        print("✓ Test passed: import_polygon_with_empty_name")

    def test_import_none_name_converts_to_empty(self):
        """Test that None names are converted to empty strings during import."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'name': None,
                'tags': []
            }
        }
        hash_val = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='none_name.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify None was converted to empty string
        imported_features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(imported_features.count(), 1)
        
        feature_store = imported_features.first()
        self.assertEqual(feature_store.geojson['properties']['name'], '')
        
        print("✓ Test passed: import_none_name_converts_to_empty")

    def test_import_mixed_empty_and_named_features(self):
        """Test importing multiple features with mix of empty and non-empty names."""
        features = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
                'properties': {'name': '', 'tags': []}
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
                'properties': {'name': 'Named Feature', 'tags': []}
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
                'properties': {'name': '', 'tags': []}
            },
        ]
        
        for feature in features:
            hash_val = generate_geojson_hash(feature)
            feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='mixed_names.kml',
            raw_file='<kml></kml>',
            geofeatures=features,
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify all features were imported
        imported_features = FeatureStore.objects.filter(user=self.user).order_by('id')
        self.assertEqual(imported_features.count(), 3)
        
        # Check names are preserved correctly
        names = [f.geojson['properties']['name'] for f in imported_features]
        self.assertIn('', names)
        self.assertIn('Named Feature', names)
        self.assertEqual(names.count(''), 2)
        
        print("✓ Test passed: import_mixed_empty_and_named_features")

    def test_import_missing_name_property(self):
        """Test that features without a name property get empty string default."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'tags': ['test']
                # No 'name' property
            }
        }
        hash_val = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = hash_val
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='missing_name.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job_status = self._wait_for_job_completion(job_id)
        
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify feature gets empty string as default name
        imported_features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(imported_features.count(), 1)
        
        feature_store = imported_features.first()
        self.assertEqual(feature_store.geojson['properties']['name'], '')
        
        print("✓ Test passed: import_missing_name_property")

