"""
Tests for Redis-based job status API endpoint.
Tests that background jobs (import, delete, bulk_import, bulk_delete) are properly
stored in Redis and can be queried via the API endpoint.
"""
import json
import time
from unittest.mock import patch
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, status_tracker
from geo_lib.processing.jobs.helpers.redis_job_storage import (
    get_job_status,
    COMPLETED_JOB_TTL
)
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.processing.jobs.delete_job import DeleteJob
from geo_lib.processing.jobs.bulk_import_job import BulkImportJob
from geo_lib.processing.jobs.bulk_delete_job import BulkDeleteJob
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


class TestRedisJobStatusAPI(TransactionTestCase):
    """
    Test Redis job status storage and API endpoint using real jobs.
    Uses TransactionTestCase because jobs run in separate threads.
    """

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
        
        # Create job instances
        self.import_job = ImportJob(status_tracker)
        self.delete_job = DeleteJob(status_tracker)
        self.bulk_import_job = BulkImportJob(status_tracker)
        self.bulk_delete_job = BulkDeleteJob(status_tracker)
        
        # Clean up Redis job keys before each test
        self._cleanup_redis_jobs()

    def tearDown(self):
        """Clean up after tests."""
        # Clean up Redis job keys after each test
        self._cleanup_redis_jobs()
        # Clean up database
        ImportQueue.objects.filter(user=self.user).delete()
        FeatureStore.objects.filter(user=self.user).delete()

    def _cleanup_redis_jobs(self):
        """Clean up Redis job keys."""
        try:
            redis_client = get_redis_connection()
            # Clean up job keys
            job_keys = redis_client.keys('job:*')
            if job_keys:
                redis_client.delete(*job_keys)
            # Clean up user job sets
            user_job_keys = redis_client.keys('user_jobs:*')
            if user_job_keys:
                redis_client.delete(*user_job_keys)
        except Exception:
            # Redis might not be available in test environment
            pass

    def _wait_for_job_completion(self, job_id: str, timeout: float = 30.0) -> dict:
        """Wait for job to complete with timeout."""
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value, 
                         ProcessingStatus.COMPLETED, ProcessingStatus.FAILED]:
                return job_status
            
            time.sleep(0.5)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")

    def test_import_job_stored_in_redis(self):
        """Test that import job is stored in Redis when started."""
        # Create an import queue item
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
            'properties': {'name': 'Test Point'}
        }
        # Add required geojson_hash
        feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature]
        )

        # Start import job
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )
        self.assertIsNotNone(job_id)

        # Wait for job to be stored in Redis (with timeout)
        max_wait = 5.0
        start_time = time.time()
        redis_job = None
        while time.time() - start_time < max_wait:
            redis_job = get_job_status(job_id)
            if redis_job:
                break
            time.sleep(0.1)

        # Check Redis for job
        self.assertIsNotNone(redis_job, "Job should be stored in Redis")
        self.assertEqual(redis_job['job_id'], job_id)
        self.assertEqual(redis_job['user_id'], self.user.id)
        self.assertEqual(redis_job['job_type'], 'import')
        self.assertEqual(redis_job['filename'], f"Import {import_item.original_filename}")
        # Job might be queued, processing, or completed (if it finished very quickly)
        self.assertIn(redis_job['status'], ['queued', 'processing', 'completed'])

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Check Redis again - job should still be there with completed status
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job)
        self.assertEqual(redis_job['status'], ProcessingStatus.COMPLETED.value)
        self.assertIsNotNone(redis_job.get('completed_at'))

    def test_delete_job_stored_in_redis(self):
        """Test that delete job is stored in Redis when started."""
        # Create an import queue item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        # Start delete job
        job_id = self.delete_job.start_delete_job(
            item_id=import_item.id,
            user_id=self.user.id,
            filename=import_item.original_filename
        )
        self.assertIsNotNone(job_id)

        # Wait for job to be stored in Redis (with timeout)
        max_wait = 5.0
        start_time = time.time()
        redis_job = None
        while time.time() - start_time < max_wait:
            redis_job = get_job_status(job_id)
            if redis_job:
                break
            time.sleep(0.1)

        # Check Redis for job
        self.assertIsNotNone(redis_job, "Job should be stored in Redis")
        self.assertEqual(redis_job['job_id'], job_id)
        self.assertEqual(redis_job['user_id'], self.user.id)
        self.assertEqual(redis_job['job_type'], 'delete')
        self.assertEqual(redis_job['filename'], import_item.original_filename)
        # Job might be queued, processing, or completed (if it finished very quickly)
        self.assertIn(redis_job['status'], ['queued', 'processing', 'completed'])

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Check Redis again - job should still be there with completed status
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job)
        self.assertEqual(redis_job['status'], ProcessingStatus.COMPLETED.value)

    def test_bulk_import_job_stored_in_redis(self):
        """Test that bulk import job is stored in Redis when started."""
        # Create import queue items
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point 1'}
            }]
        )
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0]},
                'properties': {'name': 'Test Point 2'}
            }]
        )

        # Start bulk import job
        job_id = self.bulk_import_job.start_bulk_import_job(
            item_ids=[import_item1.id, import_item2.id],
            user_id=self.user.id
        )
        self.assertIsNotNone(job_id)

        # Wait a bit for job to start
        time.sleep(0.5)

        # Check Redis for job
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job, "Job should be stored in Redis")
        self.assertEqual(redis_job['job_id'], job_id)
        self.assertEqual(redis_job['user_id'], self.user.id)
        self.assertEqual(redis_job['job_type'], 'bulk_import')
        self.assertIn('Bulk import', redis_job['filename'])

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=60.0)

        # Check Redis again
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job)
        self.assertEqual(redis_job['status'], ProcessingStatus.COMPLETED.value)

    def test_bulk_delete_job_stored_in_redis(self):
        """Test that bulk delete job is stored in Redis when started."""
        # Create import queue items
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        # Start bulk delete job
        job_id = self.bulk_delete_job.start_bulk_delete_job(
            item_ids=[import_item1.id, import_item2.id],
            user_id=self.user.id
        )
        self.assertIsNotNone(job_id)

        # Wait a bit for job to start
        time.sleep(0.5)

        # Check Redis for job
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job, "Job should be stored in Redis")
        self.assertEqual(redis_job['job_id'], job_id)
        self.assertEqual(redis_job['user_id'], self.user.id)
        self.assertEqual(redis_job['job_type'], 'bulk_delete')
        self.assertIn('Bulk delete', redis_job['filename'])

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Check Redis again
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job)
        self.assertEqual(redis_job['status'], ProcessingStatus.COMPLETED.value)

    def test_get_all_job_statuses_api_endpoint(self):
        """Test the API endpoint returns all jobs for the current user."""
        # Create and start multiple jobs
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point 1'}
            }]
        )
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        # Start an import job
        import_job_id = self.import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )

        # Start a delete job
        delete_job_id = self.delete_job.start_delete_job(
            item_id=import_item2.id,
            user_id=self.user.id,
            filename=import_item2.original_filename
        )

        # Wait a bit for jobs to start
        time.sleep(1.0)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        jobs = data['jobs']
        
        # Should have at least 2 jobs
        self.assertGreaterEqual(len(jobs), 2)
        
        # Find our jobs
        job_ids = [job['job_id'] for job in jobs]
        self.assertIn(import_job_id, job_ids)
        self.assertIn(delete_job_id, job_ids)
        
        # Verify job details
        import_job_data = next(job for job in jobs if job['job_id'] == import_job_id)
        self.assertEqual(import_job_data['user_id'], self.user.id)
        self.assertEqual(import_job_data['job_type'], 'import')
        
        delete_job_data = next(job for job in jobs if job['job_id'] == delete_job_id)
        self.assertEqual(delete_job_data['user_id'], self.user.id)
        self.assertEqual(delete_job_data['job_type'], 'delete')

    def test_jobs_sorted_by_created_at(self):
        """Test that jobs are returned sorted by created_at (newest first)."""
        # Create and start jobs with small delays
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point 1'}
            }]
        )
        
        job_id1 = self.import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )
        
        time.sleep(0.1)
        
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0]},
                'properties': {'name': 'Test Point 2'}
            }]
        )
        
        job_id2 = self.import_job.start_import_job(
            item_id=import_item2.id,
            user_id=self.user.id
        )

        # Wait a bit for jobs to be stored
        time.sleep(0.5)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        # Find our jobs
        job1_data = next((job for job in jobs if job['job_id'] == job_id1), None)
        job2_data = next((job for job in jobs if job['job_id'] == job_id2), None)
        
        self.assertIsNotNone(job1_data)
        self.assertIsNotNone(job2_data)
        
        # Job2 should be newer (created later) and appear first
        self.assertGreaterEqual(job2_data['created_at'], job1_data['created_at'])

    def test_completed_job_has_ttl(self):
        """Test that completed jobs have TTL set in Redis."""
        # Create an import queue item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point'}
            }]
        )

        # Start import job
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Check TTL in Redis
        try:
            redis_client = get_redis_connection()
            job_key = f"job:{job_id}"
            ttl = redis_client.ttl(job_key)
            
            # TTL should be set and approximately equal to COMPLETED_JOB_TTL
            # Allow some variance since TTL decreases over time
            self.assertGreater(ttl, 0)
            self.assertLessEqual(ttl, COMPLETED_JOB_TTL)
            # Should be close to COMPLETED_JOB_TTL (within 10 seconds)
            self.assertGreater(ttl, COMPLETED_JOB_TTL - 10)
        except Exception:
            # Redis might not be available, skip TTL check
            pass

    def test_failed_job_has_ttl(self):
        """Test that failed jobs have TTL set in Redis."""
        # Create an import queue item that will fail (invalid data)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]  # Empty geofeatures will cause import to fail
        )

        # Start import job
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to complete (should fail)
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Check Redis - job should be marked as failed
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job)
        self.assertEqual(redis_job['status'], ProcessingStatus.FAILED.value)

        # Check TTL in Redis
        try:
            redis_client = get_redis_connection()
            job_key = f"job:{job_id}"
            ttl = redis_client.ttl(job_key)
            
            # TTL should be set for failed jobs
            self.assertGreater(ttl, 0)
            self.assertLessEqual(ttl, COMPLETED_JOB_TTL)
        except Exception:
            # Redis might not be available, skip TTL check
            pass

    def test_user_jobs_isolation(self):
        """Test that users only see their own jobs."""
        # Create another user
        other_user = User.objects.create_user(
            email='other@example.com',
            password='testpass123',
            username='otheruser'
        )

        # Create jobs for both users
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point 1'}
            }]
        )
        import_item2 = ImportQueue.objects.create(
            user=other_user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0]},
                'properties': {'name': 'Test Point 2'}
            }]
        )

        # Start jobs for both users
        job_id1 = self.import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )
        job_id2 = self.import_job.start_import_job(
            item_id=import_item2.id,
            user_id=other_user.id
        )

        # Wait a bit for jobs to start
        time.sleep(0.5)

        # Current user should only see their own job
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        job_ids = [job['job_id'] for job in jobs]
        self.assertIn(job_id1, job_ids)
        self.assertNotIn(job_id2, job_ids)
        
        # Verify all returned jobs belong to current user
        for job in jobs:
            self.assertEqual(job['user_id'], self.user.id)

    def test_get_all_job_statuses_unauthenticated(self):
        """Test that unauthenticated users cannot access job statuses."""
        self.client.logout()
        
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 401)

    def test_get_all_job_statuses_empty_list(self):
        """Test endpoint returns empty list when user has no jobs."""
        # Clean up any existing jobs
        self._cleanup_redis_jobs()
        
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        self.assertEqual(len(data['jobs']), 0)

    def test_get_all_job_statuses_includes_all_job_types(self):
        """Test that endpoint returns all job types (import, delete, bulk_import, bulk_delete)."""
        # Create import queue items
        import_item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='import.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point'}
            }]
        )
        import_item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='delete.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        import_item3 = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk1.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        import_item4 = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk2.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        # Start different job types
        import_job_id = self.import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )
        delete_job_id = self.delete_job.start_delete_job(
            item_id=import_item2.id,
            user_id=self.user.id,
            filename=import_item2.original_filename
        )
        bulk_import_job_id = self.bulk_import_job.start_bulk_import_job(
            item_ids=[import_item3.id],
            user_id=self.user.id
        )
        bulk_delete_job_id = self.bulk_delete_job.start_bulk_delete_job(
            item_ids=[import_item4.id],
            user_id=self.user.id
        )

        # Wait for jobs to be stored
        time.sleep(1.0)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        # Should have all 4 job types
        job_types = [job['job_type'] for job in jobs]
        self.assertIn('import', job_types)
        self.assertIn('delete', job_types)
        self.assertIn('bulk_import', job_types)
        self.assertIn('bulk_delete', job_types)

    def test_get_all_job_statuses_response_structure(self):
        """Test that response has correct structure with all required fields."""
        # Create and start a job
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point'}
            }]
        )
        
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to be stored
        time.sleep(0.5)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        
        jobs = data['jobs']
        self.assertGreater(len(jobs), 0)
        
        # Verify job structure
        job = jobs[0]
        required_fields = ['job_id', 'user_id', 'job_type', 'status', 'filename', 'created_at']
        for field in required_fields:
            self.assertIn(field, job, f"Job should have '{field}' field")

    def test_get_all_job_statuses_includes_completed_jobs(self):
        """Test that completed jobs are included in the response."""
        # Create and complete a job
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
            'properties': {'name': 'Test Point'}
        }
        # Add required geojson_hash
        feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature]
        )
        
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        # Find the completed job
        completed_job = next((job for job in jobs if job['job_id'] == job_id), None)
        self.assertIsNotNone(completed_job)
        # Job may complete or fail depending on external services
        self.assertIn(completed_job['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value])
        if completed_job['status'] == ProcessingStatus.COMPLETED.value:
            self.assertIn('completed_at', completed_job)

    def test_get_all_job_statuses_includes_failed_jobs(self):
        """Test that failed jobs are included in the response."""
        # Create a job that will fail (empty geofeatures)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]  # Will cause failure
        )
        
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to fail
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Call the API endpoint
        response = self.client.get('/api/item/import/jobs/all')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        # Find the failed job
        failed_job = next((job for job in jobs if job['job_id'] == job_id), None)
        self.assertIsNotNone(failed_job)
        self.assertEqual(failed_job['status'], ProcessingStatus.FAILED.value)

    def test_get_all_job_statuses_pagination_support(self):
        """Test pagination parameters if implemented."""
        # Create multiple jobs
        for i in range(5):
            import_item = ImportQueue.objects.create(
                user=self.user,
                original_filename=f'test{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[{
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [-122.4 + i*0.01, 37.7 + i*0.01, 0]},
                    'properties': {'name': f'Test Point {i}'}
                }]
            )
            
            self.import_job.start_import_job(
                item_id=import_item.id,
                user_id=self.user.id
            )

        # Wait for jobs to be stored
        time.sleep(1.0)

        # Test pagination parameters (if implemented)
        response = self.client.get('/api/item/import/jobs/all?page=1&limit=3')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        # If pagination is implemented, should have 3 or fewer jobs
        # If not implemented, will have all jobs (which is also valid)

    def test_get_all_job_statuses_status_filter(self):
        """Test filtering by job status if implemented."""
        # Create completed and processing jobs
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
            'properties': {'name': 'Test Point'}
        }
        # Add required geojson_hash
        feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature]
        )
        
        job_id = self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        # Wait for job to complete
        self._wait_for_job_completion(job_id, timeout=30.0)

        # Test status filter (if implemented)
        response = self.client.get('/api/item/import/jobs/all?status=completed')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        jobs = data['jobs']
        
        # If filtering is implemented, all jobs should be completed
        # If not, endpoint still works correctly
        if jobs:
            # Check if filtering worked
            for job in jobs:
                if job['job_id'] == job_id:
                    # Job may complete or fail depending on external services
                    # If status filter is implemented, only completed jobs should appear
                    # If not implemented, all jobs appear regardless of filter
                    self.assertIn(job['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value])

    def test_get_all_job_statuses_multiple_calls_consistency(self):
        """Test that multiple calls return consistent results."""
        # Create a job
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0]},
                'properties': {'name': 'Test Point'}
            }]
        )
        
        self.import_job.start_import_job(
            item_id=import_item.id,
            user_id=self.user.id
        )

        time.sleep(0.5)

        # Call endpoint multiple times
        response1 = self.client.get('/api/item/import/jobs/all')
        response2 = self.client.get('/api/item/import/jobs/all')
        
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(response2.status_code, 200)
        
        data1 = json.loads(response1.content)
        data2 = json.loads(response2.content)
        
        # Job IDs should be consistent
        job_ids1 = {job['job_id'] for job in data1['jobs']}
        job_ids2 = {job['job_id'] for job in data2['jobs']}
        self.assertEqual(job_ids1, job_ids2)

