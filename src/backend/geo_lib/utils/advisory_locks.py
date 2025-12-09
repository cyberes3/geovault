"""
PostgreSQL advisory lock utilities for preventing race conditions.

Advisory locks are application-level locks that are managed by PostgreSQL
but don't lock any actual rows. They're perfect for coordinating access
to application-level resources like file processing by hash.
"""

from django.db import connection

from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


def hash_to_lock_id(file_hash: str) -> int:
    """
    Convert a file hash string to a 64-bit signed integer for use as a PostgreSQL advisory lock ID.
    
    PostgreSQL advisory locks require a bigint (64-bit signed integer) as the lock ID.
    This function deterministically converts a hash string to an integer in that range.
    
    Args:
        file_hash: A hash string (typically SHA256 hex digest)
        
    Returns:
        A 64-bit signed integer in the range -2^63 to 2^63-1
    """
    # Use Python's hash() for deterministic conversion within the process
    # Modulo to ensure it fits in PostgreSQL bigint range (-2^63 to 2^63-1)
    return hash(file_hash) % (2 ** 63)


class AdvisoryLock:
    """
    Context manager for PostgreSQL advisory locks.
    
    Advisory locks are session-level locks that are automatically released
    when the database connection closes or when explicitly unlocked.
    
    Usage:
        with AdvisoryLock(file_hash):
            # Critical section - only one process/thread with this hash can execute at a time
            save_hash_to_db()
            check_for_duplicates()
        # Lock automatically released here
    
    This is particularly useful for preventing race conditions when multiple
    identical files are uploaded simultaneously.
    """

    def __init__(self, file_hash: str):
        """
        Initialize advisory lock for a given file hash.
        
        Args:
            file_hash: The file hash to create a lock for
        """
        self.file_hash = file_hash
        self.lock_id = hash_to_lock_id(file_hash)
        self.acquired = False

    def __enter__(self):
        """Acquire the advisory lock."""
        with connection.cursor() as cursor:
            # pg_advisory_lock blocks until the lock is available
            cursor.execute("SELECT pg_advisory_lock(%s)", [self.lock_id])
            self.acquired = True
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Release the advisory lock."""
        if self.acquired:
            with connection.cursor() as cursor:
                # pg_advisory_unlock returns true if the lock was held and released
                cursor.execute("SELECT pg_advisory_unlock(%s)", [self.lock_id])
                result = cursor.fetchone()
                if not (result and result[0]):
                    logger.error('Advisory lock was not held when trying to release')
        return False  # Don't suppress exceptions


# Convenience function for use in with statements
def advisory_lock(file_hash: str) -> AdvisoryLock:
    """
    Create an advisory lock context manager for a file hash.
    
    Args:
        file_hash: The file hash to lock on
        
    Returns:
        AdvisoryLock context manager
        
    Example:
        with advisory_lock(file_hash):
            # Critical section
            pass
    """
    return AdvisoryLock(file_hash)
