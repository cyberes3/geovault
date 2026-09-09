from redis.exceptions import LockError

from geo_lib.processing.jobs.process_job.dispatch import ImportLockContention
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.utils.redis_locks import try_acquire_lock


def user_import_lock_name(user_id: int) -> str:
    return f"import_processing_lock:user:{user_id}"


def acquire_user_import_lock(user_id: int, timeout_seconds: int):
    lock = try_acquire_lock(user_import_lock_name(user_id), timeout_seconds=timeout_seconds)
    if lock is None:
        raise ImportLockContention(f"Another import job is already processing for user {user_id}")
    return lock


def is_user_import_lock_held(user_id: int) -> bool:
    return bool(get_redis_connection().exists(user_import_lock_name(user_id)))


def release_lock(lock) -> None:
    try:
        lock.release()
    except LockError:
        pass
