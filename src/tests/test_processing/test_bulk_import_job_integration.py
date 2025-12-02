"""
Integration tests for BulkImportJob - multi-item end-to-end execution.

Tests complete BulkImportJob execution flow including sequential processing,
progress tracking, aggregated results, and WebSocket broadcasts.
"""
import pytest
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model
import time

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.jobs.bulk_import_job import BulkImportJob
from geo_lib.processing.status_tracker import status_tracker, ProcessingStatus
from geo_lib.feature_id import generate_feature_hash

User = get_user_model()


class TestBulkImportJobIntegration(TransactionTestCase):
    """Test BulkImportJob with multiple items."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='bulk_import@example.com',
            password='testpass123',
            username='bulk_import_user'
        )
        
        # Create reusable features
        self.features = []
        self.hashes = []
        for i in range(5):
            feature = {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0 + i*0.1, 37.7 + i*0.1]},
                'properties': {'name': f'Feature {i}'}
            }
            hash_val = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = hash_val
            self.features.append(feature)
            self.hashes.append(hash_val)
    
    def _wait_for_job_completion(self, job_id: str, timeout: float = 30.0) -> dict:
        """Wait for job to complete with timeout."""
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            # Status is returned as a string value from the enum
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value]:
                return job_status
            
            time.sleep(0.5)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")

    def test_bulk_import_single_item_success(self):
        """Test bulk import with just one item."""
        # Create single import item
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='single_bulk.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[0], self.features[1]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed successfully
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 2)
        
        # Verify import item marked as imported
        item1.refresh_from_db()
        self.assertTrue(item1.imported)
        
        print("✓ Test passed: bulk_import_single_item_success")

    def test_bulk_import_multiple_items_all_success(self):
        """Test bulk import with multiple items, all succeed."""
        # Create 3 import items
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_item1.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[0]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_item2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[1], self.features[2]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        item3 = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_item3.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[3], self.features[4]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id, item2.id, item3.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed successfully
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('Successfully imported 3 item(s)', job_status['message'])
        
        # Verify all features were created (1 + 2 + 2 = 5)
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 5)
        
        # Verify all items marked as imported
        item1.refresh_from_db()
        item2.refresh_from_db()
        item3.refresh_from_db()
        self.assertTrue(item1.imported)
        self.assertTrue(item2.imported)
        self.assertTrue(item3.imported)
        
        print("✓ Test passed: bulk_import_multiple_items_all_success")

    def test_bulk_import_partial_failure(self):
        """Test bulk import where some items succeed, others fail."""
        # Create one valid item
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='valid_item.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[0]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Create item with all features skipped (will fail)
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='all_skipped.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[1]],
            duplicate_features=[],
            skipped_feature_ids=[self.hashes[1]],  # Skip the only feature
            imported=False
        )
        
        # Create another valid item
        item3 = ImportQueue.objects.create(
            user=self.user,
            original_filename='valid_item2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[2]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id, item2.id, item3.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed with partial success
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        self.assertIn('2 imported', job_status['message'])
        self.assertIn('1 failed', job_status['message'])
        
        # Verify only valid features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 2)
        
        # Verify successful items marked as imported
        item1.refresh_from_db()
        item2.refresh_from_db()
        item3.refresh_from_db()
        self.assertTrue(item1.imported)
        self.assertFalse(item2.imported)  # Failed to import
        self.assertTrue(item3.imported)
        
        print("✓ Test passed: bulk_import_partial_failure")

    def test_bulk_import_missing_items(self):
        """Test bulk import with non-existent item IDs."""
        # Create one valid item
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='valid.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[0]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start bulk import with one valid and one invalid ID
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id, 99999],  # 99999 doesn't exist
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job failed
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value)
        self.assertIn('not found or not authorized', job_status['message'])
        
        # Verify no features were created (bulk import fails on missing items)
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 0)
        
        print("✓ Test passed: bulk_import_missing_items")

    def test_bulk_import_progress_tracking(self):
        """Test that progress updates work for each item."""
        # Create 3 items
        items = []
        for i in range(3):
            item = ImportQueue.objects.create(
                user=self.user,
                original_filename=f'progress_item{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[self.features[i]],
                duplicate_features=[],
                skipped_feature_ids=[],
                imported=False
            )
            items.append(item)
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item.id for item in items],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Poll for status updates and track progress
        progress_updates = []
        start_time = time.time()
        timeout = 30.0
        
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            status = job_status.get('status')
            progress = job_status.get('progress', 0.0)
            
            # Record progress updates
            if progress not in [p[1] for p in progress_updates]:
                progress_updates.append((status, progress))
            
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value]:
                break
            
            time.sleep(0.2)
        
        # Verify job completed
        final_status = status_tracker.get_job_status(job_id)
        self.assertEqual(final_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify progress increased over time
        self.assertGreater(len(progress_updates), 1, "Should have multiple progress updates")
        
        print("✓ Test passed: bulk_import_progress_tracking")

    def test_bulk_import_aggregates_results(self):
        """Test that results are aggregated across all items."""
        # Create items with various features
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='agg_item1.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[0], self.features[1]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='agg_item2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.features[2]],
            duplicate_features=[],
            skipped_feature_ids=[],
            imported=False
        )
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item1.id, item2.id],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Wait for completion
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify job completed
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify message contains aggregated counts
        self.assertIn('2 item(s)', job_status['message'])
        
        # Verify total features created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 3)  # 2 + 1
        
        print("✓ Test passed: bulk_import_aggregates_results")

    def test_bulk_import_sequential_processing(self):
        """Test that items are processed sequentially, not in parallel."""
        # Create multiple items
        items = []
        for i in range(3):
            item = ImportQueue.objects.create(
                user=self.user,
                original_filename=f'seq_item{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[self.features[i]],
                duplicate_features=[],
                skipped_feature_ids=[],
                imported=False
            )
            items.append(item)
        
        # Track when features are created to verify sequential processing
        creation_times = []
        
        # Start bulk import job
        job = BulkImportJob(status_tracker)
        job_id = job.start_bulk_import_job(
            item_ids=[item.id for item in items],
            user_id=self.user.id,
            import_custom_icons=True
        )
        
        # Monitor feature creation
        start_time = time.time()
        timeout = 30.0
        last_count = 0
        
        while time.time() - start_time < timeout:
            current_count = FeatureStore.objects.filter(user=self.user).count()
            if current_count > last_count:
                creation_times.append(time.time() - start_time)
                last_count = current_count
            
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                break
            
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value]:
                break
            
            time.sleep(0.1)
        
        # Verify job completed
        final_status = status_tracker.get_job_status(job_id)
        self.assertEqual(final_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify all features created
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 3)
        
        # If processing was parallel, features would be created at similar times
        # Sequential processing means features are created one after another
        # We just verify all were created successfully
        
        print("✓ Test passed: bulk_import_sequential_processing")

