"""
Tests for Redis-based processing queue.
"""

import pytest
import time
from geo_lib.processing.redis_queue import RedisProcessingQueue, get_processing_queue
from geo_lib.utils.redis_connection import get_redis_connection


@pytest.fixture
def redis_client():
    """Get Redis client for cleanup."""
    return get_redis_connection()


@pytest.fixture
def clean_redis(redis_client):
    """Clean up Redis before and after tests."""
    # Cleanup before test
    keys = redis_client.keys('processing_queue:*')
    if keys:
        redis_client.delete(*keys)
    
    yield
    
    # Cleanup after test
    keys = redis_client.keys('processing_queue:*')
    if keys:
        redis_client.delete(*keys)


@pytest.mark.django_db
class TestRedisProcessingQueue:
    """Test Redis processing queue functionality."""
    
    def test_enqueue_dequeue_basic(self, clean_redis):
        """Test basic enqueue and dequeue operations."""
        queue = get_processing_queue(user_id=1)
        
        job_data = {
            'job_id': 'test-job-1',
            'import_queue_id': 123,
            'filename': 'test.kml',
            'user_id': 1,
            'timestamp': time.time(),
            'replacement_feature_id': None
        }
        
        # Enqueue job
        success = queue.enqueue(job_data)
        assert success
        
        # Verify queue length
        assert queue.get_queue_length() == 1
        
        # Dequeue job (non-blocking)
        dequeued_job = queue.dequeue(timeout=0)
        assert dequeued_job is not None
        assert dequeued_job['job_id'] == 'test-job-1'
        assert dequeued_job['filename'] == 'test.kml'
        
        # Queue should be empty now
        assert queue.get_queue_length() == 0
    
    def test_fifo_ordering(self, clean_redis):
        """Test that jobs are processed in FIFO order."""
        queue = get_processing_queue(user_id=1)
        
        # Enqueue multiple jobs
        for i in range(5):
            job_data = {
                'job_id': f'test-job-{i}',
                'import_queue_id': 100 + i,
                'filename': f'test{i}.kml',
                'user_id': 1,
                'timestamp': time.time() + i,  # Incrementing timestamp
                'replacement_feature_id': None
            }
            queue.enqueue(job_data)
        
        # Verify queue length
        assert queue.get_queue_length() == 5
        
        # Dequeue all jobs and verify order
        for i in range(5):
            job = queue.dequeue(timeout=0)
            assert job is not None
            assert job['job_id'] == f'test-job-{i}'
            assert job['filename'] == f'test{i}.kml'
        
        # Queue should be empty
        assert queue.get_queue_length() == 0
    
    def test_queue_isolation(self, clean_redis):
        """Test that queues are isolated per user."""
        queue1 = get_processing_queue(user_id=1)
        queue2 = get_processing_queue(user_id=2)
        
        # Enqueue jobs for different users
        job1 = {
            'job_id': 'user1-job',
            'import_queue_id': 1,
            'filename': 'user1.kml',
            'user_id': 1,
            'timestamp': time.time(),
            'replacement_feature_id': None
        }
        job2 = {
            'job_id': 'user2-job',
            'import_queue_id': 2,
            'filename': 'user2.kml',
            'user_id': 2,
            'timestamp': time.time(),
            'replacement_feature_id': None
        }
        
        queue1.enqueue(job1)
        queue2.enqueue(job2)
        
        # Verify separate queues
        assert queue1.get_queue_length() == 1
        assert queue2.get_queue_length() == 1
        
        # Dequeue from user 1
        dequeued1 = queue1.dequeue(timeout=0)
        assert dequeued1['job_id'] == 'user1-job'
        assert queue1.get_queue_length() == 0
        
        # User 2 queue should be unaffected
        assert queue2.get_queue_length() == 1
        
        # Dequeue from user 2
        dequeued2 = queue2.dequeue(timeout=0)
        assert dequeued2['job_id'] == 'user2-job'
        assert queue2.get_queue_length() == 0
    
    def test_dequeue_timeout(self, clean_redis):
        """Test dequeue timeout on empty queue."""
        queue = get_processing_queue(user_id=1)
        
        # Try to dequeue from empty queue with 1 second timeout
        start_time = time.time()
        job = queue.dequeue(timeout=1)
        elapsed = time.time() - start_time
        
        # Should return None and wait approximately 1 second
        assert job is None
        assert 0.9 <= elapsed <= 1.5  # Allow some margin
    
    def test_peek(self, clean_redis):
        """Test peeking at the next job without removing it."""
        queue = get_processing_queue(user_id=1)
        
        job_data = {
            'job_id': 'peek-job',
            'import_queue_id': 456,
            'filename': 'peek.kml',
            'user_id': 1,
            'timestamp': time.time(),
            'replacement_feature_id': None
        }
        
        queue.enqueue(job_data)
        
        # Peek at job
        peeked_job = queue.peek()
        assert peeked_job is not None
        assert peeked_job['job_id'] == 'peek-job'
        
        # Queue should still have the job
        assert queue.get_queue_length() == 1
        
        # Peek again should return same job
        peeked_again = queue.peek()
        assert peeked_again['job_id'] == 'peek-job'
        
        # Now dequeue
        dequeued = queue.dequeue(timeout=0)
        assert dequeued['job_id'] == 'peek-job'
        assert queue.get_queue_length() == 0
        
        # Peek on empty queue should return None
        assert queue.peek() is None
    
    def test_clear_queue(self, clean_redis):
        """Test clearing all jobs from queue."""
        queue = get_processing_queue(user_id=1)
        
        # Enqueue multiple jobs
        for i in range(3):
            job_data = {
                'job_id': f'clear-job-{i}',
                'import_queue_id': 700 + i,
                'filename': f'clear{i}.kml',
                'user_id': 1,
                'timestamp': time.time(),
                'replacement_feature_id': None
            }
            queue.enqueue(job_data)
        
        assert queue.get_queue_length() == 3
        
        # Clear queue
        cleared_count = queue.clear_queue()
        assert cleared_count == 3
        assert queue.get_queue_length() == 0
        
        # Clear empty queue should return 0
        cleared_again = queue.clear_queue()
        assert cleared_again == 0
    
    def test_binary_file_data(self, clean_redis):
        """Test enqueueing and dequeueing job metadata (file_data is not stored in Redis)."""
        queue = get_processing_queue(user_id=1)
        
        # Note: file_data is NOT stored in Redis - it's read from the database when processing.
        # This test verifies that job metadata can be enqueued/dequeued successfully.
        job_data = {
            'job_id': 'binary-job',
            'import_queue_id': 999,
            'filename': 'binary.kmz',
            'user_id': 1,
            'timestamp': time.time(),
            'replacement_feature_id': None
        }
        
        queue.enqueue(job_data)
        dequeued = queue.dequeue(timeout=0)
        
        assert dequeued is not None
        assert dequeued['job_id'] == 'binary-job'
        assert dequeued['filename'] == 'binary.kmz'
        # file_data is not in the dequeued data - it's read from database during processing
    
    def test_replacement_feature_id(self, clean_redis):
        """Test enqueueing jobs with replacement feature ID."""
        queue = get_processing_queue(user_id=1)
        
        job_data = {
            'job_id': 'replacement-job',
            'import_queue_id': 888,
            'filename': 'replacement.kml',
            'user_id': 1,
            'timestamp': time.time(),
            'replacement_feature_id': 12345
        }
        
        queue.enqueue(job_data)
        dequeued = queue.dequeue(timeout=0)
        
        assert dequeued['replacement_feature_id'] == 12345

