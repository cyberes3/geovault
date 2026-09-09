"""
PostgreSQL advisory lock utilities for preventing race conditions.
"""

import hashlib

from django.db import connection

from geo_lib.logging.console import get_tagged_logger

logger = get_tagged_logger()


def hash_to_lock_id(user_id: int, file_hash: str) -> int:
    digest = hashlib.sha256(f"{user_id}:{file_hash}".encode('utf-8')).digest()
    return int.from_bytes(digest[:8], byteorder='big', signed=True)


class AdvisoryLock:
    def __init__(self, user_id: int, file_hash: str):
        self.user_id = user_id
        self.file_hash = file_hash
        self.lock_id = hash_to_lock_id(user_id, file_hash)
        self.acquired = False
        self._connection = None

    def __enter__(self):
        self._connection = connection
        cursor = self._connection.cursor()
        try:
            cursor.execute("SELECT pg_advisory_lock(%s)", [self.lock_id])
            self.acquired = True
        finally:
            cursor.close()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.acquired:
            if self._connection is None:
                self._connection = connection
            cursor = self._connection.cursor()
            try:
                cursor.execute("SELECT pg_advisory_unlock(%s)", [self.lock_id])
                result = cursor.fetchone()
                if not (result and result[0]):
                    logger.error('Advisory lock was not held when trying to release')
            finally:
                cursor.close()
        return False


def advisory_lock(user_id: int, file_hash: str) -> AdvisoryLock:
    return AdvisoryLock(user_id, file_hash)
