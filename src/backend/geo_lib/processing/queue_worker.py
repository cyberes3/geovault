"""
Queue worker service for processing files sequentially per user.
Single worker thread per user ensures FIFO processing order.
"""

import threading
import time
from typing import Dict, Optional
from geo_lib.processing.redis_queue import get_processing_queue
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


class QueueWorker:
    """
    Worker thread that processes jobs from a user's queue sequentially.
    Exits after idle timeout when queue is empty.
    """
    
    IDLE_TIMEOUT = 60  # Seconds to wait before exiting when queue is empty
    
    def __init__(self, user_id: int, process_job_instance):
        """
        Initialize worker for a specific user.
        
        Args:
            user_id: User ID
            process_job_instance: ProcessJob instance to use for processing
        """
        self.user_id = user_id
        self.process_job = process_job_instance
        self.queue = get_processing_queue(user_id)
        self.thread = None
        self.running = False
        self.should_stop = False
    
    def start(self):
        """Start the worker thread."""
        if self.thread is not None and self.thread.is_alive():
            logger.warning(f"Worker for user {self.user_id} is already running")
            return
        
        self.running = True
        self.should_stop = False
        self.thread = threading.Thread(
            target=self._worker_loop,
            daemon=True,
            name=f"QueueWorker-User{self.user_id}"
        )
        self.thread.start()
        logger.info(f"Started queue worker for user {self.user_id}")
    
    def stop(self):
        """Signal the worker to stop gracefully."""
        self.should_stop = True
    
    def is_alive(self) -> bool:
        """Check if worker thread is running."""
        return self.thread is not None and self.thread.is_alive()
    
    def _worker_loop(self):
        """Main worker loop - processes jobs until idle timeout or stop signal."""
        try:
            logger.info(f"Queue worker for user {self.user_id} started processing")
            
            while not self.should_stop:
                try:
                    # Use shorter timeout so we can check should_stop more frequently
                    # Dequeue with 1 second timeout instead of full IDLE_TIMEOUT
                    job_data = self.queue.dequeue(timeout=1)
                    
                    if job_data is None:
                        # Check if we should stop
                        if self.should_stop:
                            break
                        
                        # Track idle time manually
                        if not hasattr(self, '_idle_start'):
                            self._idle_start = time.time()
                        elif time.time() - self._idle_start >= self.IDLE_TIMEOUT:
                            # Idle timeout - exit worker
                            logger.info(f"Queue worker for user {self.user_id} idle timeout, exiting")
                            break
                        # Continue loop to check for jobs again
                        continue
                    
                    # Reset idle timer when we get a job
                    if hasattr(self, '_idle_start'):
                        delattr(self, '_idle_start')
                    
                    if self.should_stop:
                        # Stop signal received, re-enqueue job and exit
                        logger.info(f"Queue worker for user {self.user_id} stopping, re-enqueueing job {job_data['job_id']}")
                        self.queue.enqueue(job_data)
                        break
                    
                    # Process the job
                    try:
                        logger.info(f"Queue worker for user {self.user_id} processing job {job_data['job_id']}")
                        self.process_job.process_from_queue(job_data)
                    except Exception as e:
                        logger.error(f"Error processing job {job_data['job_id']} for user {self.user_id}: {e}", exc_info=True)
                        # Continue processing next job even if this one failed
                
                except Exception as e:
                    logger.error(f"Error in worker loop for user {self.user_id}: {e}", exc_info=True)
                    # Small delay before retrying to avoid tight error loop
                    time.sleep(1)
        finally:
            self.running = False
            # Unregister this worker
            WorkerRegistry.unregister_worker(self.user_id)


class WorkerRegistry:
    """
    Thread-safe registry of active queue workers.
    Ensures only one worker per user at a time.
    """
    
    _workers: Dict[int, QueueWorker] = {}
    _lock = threading.RLock()
    
    @classmethod
    def start_worker_for_user(cls, user_id: int, process_job_instance) -> bool:
        """
        Start a worker for a user if not already running.
        
        Args:
            user_id: User ID
            process_job_instance: ProcessJob instance
        
        Returns:
            True if worker was started or already running, False on error
        """
        with cls._lock:
            # Check if worker already exists and is alive
            existing_worker = cls._workers.get(user_id)
            if existing_worker is not None:
                if existing_worker.is_alive():
                    logger.debug(f"Worker for user {user_id} already running")
                    return True
                else:
                    # Worker thread died, remove it
                    logger.info(f"Removing dead worker for user {user_id}")
                    del cls._workers[user_id]
            
            # Create and start new worker
            try:
                worker = QueueWorker(user_id, process_job_instance)
                worker.start()
                cls._workers[user_id] = worker
                return True
            except Exception as e:
                logger.error(f"Failed to start worker for user {user_id}: {e}")
                return False
    
    @classmethod
    def unregister_worker(cls, user_id: int):
        """
        Remove a worker from the registry.
        Called by worker when it exits.
        
        Args:
            user_id: User ID
        """
        with cls._lock:
            if user_id in cls._workers:
                del cls._workers[user_id]

    @classmethod
    def stop_all_workers(cls):
        """
        Signal all workers to stop gracefully.
        Called during shutdown.
        """
        with cls._lock:
            for user_id, worker in cls._workers.items():
                worker.stop()
    
    @classmethod
    def get_active_worker_count(cls) -> int:
        """Get the number of active workers."""
        with cls._lock:
            # Clean up dead workers
            dead_workers = [
                user_id for user_id, worker in cls._workers.items()
                if not worker.is_alive()
            ]
            for user_id in dead_workers:
                del cls._workers[user_id]
            
            return len(cls._workers)
    
    @classmethod
    def get_worker_for_user(cls, user_id: int) -> Optional[QueueWorker]:
        """
        Get the active worker for a user.
        
        Args:
            user_id: User ID
        
        Returns:
            QueueWorker instance or None
        """
        with cls._lock:
            worker = cls._workers.get(user_id)
            if worker is not None and not worker.is_alive():
                # Worker died, remove it
                del cls._workers[user_id]
                return None
            return worker


def start_worker_for_user(user_id: int, process_job_instance) -> bool:
    """
    Convenience function to start a worker for a user.
    
    Args:
        user_id: User ID
        process_job_instance: ProcessJob instance
    
    Returns:
        True if worker was started or already running
    """
    return WorkerRegistry.start_worker_for_user(user_id, process_job_instance)


def stop_all_workers(wait_timeout: float = 2.0):
    """
    Convenience function to stop all workers.
    Called during application shutdown.
    
    Args:
        wait_timeout: Seconds to wait for workers to stop (default: 2.0)
    """
    WorkerRegistry.stop_all_workers()
    
    # Wait for workers to actually stop
    if wait_timeout > 0:
        start_time = time.time()
        while time.time() - start_time < wait_timeout:
            if WorkerRegistry.get_active_worker_count() == 0:
                break
            time.sleep(0.1)

