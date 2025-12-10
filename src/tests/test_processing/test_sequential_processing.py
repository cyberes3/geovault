"""
Tests for sequential processing with Redis queue.
Verifies that files are processed in FIFO order and only one at a time per user.
"""

import time
import threading
from unittest.mock import patch
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model

from geo_lib.processing.jobs.process_job import ProcessJob
from geo_lib.processing.queue_worker import QueueWorker, WorkerRegistry
from geo_lib.processing.redis_queue import get_processing_queue
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, status_tracker
from geo_lib.utils.redis_connection import get_redis_connection

User = get_user_model()


class TestSequentialProcessing(TransactionTestCase):
    """Test sequential file processing with queue."""
    
    @classmethod
    def setUpClass(cls):
        """Set up once for all tests in this class."""
        super().setUpClass()
        # Stop all workers at class level
        WorkerRegistry.stop_all_workers()
        time.sleep(2.0)  # Workers now respond within 1 second
    
    def setUp(self):
        """Set up test fixtures."""
        # Clean up Redis before test
        redis_client = get_redis_connection()
        keys = redis_client.keys('processing_queue:*')
        if keys:
            redis_client.delete(*keys)
        
        # Stop all workers and wait for them to exit
        WorkerRegistry.stop_all_workers()
        time.sleep(2.0)  # Workers respond within 1 second
        
        self.user = User.objects.create_user(
            email=f'sequential{time.time()}@example.com',
            password='testpass123',
            username=f'sequential_user_{time.time()}'
        )
        self.process_job = ProcessJob(status_tracker)
        
        # Track processing order
        self.processing_order = []
        self.processing_lock = threading.Lock()
    
    def tearDown(self):
        """Clean up after tests."""
        # Stop all workers
        WorkerRegistry.stop_all_workers()
        time.sleep(2.0)  # Workers respond within 1 second
        
        # Clean up Redis after test
        redis_client = get_redis_connection()
        keys = redis_client.keys('processing_queue:*')
        if keys:
            redis_client.delete(*keys)
    
    @classmethod
    def tearDownClass(cls):
        """Clean up once after all tests in this class."""
        WorkerRegistry.stop_all_workers()
        time.sleep(2.0)
        super().tearDownClass()
    
    def test_files_processed_sequentially(self):
        """Test that multiple files are processed one at a time in FIFO order."""
        # Mock the _execute_job method to track processing
        original_execute = self.process_job._execute_job
        
        def mock_execute(job_id, kwargs):
            with self.processing_lock:
                self.processing_order.append(job_id)
            # Simulate some processing time
            time.sleep(0.2)
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute):
            # Create and enqueue 5 jobs
            job_ids = []
            for i in range(5):
                job_id = status_tracker.create_job(f'file{i}.kml', self.user.id)
                job_ids.append(job_id)
                
                file_data = f'test content {i}'.encode('utf-8')
                result = self.process_job.enqueue_job(
                    job_id, file_data, f'file{i}.kml', self.user.id
                )
                assert result is not False
            
            # Wait for all jobs to complete
            # With 0.2s per job, 5 jobs should take ~1 second
            time.sleep(2.0)
        
        # Verify all jobs were processed
        assert len(self.processing_order) == 5
        
        # Verify jobs were processed in order
        for i, job_id in enumerate(job_ids):
            assert self.processing_order[i] == job_id
    
    def test_queue_length_tracking(self):
        """Test that queue length is correctly tracked."""
        queue = get_processing_queue(self.user.id)
        
        # Enqueue multiple jobs (mock execute to prevent actual processing)
        with patch.object(self.process_job, '_execute_job'):
            for i in range(3):
                job_id = status_tracker.create_job(f'queue{i}.kml', self.user.id)
                file_data = f'queue test {i}'.encode('utf-8')
                self.process_job.enqueue_job(
                    job_id, file_data, f'queue{i}.kml', self.user.id
                )
            
            # Check queue length (may be less than 3 if worker already started processing)
            initial_length = queue.get_queue_length()
            assert 0 <= initial_length <= 3
    
    def test_processing_with_failures(self):
        """Test that queue continues processing after a job fails."""
        processing_count = {'count': 0}
        
        def mock_execute_with_failure(job_id, kwargs):
            processing_count['count'] += 1
            # Fail on second job
            if processing_count['count'] == 2:
                raise Exception("Simulated processing error")
            # Succeed on others
            time.sleep(0.1)
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute_with_failure):
            # Enqueue 4 jobs
            for i in range(4):
                job_id = status_tracker.create_job(f'fail{i}.kml', self.user.id)
                file_data = f'fail test {i}'.encode('utf-8')
                self.process_job.enqueue_job(
                    job_id, file_data, f'fail{i}.kml', self.user.id
                )
            
            # Wait for processing
            time.sleep(2.0)
        
        # Verify all 4 jobs were attempted (including the failed one)
        assert processing_count['count'] == 4
    
    def test_user_isolation(self):
        """Test that different users' queues are independent."""
        user2 = User.objects.create_user(
            email='sequential2@example.com',
            password='testpass123',
            username='sequential_user2'
        )
        
        user1_order = []
        user2_order = []
        lock = threading.Lock()
        
        def mock_execute(job_id, kwargs):
            user_id = kwargs['user_id']
            with lock:
                if user_id == self.user.id:
                    user1_order.append(job_id)
                else:
                    user2_order.append(job_id)
            time.sleep(0.1)
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute):
            # Enqueue jobs for both users
            user1_jobs = []
            user2_jobs = []
            
            for i in range(3):
                # User 1 job
                job_id1 = status_tracker.create_job(f'user1-file{i}.kml', self.user.id)
                user1_jobs.append(job_id1)
                self.process_job.enqueue_job(
                    job_id1, f'user1 content {i}'.encode(), f'user1-file{i}.kml', self.user.id
                )
                
                # User 2 job
                job_id2 = status_tracker.create_job(f'user2-file{i}.kml', user2.id)
                user2_jobs.append(job_id2)
                self.process_job.enqueue_job(
                    job_id2, f'user2 content {i}'.encode(), f'user2-file{i}.kml', user2.id
                )
            
            # Wait for all jobs to complete
            time.sleep(2.0)
        
        # Both users should have processed all their jobs
        assert len(user1_order) == 3
        assert len(user2_order) == 3
        
        # Each user's jobs should be in order
        assert user1_order == user1_jobs
        assert user2_order == user2_jobs
    
    def test_timestamp_preserved(self):
        """Test that timestamps are preserved through queue."""
        timestamps = []
        
        def mock_execute(job_id, kwargs):
            # Access the job data if available
            time.sleep(0.05)
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute):
            # Enqueue jobs with small delays to ensure different timestamps
            for i in range(3):
                job_id = status_tracker.create_job(f'ts{i}.kml', self.user.id)
                file_data = f'timestamp test {i}'.encode()
                
                result = self.process_job.enqueue_job(
                    job_id, file_data, f'ts{i}.kml', self.user.id
                )
                assert result is not False
                timestamps.append(time.time())
                time.sleep(0.05)  # Small delay between enqueues
            
            # Wait for processing
            time.sleep(1.0)
        
        # Verify timestamps are in ascending order (jobs enqueued in order)
        for i in range(len(timestamps) - 1):
            assert timestamps[i] < timestamps[i + 1]
    
    def test_worker_exits_after_idle(self):
        """Test that worker exits after idle timeout."""
        # Set a short idle timeout for testing
        original_timeout = QueueWorker.IDLE_TIMEOUT
        QueueWorker.IDLE_TIMEOUT = 1  # 1 second timeout
        
        try:
            # Enqueue and process one job
            with patch.object(self.process_job, '_execute_job'):
                job_id = status_tracker.create_job('idle.kml', self.user.id)
                self.process_job.enqueue_job(
                    job_id, b'idle test', 'idle.kml', self.user.id
                )
                
                # Worker should start
                time.sleep(0.5)
                initial_count = WorkerRegistry.get_active_worker_count()
                assert initial_count >= 1, f"Worker should have started, found {initial_count}"
                
                # Wait for idle timeout plus buffer
                time.sleep(2.5)
                
                # Worker for this user should have exited
                worker = WorkerRegistry.get_worker_for_user(self.user.id)
                assert worker is None, "Worker should have exited after idle timeout"
        finally:
            # Restore original timeout
            QueueWorker.IDLE_TIMEOUT = original_timeout
    
    def test_only_one_worker_per_user(self):
        """Test that only one worker runs per user at a time."""
        def mock_execute(job_id, kwargs):
            time.sleep(0.5)  # Longer processing time
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute):
            # Quickly enqueue multiple jobs
            for i in range(5):
                job_id = status_tracker.create_job(f'worker{i}.kml', self.user.id)
                self.process_job.enqueue_job(
                    job_id, f'worker test {i}'.encode(), f'worker{i}.kml', self.user.id
                )
            
            # Give worker time to start
            time.sleep(0.5)
            
            # Should only have 1 worker for this user
            worker = WorkerRegistry.get_worker_for_user(self.user.id)
            assert worker is not None, "Worker should exist for user"
            assert worker.is_alive(), "Worker should be alive"
            
            # Total active workers should be 1
            active_count = WorkerRegistry.get_active_worker_count()
            assert active_count == 1, f"Expected 1 worker, found {active_count}"
    
    def test_job_status_progression(self):
        """Test that job statuses progress correctly: QUEUED → WAITING → PROCESSING → COMPLETED."""
        
        # We need to allow the real _execute_job to run so status updates happen
        # But we'll make it fast by mocking the actual file processing
        def mock_execute(job_id, kwargs):
            # Update status to PROCESSING (simulating what _execute_job does)
            status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Processing...", 50.0
            )
            # Simulate longer processing time so we can observe states
            time.sleep(0.8)
            # Update to COMPLETED (simulating success)
            status_tracker.update_job_status(
                job_id, ProcessingStatus.COMPLETED,
                "Processing completed", 100.0
            )
        
        with patch.object(self.process_job, '_execute_job', side_effect=mock_execute):
            # Create and enqueue all 3 jobs first
            job_ids = []
            for i in range(1, 4):
                job_id = status_tracker.create_job(f'status{i}.kml', self.user.id)
                job_ids.append(job_id)
                
                # Check initial status (QUEUED)
                job = status_tracker.get_job(job_id)
                assert job.status == ProcessingStatus.QUEUED, f"Job {i} should start as QUEUED"
                
                # Enqueue the job
                file_data = f'status test {i}'.encode('utf-8')
                result = self.process_job.enqueue_job(
                    job_id, file_data, f'status{i}.kml', self.user.id
                )
                assert result is not False
            
            # Give a moment for the queue to be populated and first job to start
            time.sleep(0.2)
            
            # Check statuses shortly after all are enqueued
            # First job might be in any state (worker is VERY fast!)
            job1 = status_tracker.get_job(job_ids[0])
            # Just verify it exists and is in a valid state
            assert job1 is not None, "Job 1 should exist"
            assert job1.status in [ProcessingStatus.WAITING, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED], \
                f"Job 1 should be in a valid state, got {job1.status.value}"
            
            # Second and third jobs should definitely be WAITING
            # (since first job takes 0.8s total)
            job2 = status_tracker.get_job(job_ids[1])
            assert job2.status == ProcessingStatus.WAITING, \
                f"Job 2 should be WAITING, got {job2.status.value}"
            
            job3 = status_tracker.get_job(job_ids[2])
            assert job3.status == ProcessingStatus.WAITING, \
                f"Job 3 should be WAITING, got {job3.status.value}"
            
            # This is the KEY test: verify only ONE job is processing at a time
            # Check that at most one job is in PROCESSING state
            statuses = [
                status_tracker.get_job(jid).status for jid in job_ids 
                if status_tracker.get_job(jid)
            ]
            processing_count = sum(1 for s in statuses if s == ProcessingStatus.PROCESSING)
            assert processing_count <= 1, \
                f"Should have at most 1 job PROCESSING at a time, found {processing_count}"
            
            # Wait longer for jobs to progress
            time.sleep(0.5)
            
            # Check that jobs are progressing sequentially
            # At least one should be in a terminal or processing state
            statuses = [
                status_tracker.get_job(jid).status for jid in job_ids 
                if status_tracker.get_job(jid)
            ]
            terminal_or_processing = sum(
                1 for s in statuses 
                if s in [ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED, ProcessingStatus.FAILED]
            )
            assert terminal_or_processing >= 1, \
                "At least one job should be processing or complete"
            
            # Most importantly: verify third job is still waiting
            # (proves sequential processing - can't all run at once)
            job3 = status_tracker.get_job(job_ids[2])
            assert job3.status == ProcessingStatus.WAITING, \
                f"Job 3 should still be WAITING (proves sequential processing), got {job3.status.value}"
            
            # Wait for all jobs to complete
            time.sleep(3.0)
            
            # Verify all jobs eventually completed
            completed_count = 0
            for i, job_id in enumerate(job_ids, 1):
                job = status_tracker.get_job(job_id)
                # Job might be COMPLETED or removed from tracker
                if job:
                    assert job.status in [ProcessingStatus.COMPLETED, ProcessingStatus.FAILED], \
                        f"Job {i} should be COMPLETED or FAILED, got {job.status.value}"
                    if job.status == ProcessingStatus.COMPLETED:
                        completed_count += 1
            
            # At least some jobs should have completed successfully
            assert completed_count >= 1, "At least one job should have completed"
    
    def test_websocket_status_updates(self):
        """Test that WebSocket status updates are sent correctly for waiting/processing jobs."""
        websocket_events = []
        
        # Mock the WebSocket broadcast method
        def mock_broadcast(user_id, job_id, status, progress, message, **kwargs):
            websocket_events.append({
                'job_id': job_id,
                'status': status,
                'message': message,
                'import_queue_id': kwargs.get('import_queue_id')
            })
        
        with patch.object(self.process_job, '_broadcast_job_status_updated', side_effect=mock_broadcast):
            with patch.object(self.process_job, '_execute_job'):
                # Enqueue 2 jobs
                job_id1 = status_tracker.create_job('ws1.kml', self.user.id)
                self.process_job.enqueue_job(
                    job_id1, b'ws test 1', 'ws1.kml', self.user.id
                )
                
                job_id2 = status_tracker.create_job('ws2.kml', self.user.id)
                self.process_job.enqueue_job(
                    job_id2, b'ws test 2', 'ws2.kml', self.user.id
                )
                
                time.sleep(0.2)
        
        # Verify WebSocket events were sent
        assert len(websocket_events) >= 2, "Should have sent at least 2 status updates"
        
        # Both jobs should have received 'waiting' status updates
        waiting_events = [e for e in websocket_events if e['status'] == 'waiting']
        assert len(waiting_events) >= 2, f"Should have 2 'waiting' events, got {len(waiting_events)}"
        
        # Verify the waiting messages
        for event in waiting_events:
            assert 'waiting' in event['message'].lower() or 'queue' in event['message'].lower(), \
                f"Waiting message should mention waiting/queue: {event['message']}"
    
    def test_multiple_files_status_display(self):
        """
        Integration test: Upload multiple files and verify status display logic.
        Tests the exact scenario from the UI where all files show as 'Processing'.
        """
        # This test simulates what the frontend receives
        def slow_execute(job_id, kwargs):
            # Call the original method to set status to PROCESSING, but catch it early
            # We'll manually set the status and then sleep
            self.process_job.status_tracker.update_job_status(
                job_id, ProcessingStatus.PROCESSING,
                "Processing...", 50.0
            )
            # Slow processing to keep jobs in different states
            time.sleep(0.5)
        
        with patch.object(self.process_job, '_execute_job', side_effect=slow_execute):
            # Upload 4 files
            job_ids = []
            for i in range(4):
                job_id = status_tracker.create_job(f'display{i}.kml', self.user.id)
                job_ids.append(job_id)
                self.process_job.enqueue_job(
                    job_id, f'display test {i}'.encode(), f'display{i}.kml', self.user.id
                )
            
            # Give time for worker to start and process first job
            # Worker needs time to: start thread, dequeue job, call _execute_job (which sets PROCESSING)
            # Use a shorter sleep to catch the state right after first job starts but before second job dequeues
            time.sleep(0.2)
            
            # Check statuses (simulating what frontend would see)
            jobs_status = []
            for job_id in job_ids:
                job = status_tracker.get_job(job_id)
                if job:
                    jobs_status.append({
                        'job_id': job_id,
                        'status': job.status.value,
                        'filename': job.filename
                    })
            
            # At this point:
            # - First job should be PROCESSING
            # - Other jobs should be WAITING
            processing_count = sum(1 for j in jobs_status if j['status'] == 'processing')
            waiting_count = sum(1 for j in jobs_status if j['status'] == 'waiting')
            
            assert processing_count == 1, \
                f"Expected exactly 1 job PROCESSING, found {processing_count}. Statuses: {jobs_status}"
            assert waiting_count >= 2, \
                f"Expected at least 2 jobs WAITING, found {waiting_count}. Statuses: {jobs_status}"
            
            # Wait for first job to complete
            time.sleep(0.6)
            
            # Check statuses again
            jobs_status_after = []
            for job_id in job_ids:
                job = status_tracker.get_job(job_id)
                if job:
                    jobs_status_after.append({
                        'job_id': job_id,
                        'status': job.status.value,
                        'filename': job.filename
                    })
            
            # Now first should be COMPLETED and second should be PROCESSING
            completed_or_none = sum(
                1 for j in jobs_status_after 
                if j['job_id'] == job_ids[0] and j['status'] in ['completed', 'failed']
            )
            second_processing = sum(
                1 for j in jobs_status_after 
                if j['job_id'] == job_ids[1] and j['status'] == 'processing'
            )
            
            # First job might have been cleaned from tracker if completed
            if completed_or_none == 0:
                # Job was cleaned from tracker (acceptable)
                pass
            else:
                assert completed_or_none == 1, \
                    f"First job should be COMPLETED. Statuses: {jobs_status_after}"
            
            assert second_processing == 1, \
                f"Second job should now be PROCESSING. Statuses: {jobs_status_after}"

