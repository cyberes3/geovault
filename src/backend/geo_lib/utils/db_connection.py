"""
Return Django DB connections to the pool outside the request/response cycle.

Django only auto-closes connections for the request thread. Background threads,
ThreadPoolExecutor workers, and similar entry points must return pooled connections
themselves or they leak until psycopg_pool raises PoolTimeout.

Use `@ensure_db_connection_cleanup` (or `with db_connection_cleanup():`) on those
entry points instead of calling `close_old_connections()` by hand.

For async WebSocket/consumer ORM work, use `channels.db.database_sync_to_async` —
it already wraps connection cleanup around each call. Celery task boundaries are
handled by Django's Celery fixup.
"""
from contextlib import contextmanager
from functools import wraps
from typing import Callable, Iterator, TypeVar

from django.db import close_old_connections

F = TypeVar('F', bound=Callable)


@contextmanager
def db_connection_cleanup() -> Iterator[None]:
    """Close/return any thread-local DB connection before and after the block."""
    close_old_connections()
    try:
        yield
    finally:
        close_old_connections()


def ensure_db_connection_cleanup(func: F) -> F:
    """
    Decorator for thread/executor entry points that may touch the ORM.

    Apply once at the worker boundary; nested DB calls do not need their own cleanup.
    """
    @wraps(func)
    def wrapper(*args, **kwargs):
        with db_connection_cleanup():
            return func(*args, **kwargs)

    return wrapper  # type: ignore[return-value]
