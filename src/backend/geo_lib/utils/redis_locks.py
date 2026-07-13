"""
Redis-based non-blocking distributed locks.

Unlike the PostgreSQL advisory locks in `geo_lib.utils.advisory_locks`, `try_acquire_lock`
never blocks the caller while waiting: it returns immediately, which is what code running
inside a Celery worker needs, since blocking a worker slot on someone else's lock instead of
picking up other queued work defeats the point of having a worker pool.
"""

from typing import Optional

from redis.lock import Lock

from geo_lib.utils.redis_connection import get_redis_connection


def try_acquire_lock(lock_name: str, timeout_seconds: int) -> Optional[Lock]:
    """
    Attempt to acquire a named, auto-expiring Redis lock without blocking.

    The lock self-expires after `timeout_seconds` even if never released, so a crashed holder
    can never wedge the lock permanently. Callers that acquire a lock are responsible for
    releasing it (ideally in a `finally` block) once their critical section is done.

    Args:
        lock_name: Globally unique name for the lock (used verbatim as the Redis key).
        timeout_seconds: How long the lock is held for before auto-expiring.

    Returns:
        The acquired `Lock` (call `.release()` when done), or None if already held elsewhere.
    """
    lock = get_redis_connection().lock(lock_name, timeout=timeout_seconds)
    return lock if lock.acquire(blocking=False) else None
