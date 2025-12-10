"""
Redis-based FIFO queue for sequential file processing per user.
Ensures files are processed in upload order.
"""

import json
import base64
from typing import Optional, Dict, Any
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.logging.console import get_tagged_logger

logger = get_tagged_logger(__name__)


class RedisProcessingQueue:
    """
    Redis-based FIFO queue for file processing jobs.
    Each user has their own queue to ensure sequential processing.
    """
    
    def __init__(self, user_id: int):
        """
        Initialize queue for a specific user.
        
        Args:
            user_id: User ID for queue isolation
        """
        self.user_id = user_id
        self.queue_key = f"processing_queue:user:{user_id}"
        self.redis_client = get_redis_connection()
    
    def enqueue(self, job_data: Dict[str, Any]) -> bool:
        """
        Add a job to the end of the queue (FIFO).
        
        Args:
            job_data: Dictionary containing job information:
                - job_id: str
                - import_queue_id: int
                - filename: str
                - user_id: int
                - file_data: bytes
                - timestamp: float
                - replacement_feature_id: Optional[int]
        
        Returns:
            True if enqueued successfully, False otherwise
        """
        try:
            # Encode file_data as base64 for JSON serialization
            encoded_data = job_data.copy()
            if 'file_data' in encoded_data and isinstance(encoded_data['file_data'], bytes):
                encoded_data['file_data'] = base64.b64encode(encoded_data['file_data']).decode('utf-8')
            
            # Serialize to JSON
            job_json = json.dumps(encoded_data)
            
            # Add to queue (RPUSH adds to tail)
            self.redis_client.rpush(self.queue_key, job_json)
            
            queue_length = self.get_queue_length()
            logger.info(f"Enqueued job {job_data['job_id']} for user {self.user_id} (queue length: {queue_length})")
            
            return True
            
        except Exception as e:
            logger.error(f"Failed to enqueue job {job_data.get('job_id')} for user {self.user_id}: {e}")
            return False
    
    def dequeue(self, timeout: int = 60) -> Optional[Dict[str, Any]]:
        """
        Remove and return the next job from the queue (FIFO).
        Blocks until a job is available or timeout expires.
        
        Args:
            timeout: Seconds to wait for a job (0 = non-blocking)
        
        Returns:
            Job data dictionary or None if timeout/empty
        """
        try:
            # BLPOP returns (key, value) tuple or None
            result = self.redis_client.blpop(self.queue_key, timeout=timeout)
            
            if result is None:
                return None
            
            # result is (key, value)
            _, job_json = result
            
            # Deserialize from JSON
            job_data = json.loads(job_json)
            
            # Decode file_data from base64
            if 'file_data' in job_data and isinstance(job_data['file_data'], str):
                job_data['file_data'] = base64.b64decode(job_data['file_data'])
            
            logger.info(f"Dequeued job {job_data['job_id']} for user {self.user_id}")
            
            return job_data
            
        except Exception as e:
            logger.error(f"Failed to dequeue job for user {self.user_id}: {e}")
            return None
    
    def get_queue_length(self) -> int:
        """
        Get the number of jobs in the queue.
        
        Returns:
            Number of jobs waiting in queue
        """
        try:
            return self.redis_client.llen(self.queue_key)
        except Exception as e:
            logger.error(f"Failed to get queue length for user {self.user_id}: {e}")
            return 0
    
    def clear_queue(self) -> int:
        """
        Remove all jobs from the queue.
        
        Returns:
            Number of jobs removed
        """
        try:
            queue_length = self.get_queue_length()
            if queue_length > 0:
                self.redis_client.delete(self.queue_key)
                logger.info(f"Cleared {queue_length} jobs from queue for user {self.user_id}")
            return queue_length
        except Exception as e:
            logger.error(f"Failed to clear queue for user {self.user_id}: {e}")
            return 0
    
    def peek(self) -> Optional[Dict[str, Any]]:
        """
        View the next job without removing it from the queue.
        
        Returns:
            Job data dictionary or None if queue is empty
        """
        try:
            # LINDEX 0 gets first element without removing
            job_json = self.redis_client.lindex(self.queue_key, 0)
            
            if job_json is None:
                return None
            
            job_data = json.loads(job_json)
            
            # Decode file_data from base64
            if 'file_data' in job_data and isinstance(job_data['file_data'], str):
                job_data['file_data'] = base64.b64decode(job_data['file_data'])
            
            return job_data
            
        except Exception as e:
            logger.error(f"Failed to peek queue for user {self.user_id}: {e}")
            return None


def get_processing_queue(user_id: int) -> RedisProcessingQueue:
    """
    Factory function to get a processing queue for a user.
    
    Args:
        user_id: User ID
    
    Returns:
        RedisProcessingQueue instance
    """
    return RedisProcessingQueue(user_id)

