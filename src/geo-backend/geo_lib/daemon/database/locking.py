import threading
import contextlib

from geo_lib.daemon.database.connection import Database


def _get_table_oid(cursor, table_name: str):
    # Ensure schema-qualified name to avoid search_path surprises
    if '.' not in table_name:
        table_name = f'public.{table_name}'
    cursor.execute("SELECT %s::regclass::oid::int4", (table_name,))
    return cursor.fetchone()[0]


class DBLockManager:
    locks_lock = threading.Lock()

    def __init__(self, worker_id=None):
        self.worker_id = worker_id
        self._read_only = worker_id is None
        self._conn = None
        self._table_name_to_oid = {}
        if not self._read_only:
            self._conn = Database.get_connection()
            self._conn.autocommit = True

    @contextlib.contextmanager
    def _with_connection(self):
        if self._read_only:
            conn = Database.get_connection()
            conn.autocommit = True
            try:
                yield conn
            finally:
                Database.return_connection(conn)
        else:
            yield self._conn

    def _get_oid(self, conn, table_name: str):
        oid = self._table_name_to_oid.get(table_name)
        if oid is not None:
            return oid
        with conn.cursor() as cur:
            oid = _get_table_oid(cur, table_name)
        self._table_name_to_oid[table_name] = oid
        return oid

    def lock_row(self, table_name: str, primary_key):
        if self._read_only:
            raise Exception('Cannot lock row in read-only mode')
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                cur.execute("SELECT pg_try_advisory_lock(%s, %s)", (oid, int(primary_key)))
                acquired = cur.fetchone()[0]
                return bool(acquired)

    def unlock_row(self, table_name: str, primary_key):
        if self._read_only:
            raise Exception('Cannot unlock row in read-only mode')
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s, %s)", (oid, int(primary_key)))
                released = cur.fetchone()[0]
                return bool(released)

    def is_locked(self, table_name: str, primary_key):
        with self._with_connection() as conn:
            oid = self._get_oid(conn, table_name)
            with conn.cursor() as cur:
                cur.execute("SELECT pg_try_advisory_lock(%s, %s)", (oid, int(primary_key)))
                acquired = cur.fetchone()[0]
                if acquired:
                    cur.execute("SELECT pg_advisory_unlock(%s, %s)", (oid, int(primary_key)))
                    return False
                return True

    def close(self):
        if self._conn is not None:
            Database.return_connection(self._conn)
            self._conn = None

    def __del__(self):
        try:
            self.close()
        except Exception:
            pass
