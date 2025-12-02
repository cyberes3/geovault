"""
Edge case tests for import operations.

Tests boundary conditions, large batches, mixed valid/invalid features,
and concurrent operations.
"""
import pytest
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model
from unittest.mock import patch
import time

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.processing.jobs.bulk_import_job import BulkImportJob
from geo_lib.processing.status_tracker import status_tracker, ProcessingStatus
from geo_lib.feature_id import generate_feature_hash
from geo_lib.processing.duplicate_models import DuplicateMatchType, DuplicateSource

User = get_user_model()


class TestImportEdgeCases(TransactionTestCase):
    """Test edge cases and boundary conditions."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='edge_case@example.com',
            password='testpass123',
            username='edge_case_user'
        )
    
    def _wait_for_job_completion(self, job_id: str, timeout: float = 60.0) -> dict:
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

    def test_large_batch_import(self):
        """Test importing 100+ features in one job."""
        # Create 100 features
        features = []
        for i in range(100):
            feature = {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0 + (i % 10) * 0.01, 37.7 + (i // 10) * 0.01]},
                'properties': {'name': f'Large Batch Feature {i}'}
            }
            hash_val = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = hash_val
            features.append(feature)
        
        # Create import item with all features
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='large_batch.kml',
            raw_file='<kml></kml>',
            geofeatures=features,
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start import job
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id, timeout=60.0)
        
        # Verify job completed successfully
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('100', job_status['message'])
        
        # Verify all features were created
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 100)
        
        print("✓ Test passed: large_batch_import")

    def test_mixed_valid_invalid_features(self):
        """Test import with some features having issues, others are fine."""
        # Note: Features without feature_hash will cause filter_features_to_process to fail
        # This test verifies that the import system handles this error gracefully
        
        # Create valid feature
        valid_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Valid Feature'}
        }
        valid_hash = generate_feature_hash(valid_feature)
        valid_feature['properties']['feature_hash'] = valid_hash
        
        # Create feature without hash (invalid) - this will cause an error
        invalid_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Invalid Feature'}
            # Missing feature_hash - will cause KeyError in filter_features_to_process
        }
        
        # Create another valid feature
        valid_feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Valid Feature 2'}
        }
        valid_hash2 = generate_feature_hash(valid_feature2)
        valid_feature2['properties']['feature_hash'] = valid_hash2
        
        # Create import item with mixed features
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='mixed.kml',
            raw_file='<kml></kml>',
            geofeatures=[valid_feature, invalid_feature, valid_feature2],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start import job
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job failed due to invalid feature (missing feature_hash)
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        
        # Verify no features were created (job failed before processing)
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 0)
        
        print("✓ Test passed: mixed_valid_invalid_features")

    def test_all_three_skip_types_combined(self):
        """Test import with geometry duplicates + hash duplicates + manual skips."""
        # Create features
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        hash1 = generate_feature_hash(feature1)
        feature1['properties']['feature_hash'] = hash1
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'}
        }
        hash2 = generate_feature_hash(feature2)
        feature2['properties']['feature_hash'] = hash2
        
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Feature 3'}
        }
        hash3 = generate_feature_hash(feature3)
        feature3['properties']['feature_hash'] = hash3
        
        # Create feature that already exists (hash duplicate)
        from django.contrib.gis.geos import Point
        existing_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1,
            geometry=Point(-122.1, 37.7, 0.0),  # 3D Point
            geojson_hash=hash1
        )
        
        # Create import item with:
        # - feature1: hash duplicate (already exists)
        # - feature2: geometry duplicate (marked in duplicate_features)
        # - feature3: manually skipped
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='all_skip_types.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2, feature3],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': feature2,
                'existing_features': []
            }],
            skipped_feature_ids=[hash3],  # Manual skip
            imported=False
        )
        
        # Start import job
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # When all features are skipped (hash duplicate + geometry duplicate + manual skip),
        # the job fails because no features were imported
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        self.assertIn('No features were imported', job_status['message'])
        
        # Verify no new features were created (all were skipped)
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 1)  # Only the existing one
        
        print("✓ Test passed: all_three_skip_types_combined")

    def test_import_with_bulk_operations(self):
        """Test that bulk operations are applied during import."""
        # Create features
        features = []
        for i in range(5):
            feature = {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0 + i*0.1, 37.7 + i*0.1]},
                'properties': {'name': f'Bulk Op Feature {i}'}
            }
            hash_val = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = hash_val
            features.append(feature)
        
        # Create import item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_ops.kml',
            raw_file='<kml></kml>',
            geofeatures=features,
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start import job
        job = ImportJob(status_tracker)
        job_id = job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify all features were created
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 5)
        
        # The bulk operations are handled by bulk_create_features_with_fallback
        # which is tested in other tests. This test just verifies the import works.
        
        print("✓ Test passed: import_with_bulk_operations")

    def test_concurrent_import_same_file(self):
        """Test two users importing same file simultaneously."""
        # Create second user
        user2 = User.objects.create_user(
            email='concurrent_user2@example.com',
            password='testpass123',
            username='concurrent_user2'
        )
        
        # Create same feature for both users
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Concurrent Feature'}
        }
        hash_val = generate_feature_hash(feature)
        feature['properties']['feature_hash'] = hash_val
        
        # Create import items for both users
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='concurrent.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        import_item2 = ImportQueue.objects.create(
            user=user2,
            original_filename='concurrent.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start both import jobs
        job1 = ImportJob(status_tracker)
        job_id1 = job1.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        job2 = ImportJob(status_tracker)
        job_id2 = job2.start_import_job(
            item_id=import_item2.id,
            user_id=user2.id,
            import_custom_icons=True
        )
        
        # Wait for both to complete
        job_status1 = self._wait_for_job_completion(job_id1)
        job_status2 = self._wait_for_job_completion(job_id2)
        
        # Verify both jobs completed successfully
        self.assertEqual(job_status1['status'], ProcessingStatus.COMPLETED.value)
        self.assertEqual(job_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify both users have the feature (they're separate users, so no conflict)
        features1 = FeatureStore.objects.filter(user=self.user).count()
        features2 = FeatureStore.objects.filter(user=user2).count()
        self.assertEqual(features1, 1)
        self.assertEqual(features2, 1)
        
        print("✓ Test passed: concurrent_import_same_file")


class TestBulkImportEdgeCases(TransactionTestCase):
    """Test edge cases for bulk import operations."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='bulk_edge@example.com',
            password='testpass123',
            username='bulk_edge_user'
        )
    
    def _wait_for_job_completion(self, job_id: str, timeout: float = 60.0) -> dict:
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

    def test_bulk_import_large_batch(self):
        """Test bulk import with many items."""
        # Create 10 import items
        items = []
        for i in range(10):
            feature = {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0 + i*0.1, 37.7 + i*0.1]},
                'properties': {'name': f'Bulk Item {i} Feature'}
            }
            hash_val = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = hash_val
            
            item = ImportQueue.objects.create(
                user=self.user,
                original_filename=f'bulk_item_{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[feature],
                duplicate_features=[],
                skipped_feature_ids=[],
                imported=False
            )
            items.append(item)
        
        # Start bulk import
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item.id for item in items],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id, timeout=60.0)
        
        # Verify job completed
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('10', job_status['message'])
        self.assertIn('imported', job_status['message'].lower())
        
        # Verify all features created
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 10)
        
        print("✓ Test passed: bulk_import_large_batch")

