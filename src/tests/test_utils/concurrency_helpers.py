"""
Utility functions for concurrent operation testing.

These helpers simplify testing of concurrent operations and race conditions.
"""
import threading
import time
from typing import List, Callable, Any, Dict
from geo_lib.processing.status_tracker import status_tracker, ProcessingStatus


def run_concurrent_operations(
    operations: List[Callable],
    thread_count: int = None
) -> List[Any]:
    """
    Execute multiple operations concurrently using threads.
    
    Args:
        operations: List of callable functions to execute concurrently
        thread_count: Number of threads to use (defaults to len(operations))
    
    Returns:
        List of results from each operation (in order of operations list)
    """
    if thread_count is None:
        thread_count = len(operations)
    
    results = [None] * len(operations)
    errors = [None] * len(operations)
    
    def run_operation(index: int, operation: Callable):
        """Wrapper to capture results and errors."""
        try:
            results[index] = operation()
        except Exception as e:
            errors[index] = e
    
    # Create and start threads
    threads = []
    for i, operation in enumerate(operations):
        thread = threading.Thread(target=run_operation, args=(i, operation))
        threads.append(thread)
        thread.start()
    
    # Wait for all threads to complete
    for thread in threads:
        thread.join()
    
    # Raise first error if any occurred
    for error in errors:
        if error is not None:
            raise error
    
    return results


def assert_race_condition_safe(
    results: List[Dict[str, Any]],
    expected_unique_count: int = 1
) -> None:
    """
    Verify that concurrent operations didn't cause data corruption.
    
    Checks that exactly expected_unique_count unique results were produced,
    indicating proper synchronization (e.g., only one thread created a resource).
    
    Args:
        results: List of result dictionaries from concurrent operations
        expected_unique_count: Expected number of unique successful operations
    
    Raises:
        AssertionError: If data corruption or race condition detected
    """
    # Count unique successful operations
    successful_ops = [r for r in results if r.get('success', False)]
    
    # Count duplicates found
    duplicate_ops = [r for r in results if 'duplicate' in str(r)]
    
    unique_count = len(successful_ops)
    duplicate_count = len(duplicate_ops)
    
    total = unique_count + duplicate_count
    
    assert total == len(results), \
        f"Some operations neither succeeded nor found duplicates: {results}"
    
    assert unique_count == expected_unique_count, \
        f"Expected {expected_unique_count} unique operations, got {unique_count}: {results}"


def wait_for_processing_completion(
    job_id: str,
    timeout: float = 30.0,
    poll_interval: float = 0.1
) -> Dict[str, Any]:
    """
    Wait for a processing job to complete.
    
    Args:
        job_id: The job ID to wait for
        timeout: Maximum time to wait in seconds
        poll_interval: How often to check status in seconds
    
    Returns:
        Final job status dictionary
    
    Raises:
        TimeoutError: If job doesn't complete within timeout
    """
    start_time = time.time()
    
    while time.time() - start_time < timeout:
        job_status = status_tracker.get_job_status(job_id)
        
        if not job_status:
            raise ValueError(f"Job {job_id} not found")
        
        status = job_status.get('status')
        
        if status in [ProcessingStatus.COMPLETED.value, 
                      ProcessingStatus.FAILED.value,
                      ProcessingStatus.CANCELLED.value]:
            return job_status
        
        time.sleep(poll_interval)
    
    raise TimeoutError(f"Job {job_id} did not complete within {timeout}s")


def run_concurrent_with_stagger(
    operation: Callable,
    count: int,
    stagger_ms: float = 1.0
) -> List[Any]:
    """
    Run the same operation multiple times concurrently with a small stagger.
    
    Useful for testing race conditions where you want threads to overlap
    but not start at exactly the same microsecond.
    
    Args:
        operation: Callable to execute multiple times
        count: Number of concurrent executions
        stagger_ms: Milliseconds to wait between starting each thread
    
    Returns:
        List of results from each execution
    """
    results = [None] * count
    errors = [None] * count
    
    def run_operation(index: int):
        """Wrapper to capture results and errors."""
        try:
            results[index] = operation(index)
        except Exception as e:
            errors[index] = e
    
    # Create and start threads with stagger
    threads = []
    for i in range(count):
        thread = threading.Thread(target=run_operation, args=(i,))
        threads.append(thread)
        thread.start()
        
        # Small stagger between thread starts
        if i < count - 1:  # Don't wait after last thread
            time.sleep(stagger_ms / 1000.0)
    
    # Wait for all threads to complete
    for thread in threads:
        thread.join()
    
    # Raise first error if any occurred
    for error in errors:
        if error is not None:
            raise error
    
    return results


def create_test_lock_context():
    """
    Create a threading lock for testing concurrent operations.
    
    Returns:
        threading.Lock instance
    """
    return threading.Lock()


def measure_concurrent_performance(
    operation: Callable,
    thread_count: int,
    iterations_per_thread: int = 1
) -> Dict[str, float]:
    """
    Measure performance of concurrent operations.
    
    Args:
        operation: Callable to execute (should accept thread_id and iteration args)
        thread_count: Number of concurrent threads
        iterations_per_thread: How many times each thread executes the operation
    
    Returns:
        Dictionary with timing metrics:
        - total_time: Total elapsed time
        - avg_time_per_op: Average time per operation
        - ops_per_second: Operations per second throughput
    """
    start_time = time.perf_counter()
    
    def thread_work(thread_id: int):
        """Execute operation multiple times."""
        for iteration in range(iterations_per_thread):
            operation(thread_id, iteration)
    
    threads = []
    for i in range(thread_count):
        thread = threading.Thread(target=thread_work, args=(i,))
        threads.append(thread)
        thread.start()
    
    for thread in threads:
        thread.join()
    
    elapsed = time.perf_counter() - start_time
    total_ops = thread_count * iterations_per_thread
    
    return {
        'total_time': elapsed,
        'avg_time_per_op': elapsed / total_ops,
        'ops_per_second': total_ops / elapsed if elapsed > 0 else 0,
        'total_operations': total_ops
    }

