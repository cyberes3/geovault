"""
Queue worker service for processing files sequentially per user.
Single worker thread per user ensures FIFO processing order.
"""

import threading
import time
import traceback
from enum import Enum
from typing import Any, Dict, Optional

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.redis_job_storage import store_job_started
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus
from geo_lib.processing.redis_queue import get_processing_queue

_logger = get_tagged_logger()


class WorkerState(Enum):
    """Worker lifecycle states."""
    STARTING = "starting"  # Worker created, thread not yet started
    RUNNING = "running"  # Worker thread is processing jobs
    STOPPING = "stopping"  # Stop signal received, finishing current job
    STOPPED = "stopped"  # Thread has exited cleanly
    FAILED = "failed"  # Thread crashed or failed to start


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
        self._state = WorkerState.STARTING
        self._state_lock = threading.Lock()

    def get_state(self) -> WorkerState:
        """Get current worker state (thread-safe)."""
        with self._state_lock:
            return self._state

    def _transition_state(self, new_state: WorkerState):
        """Transition to new state (thread-safe)."""
        with self._state_lock:
            old_state = self._state
            self._state = new_state
            _logger.debug(f"Worker {self.user_id} state: {old_state.value} -> {new_state.value}")

    def start(self):
        """Start the worker thread."""
        current_state = self.get_state()
        if current_state not in (WorkerState.STARTING, WorkerState.STOPPED, WorkerState.FAILED):
            _logger.warning(f"Worker for user {self.user_id} is already in state {current_state.value}")
            return

        try:
            self.thread = threading.Thread(
                target=self._worker_loop,
                daemon=True,
                name=f"QueueWorker-User{self.user_id}"
            )
            self.thread.start()
            _logger.debug(f"Started queue worker for user {self.user_id}")
        except Exception as e:
            _logger.error(f"Failed to start worker thread for user {self.user_id}: {e}")
            self._transition_state(WorkerState.FAILED)
            raise

    def stop(self):
        """Signal the worker to stop gracefully."""
        current_state = self.get_state()
        if current_state == WorkerState.RUNNING:
            self._transition_state(WorkerState.STOPPING)

    def _worker_loop(self):
        """Main worker loop - processes jobs until idle timeout or stop signal."""
        try:
            # Transition to RUNNING state when thread starts
            self._transition_state(WorkerState.RUNNING)
            _logger.debug(f"Queue worker for user {self.user_id} started processing")

            idle_start = None

            while self.get_state() == WorkerState.RUNNING:
                # Dequeue with 1 second timeout so we can check state frequently
                job_data = self.queue.dequeue(timeout=1)

                if job_data is None:
                    # No job available - check for idle timeout
                    if self.get_state() != WorkerState.RUNNING:
                        break

                    if idle_start is None:
                        idle_start = time.time()
                    elif time.time() - idle_start >= self.IDLE_TIMEOUT:
                        _logger.debug(f"Queue worker for user {self.user_id} idle timeout, exiting")
                        self._transition_state(WorkerState.STOPPING)
                        break
                    continue

                # Got a job - reset idle timer
                idle_start = None

                # Check if we should stop before processing
                if self.get_state() != WorkerState.RUNNING:
                    _logger.debug(f"Queue worker for user {self.user_id} stopping, re-enqueueing job {job_data['job_id']}")
                    self.queue.enqueue(job_data)
                    break

                # Process the job - catch errors to continue with next job
                try:
                    self._process_single_job(job_data)
                except:
                    _logger.error(f"Error processing job {job_data['job_id']} for user {self.user_id}: {traceback.format_exc()}", exc_info=True)
                    # Continue processing next job even if this one failed
        except:
            # Catastrophic failure in worker loop itself (not in job processing)
            _logger.error(f"Catastrophic error in worker loop for user {self.user_id}: {traceback.format_exc()}", exc_info=True)
            self._transition_state(WorkerState.FAILED)
        finally:
            # Ensure we transition to a terminal state
            current_state = self.get_state()
            if current_state not in (WorkerState.STOPPED, WorkerState.FAILED):
                self._transition_state(WorkerState.STOPPED)
            # Unregister this worker
            WorkerRegistry.unregister_worker(self.user_id)

    def _process_single_job(self, job_data: Dict[str, Any]):
        """
        Process a single job from the queue.
        
        Args:
            job_data: Job metadata from Redis queue
        """
        job_id = job_data['job_id']
        _logger.debug(f"Queue worker for user {self.user_id} processing job {job_id}")

        # Set status to PROCESSING immediately when job is dequeued
        # This ensures only one job shows as processing at a time
        self.process_job.status_tracker.update_job_status(
            job_id,
            ProcessingStatus.PROCESSING,
            "Processing...",
            0.0
        )

        # Store job in Redis when it starts processing
        job = self.process_job.status_tracker.get_job(job_id)
        store_job_started(
            job_id=job_id,
            user_id=job.user_id,
            job_type=self.process_job.get_job_type(),
            filename=job.filename,
            created_at=job.created_at,
            import_queue_id=getattr(job, 'import_queue_id', None)
        )

        # Call _execute_job directly (NOT _job_worker) to process synchronously
        # The queue worker thread already provides sequential execution,
        # so we don't need _job_worker to spawn another thread
        self.process_job._execute_job(job_id, job_data)


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
            existing_worker = cls._workers.get(user_id)
            if existing_worker is not None:
                state = existing_worker.get_state()

                # If worker is active (starting or running), don't create another
                if state in (WorkerState.STARTING, WorkerState.RUNNING):
                    _logger.debug(f"Worker for user {user_id} already active (state: {state.value})")
                    return True

                # If worker is stopping, wait for it to finish
                if state == WorkerState.STOPPING:
                    _logger.debug(f"Worker for user {user_id} is stopping, waiting for it to exit")
                    return True

                # Worker is STOPPED or FAILED - remove it and create new one
                _logger.info(f"Removing {state.value} worker for user {user_id}")
                del cls._workers[user_id]

            # Create and start new worker
            try:
                worker = QueueWorker(user_id, process_job_instance)
                # Add to registry BEFORE starting to ensure it's visible immediately
                # This prevents race condition where another thread tries to start
                # a worker before this one is registered
                cls._workers[user_id] = worker
                # Now start the worker (transitions to RUNNING when thread starts)
                worker.start()
                return True
            except:
                _logger.error(f"Failed to start worker for user {user_id}: {traceback.format_exc()}")
                # If worker was added to registry but failed to start, remove it
                if user_id in cls._workers:
                    del cls._workers[user_id]
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
        """Get the number of active workers (STARTING or RUNNING only)."""
        with cls._lock:
            # Clean up stopped/failed/stopping workers
            inactive_workers = [
                user_id for user_id, worker in cls._workers.items()
                if worker.get_state() in (WorkerState.STOPPED, WorkerState.FAILED, WorkerState.STOPPING)
            ]
            for user_id in inactive_workers:
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
            if worker is not None and worker.get_state() in (WorkerState.STOPPED, WorkerState.FAILED):
                # Worker is stopped or failed, remove it
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
