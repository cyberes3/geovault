import threading
import contextlib
import logging

from geo_lib.daemon.database.connection import Database

_logger = logging.getLogger("DAEMON").getChild("LOCKING")


def _get_table_oid(cursor, table_name: str):
    # Ensure schema-qualified name to avoid search_path surprises
    if '.' not in table_name:
        table_name = f'public.{table_name}'
    cursor.execute("SELECT %s::regclass::oid::int4", (table_name,))
    return cursor.fetchone()[0]


class DBLockManager:
    """
    Improved database lock manager that uses connection-per-operation pattern
    to avoid race conditions and ensure proper lock cleanup.
    """
    
    def __init__(self, worker_id=None):
        self.worker_id = worker_id
        self._read_only = worker_id is None
        self._table_name_to_oid = {}
        self._active_locks = set()  # Track active locks for cleanup
        self._lock_tracking_lock = threading.Lock()

    @contextlib.contextmanager
    def _with_connection(self):
        """Always use fresh connections for lock operations to ensure proper cleanup."""
        conn = Database.get_connection()
        conn.autocommit = True
        try:
            yield conn
        finally:
            Database.return_connection(conn)

    def _get_oid(self, conn, table_name: str):
        """Get table OID with caching for performance."""
        oid = self._table_name_to_oid.get(table_name)
        if oid is not None:
            return oid
        with conn.cursor() as cur:
            oid = _get_table_oid(cur, table_name)
        self._table_name_to_oid[table_name] = oid
        return oid

    def lock_row(self, table_name: str, primary_key):
        """Acquire an advisory lock on a specific row."""
        if self._read_only:
            raise Exception('Cannot lock row in read-only mode')
        
        lock_key = (table_name, primary_key)
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                cur.execute("SELECT pg_try_advisory_lock(%s::int4, %s::int4)", (oid, int(primary_key)))
                acquired = cur.fetchone()[0]
                
                if acquired:
                    # Track the lock for cleanup
                    with self._lock_tracking_lock:
                        self._active_locks.add(lock_key)
                    _logger.debug(f'Lock acquired for {table_name}:{primary_key} -- {self.worker_id}')
                    return True
                else:
                    _logger.debug(f'Lock failed for {table_name}:{primary_key} -- {self.worker_id}')
                    return False

    def unlock_row(self, table_name: str, primary_key):
        """Release an advisory lock on a specific row."""
        if self._read_only:
            raise Exception('Cannot unlock row in read-only mode')
        
        lock_key = (table_name, primary_key)
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s::int4, %s::int4)", (oid, int(primary_key)))
                released = cur.fetchone()[0]
                
                if released:
                    # Remove from tracking
                    with self._lock_tracking_lock:
                        self._active_locks.discard(lock_key)
                    _logger.debug(f'Lock released for {table_name}:{primary_key} -- {self.worker_id}')
                    return True
                else:
                    _logger.warning(f'Lock release failed for {table_name}:{primary_key} -- {self.worker_id}')
                    return False

    def is_locked(self, table_name: str, primary_key):
        """Check if a row is currently locked (without acquiring the lock)."""
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                # Try to acquire lock temporarily to test if it's available
                cur.execute("SELECT pg_try_advisory_lock(%s::int4, %s::int4)", (oid, int(primary_key)))
                acquired = cur.fetchone()[0]
                if acquired:
                    # Immediately release it since we were just testing
                    cur.execute("SELECT pg_advisory_unlock(%s::int4, %s::int4)", (oid, int(primary_key)))
                    return False  # Not locked (we could acquire it)
                return True  # Locked (we couldn't acquire it)

    def release_all_locks(self):
        """Release all tracked locks. Used during graceful shutdown."""
        with self._lock_tracking_lock:
            locks_to_release = list(self._active_locks)
        
        released_count = 0
        for table_name, primary_key in locks_to_release:
            if self.unlock_row(table_name, primary_key):
                released_count += 1
        
        _logger.info(f'Released {released_count} locks during cleanup -- {self.worker_id}')
        return released_count

    def close(self):
        """Clean up resources and release any remaining locks."""
        self.release_all_locks()

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
