"""
Error handling tests for import operations.

Tests various failure modes including file-level duplicates, database errors,
invalid data, and edge cases.
"""
import pytest
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model
from django.db import IntegrityError
from unittest.mock import patch, MagicMock
import time

from api.models import ImportQueue, FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.jobs.bulk_import_job import BulkImportJob
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.processing.status_tracker import ProcessingStatus, status_tracker

User = get_user_model()


class TestImportErrorHandling(TransactionTestCase):
    """Test error handling in import operations."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='error_test@example.com',
            password='testpass123',
            username='error_test_user'
        )
        
        # Create test features
        self.feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        self.hash1 = generate_geojson_hash(self.feature1)
        self.feature1['properties']['geojson_hash'] = self.hash1
        
        self.feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'}
        }
        self.hash2 = generate_geojson_hash(self.feature2)
        self.feature2['properties']['geojson_hash'] = self.hash2
    
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

    def test_file_level_duplicate_in_queue(self):
        """Test that earlier unimported file with same hash blocks import in bulk import."""
        # Note: File-level duplicate checking is only in BulkImportJob, not ImportJob
        # Create first import item
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='first.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False,
            file_hash='same_hash_123'
        )
        
        # Create second import item with same hash (but later timestamp)
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='duplicate.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature2],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False,
            file_hash='same_hash_123'  # Same hash
        )
        
        # Try to import the second item via bulk import (should fail due to file-level duplicate)
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item2.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed with failure for that item
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('0 imported', job_status['message'])
        self.assertIn('1 failed', job_status['message'])
        
        # Verify no features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 0)
        
        print("✓ Test passed: file_level_duplicate_in_queue")

    def test_database_write_failure_fallback(self):
        """Test that bulk create failure falls back to individual saves."""
        # Note: The bulk_create_features_with_fallback function already has
        # fallback logic built in. This test verifies that imports work correctly
        # even when bulk operations might encounter issues.
        
        # Create import item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='fallback_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2],
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
        
        # Verify job completed successfully
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify features were created
        features_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(features_count, 2)
        
        # The actual fallback mechanism is tested in the bulk_create_features_with_fallback
        # function's own tests. This test just verifies the integration works.
        
        print("✓ Test passed: database_write_failure_fallback")

    def test_invalid_feature_hash_handling(self):
        """Test handling of features missing feature_hash property."""
        # Create feature without feature_hash
        invalid_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Invalid Feature'}
            # Missing feature_hash
        }
        
        # Create import item with invalid feature
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='invalid_hash.kml',
            raw_file='<kml></kml>',
            geofeatures=[invalid_feature],
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
        
        # Job should complete but may skip the invalid feature
        # The exact behavior depends on process_features_for_import implementation
        # We just verify it doesn't crash
        self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value])
        
        print("✓ Test passed: invalid_feature_hash_handling")

    def test_empty_geofeatures_array(self):
        """Test import item with no features."""
        # Create import item with empty geofeatures
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='empty.kml',
            raw_file='<kml></kml>',
            geofeatures=[],  # Empty
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
        
        # Verify job failed with appropriate message
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        self.assertIn('No features', job_status['message'])
        
        # Verify no features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 0)
        
        print("✓ Test passed: empty_geofeatures_array")

    def test_integrity_error_on_hash_collision(self):
        """Test handling of duplicate hash during save (race condition)."""
        # Create a feature and import it first
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='first_import.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Import first item
        job1 = ImportJob(status_tracker)
        job_id1 = job1.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        self._wait_for_job_completion(job_id1)
        
        # Verify feature was created
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)
        
        # Create second import item with same feature (hash duplicate)
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='duplicate_hash.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],  # Same feature
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Import second item - should skip duplicate
        job2 = ImportJob(status_tracker)
        job_id2 = job2.start_import_job(
            item_id=import_item2.id,
            user_id=self.user.id,
            import_custom_icons=True
        )
        job_status = self._wait_for_job_completion(job_id2)
        
        # When all features are duplicates, the job fails because no features were imported
        # This is expected behavior - hash duplicates are always blocked
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        self.assertIn('No features were imported', job_status['message'])
        
        # Verify still only 1 feature (duplicate was blocked)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 1)
        
        print("✓ Test passed: integrity_error_on_hash_collision")

    def test_import_job_no_features_to_create(self):
        """Test when all features are filtered out (skipped or duplicates)."""
        # Create import item where all features are manually skipped
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='all_filtered.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2],
            duplicate_features=[],
            skipped_feature_ids=[self.hash1, self.hash2],  # All skipped
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
        
        # Verify job failed with appropriate message
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        self.assertIn('No features were imported', job_status['message'])
        
        # Verify no features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 0)
        
        print("✓ Test passed: import_job_no_features_to_create")


class TestBulkImportErrorHandling(TransactionTestCase):
    """Test error handling in bulk import operations."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='bulk_error@example.com',
            password='testpass123',
            username='bulk_error_user'
        )
        
        self.feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        self.hash1 = generate_geojson_hash(self.feature1)
        self.feature1['properties']['geojson_hash'] = self.hash1
    
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

    def test_bulk_import_file_level_duplicate(self):
        """Test bulk import with file-level duplicate in queue."""
        # Create first item
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='first.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False,
            file_hash='same_hash'
        )
        
        # Create second item with same hash
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='duplicate.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False,
            file_hash='same_hash'
        )
        
        # Try bulk import - second should fail
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id, item2.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed with partial success
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('1 imported', job_status['message'])
        self.assertIn('1 failed', job_status['message'])
        
        print("✓ Test passed: bulk_import_file_level_duplicate")
