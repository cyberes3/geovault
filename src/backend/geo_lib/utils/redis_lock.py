"""
Redis-based distributed lock for sequential file processing per user.
Ensures files are processed one at a time per user to prevent duplicate detection race conditions.
"""

import time
import uuid
from typing import Optional

from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.processing.status_tracker import ProcessingStatus, ProcessingStatusTracker
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


class RedisProcessingLock:
    """
    Context manager for Redis-based distributed lock on file processing.
    Ensures only one file is processed at a time per user.
    """
    
    # Hard-coded timeouts (not user-configurable)
    LOCK_EXPIRATION = 300  # 5 minutes - max time to hold lock
    WAIT_TIMEOUT = 600  # 10 minutes - max time to wait for lock
    INITIAL_POLL_INTERVAL = 0.5  # Start with 0.5 second polls
    MAX_POLL_INTERVAL = 5.0  # Max 5 second polls
    
    def __init__(self, user_id: int, job_id: Optional[str] = None, 
                 status_tracker: Optional[ProcessingStatusTracker] = None):
        """
        Initialize the Redis lock.
        
        Args:
            user_id: User ID to create lock for
            job_id: Optional job ID for status updates
            status_tracker: Optional status tracker for broadcasting status
        """
        self.user_id = user_id
        self.job_id = job_id
        self.status_tracker = status_tracker
        self.lock_key = f"processing_lock:user:{user_id}"
        self.lock_value = str(uuid.uuid4())  # Unique value to identify our lock
        self.redis_client = None
        self.acquired = False
        
    def __enter__(self):
        """Acquire the lock, waiting if necessary."""
        self.redis_client = get_redis_connection()
        
        start_time = time.time()
        poll_interval = self.INITIAL_POLL_INTERVAL
        waiting_broadcast = False
        
        while True:
            # Try to acquire the lock
            acquired = self.redis_client.set(
                self.lock_key,
                self.lock_value,
                nx=True,  # Only set if not exists
                ex=self.LOCK_EXPIRATION  # Expiration in seconds
            )
            
            if acquired:
                self.acquired = True
                logger.info(f"Acquired processing lock for user {self.user_id}")
                
                # Update status to PROCESSING if we have job info
                if self.job_id and self.status_tracker:
                    self.status_tracker.update_job_status(
                        self.job_id,
                        ProcessingStatus.PROCESSING,
                        "Processing file..."
                    )
                    self._broadcast_status_update('processing', 'Processing file...')
                
                return self
            
            # Check if we've exceeded wait timeout
            elapsed = time.time() - start_time
            if elapsed > self.WAIT_TIMEOUT:
                logger.error(
                    f"Failed to acquire processing lock for user {self.user_id} "
                    f"after {elapsed:.1f} seconds"
                )
                raise TimeoutError(
                    f"Timeout waiting for processing lock (waited {elapsed:.1f} seconds). "
                    "Another file may be stuck processing."
                )
            
            # Broadcast WAITING status once
            if not waiting_broadcast and self.job_id and self.status_tracker:
                self.status_tracker.update_job_status(
                    self.job_id,
                    ProcessingStatus.WAITING,
                    "Waiting for earlier file to finish processing..."
                )
                self._broadcast_status_update(
                    'waiting',
                    'Waiting for earlier file to finish processing...'
                )
                waiting_broadcast = True
                logger.info(
                    f"Processing lock for user {self.user_id} is held by another job, "
                    f"waiting... (job_id: {self.job_id})"
                )
            
            # Wait before retrying (exponential backoff)
            time.sleep(poll_interval)
            poll_interval = min(poll_interval * 1.5, self.MAX_POLL_INTERVAL)
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """Release the lock."""
        if self.acquired and self.redis_client:
            # Only delete the lock if it's still ours (check value matches)
            # This prevents deleting a lock that was acquired by another process
            # after ours expired
            lua_script = """
            if redis.call("get", KEYS[1]) == ARGV[1] then
                return redis.call("del", KEYS[1])
            else
                return 0
            end
            """
            deleted = self.redis_client.eval(lua_script, 1, self.lock_key, self.lock_value)
            
            if deleted:
                logger.info(f"Released processing lock for user {self.user_id}")
            else:
                logger.warning(
                    f"Processing lock for user {self.user_id} was already released "
                    "(possibly expired)"
                )
            
            self.acquired = False
        
        return False  # Don't suppress exceptions
    
    def _broadcast_status_update(self, status: str, message: str):
        """Broadcast status update via WebSocket."""
        if not self.job_id or not self.status_tracker:
            return
        
        try:
            from channels.layers import get_channel_layer
            from asgiref.sync import async_to_sync
            
            job = self.status_tracker.get_job(self.job_id)
            if not job or not job.import_queue_id:
                return
            
            channel_layer = get_channel_layer()
            if channel_layer:
                async_to_sync(channel_layer.group_send)(
                    f"realtime_{self.user_id}",
                    {
                        'type': 'process_job_status_updated',
                        'data': {
                            'import_queue_id': job.import_queue_id,
                            'status': status,
                            'message': message
                        }
                    }
                )
        except Exception as e:
            logger.warning(f"Failed to broadcast status update: {e}")

