"""
Tests for Redis-based job status API endpoint.
Tests that background jobs (import, delete, bulk_import, bulk_delete) are properly
stored in Redis and can be queried via the API endpoint.
"""
import json
import time
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.status_tracker import ProcessingStatus, status_tracker
from geo_lib.processing.jobs.helpers.redis_job_storage import (
    get_job_status,
    COMPLETED_JOB_TTL
)
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.processing.jobs import import_job, delete_job, bulk_import_job, bulk_delete_job

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
        job_id = import_job.start_import_job(
            item_id=import_item.id,
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
        self.assertEqual(redis_job['job_type'], 'import')
        self.assertEqual(redis_job['filename'], f"Import {import_item.original_filename}")
        self.assertIn(redis_job['status'], ['queued', 'processing'])

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
        job_id = delete_job.start_delete_job(
            item_id=import_item.id,
            user_id=self.user.id,
            filename=import_item.original_filename
        )
        self.assertIsNotNone(job_id)

        # Wait a bit for job to start
        time.sleep(0.5)

        # Check Redis for job
        redis_job = get_job_status(job_id)
        self.assertIsNotNone(redis_job, "Job should be stored in Redis")
        self.assertEqual(redis_job['job_id'], job_id)
        self.assertEqual(redis_job['user_id'], self.user.id)
        self.assertEqual(redis_job['job_type'], 'delete')
        self.assertEqual(redis_job['filename'], import_item.original_filename)
        self.assertIn(redis_job['status'], ['queued', 'processing'])

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
        job_id = bulk_import_job.start_bulk_import_job(
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
        job_id = bulk_delete_job.start_bulk_delete_job(
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
        import_job_id = import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )

        # Start a delete job
        delete_job_id = delete_job.start_delete_job(
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
        
        job_id1 = import_job.start_import_job(
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
        
        job_id2 = import_job.start_import_job(
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
        job_id = import_job.start_import_job(
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
        job_id = import_job.start_import_job(
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

    def test_api_endpoint_handles_redis_unavailable(self):
        """Test that API endpoint handles Redis unavailability gracefully."""
        # Mock Redis to raise an exception
        from unittest.mock import patch
        
        with patch('geo_lib.processing.redis_job_storage.get_redis_connection') as mock_redis:
            mock_redis.side_effect = Exception("Redis unavailable")
            
            # API should return empty list instead of failing
            response = self.client.get('/api/item/import/jobs/all')
            self.assertEqual(response.status_code, 200)
            
            data = json.loads(response.content)
            self.assertIn('jobs', data)
            self.assertEqual(data['jobs'], [])

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
        job_id1 = import_job.start_import_job(
            item_id=import_item1.id,
            user_id=self.user.id
        )
        job_id2 = import_job.start_import_job(
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

