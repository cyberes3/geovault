import json
import logging
import threading
import time
import traceback
from uuid import uuid4

from psycopg2.extras import RealDictCursor

from geo_lib.daemon.database.connection import CursorFromConnectionFromPool, Database
from geo_lib.daemon.database.locking import DBLockManager
from geo_lib.daemon.workers.workers_lib.importer.kml import kml_to_geojson
from geo_lib.logging.database import log_to_db, DatabaseLogLevel, DatabaseLogSource
from geo_lib.time import get_time_ms
from geo_lib.types.feature import geojson_to_geofeature

# SQL queries for atomic claim-and-process pattern
_SQL_CLAIM_AND_LOCK_ITEM = """
    SELECT * FROM public.data_importqueue 
    WHERE geofeatures = '[]'::jsonb 
    AND imported = false 
    AND unparsable = false 
    AND pg_try_advisory_lock('public.data_importqueue'::regclass::oid::int4, id::int4)
    ORDER BY id ASC 
    LIMIT 1
"""
_SQL_INSERT_PROCESSED_ITEM = "UPDATE public.data_importqueue SET geofeatures = %s WHERE id = %s"
_SQL_MARK_UNPARSABLE = "UPDATE public.data_importqueue SET unparsable = true WHERE id = %s"
_SQL_DELETE_ITEM = "DELETE FROM public.data_importqueue WHERE id = %s"
_SQL_RELEASE_LOCK = "SELECT pg_advisory_unlock('public.data_importqueue'::regclass::oid::int4, %s::int4)"

_logger = logging.getLogger("DAEMON").getChild("IMPORTER")


def _claim_and_lock_item():
    """
    Atomically claim and lock a single item for processing.
    This prevents race conditions where multiple workers try to process the same item.
    Returns a tuple of (item_data, connection) where connection must be kept alive
    until processing is complete to maintain the lock.
    """
    conn = Database.get_connection()
    conn.autocommit = True
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute(_SQL_CLAIM_AND_LOCK_ITEM)
            item = cursor.fetchone()
            if item:
                return item, conn
            else:
                Database.return_connection(conn)
                return None, None
    except Exception:
        Database.return_connection(conn)
        raise


def _process_item(item, connection, worker_id: str):
    """
    Process a single item that has already been locked.
    The lock is automatically released when the database connection closes.
    """
    _logger.debug(f'Processing locked item #{item["id"]} -- {worker_id}')
    start_ms = get_time_ms()
    success = False
    log_id = str(item['log_id'])
    
    # Log processing start
    log_to_db('Processing start', level=DatabaseLogLevel.INFO, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
    _logger.info(f'Start processing item #{item["id"]} -- {worker_id}')

    try:
        geojson_data, kml_conv_messages = kml_to_geojson(item['raw_kml'])
        # Log KML conversion messages
        for msg in kml_conv_messages.get():
            log_to_db(msg.msg, level=DatabaseLogLevel.INFO, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
        
        geo_features, typing_messages = geojson_to_geofeature(geojson_data)
        # Log typing messages
        for msg in typing_messages.get():
            log_to_db(msg.msg, level=DatabaseLogLevel.INFO, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
        
        success = True
    except Exception as e:
        err_name = e.__class__.__name__
        err_msg = str(e) if not hasattr(e, 'message') else e.message
        msg = f'Failed to import item #{item["id"]} "{item["original_filename"]}", encountered {err_name}. {err_msg}'
        log_to_db(f'{err_name}: {err_msg}', level=DatabaseLogLevel.ERROR, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
        log_to_db(msg, level=DatabaseLogLevel.ERROR, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
        
        # Check if this is a KML parsing error and mark as unparsable
        if "KML parsing failed" in err_msg or "Failed to parse KML content" in err_msg:
            log_to_db(f'Marking file as unparsable to prevent future retries', level=DatabaseLogLevel.WARNING, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
            with connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute(_SQL_MARK_UNPARSABLE, (item['id'],))
                # Also mark as processed with error indicator to show it's finished
                error_geofeatures = json.dumps([{"error": "unparsable", "message": err_msg}])
                cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (error_geofeatures, item['id']))
        else:
            # For other types of errors, still mark as processed to show it's finished
            # but don't mark as unparsable (might be retryable)
            with connection.cursor(cursor_factory=RealDictCursor) as cursor:
                error_geofeatures = json.dumps([{"error": "processing_failed", "message": err_msg}])
                cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (error_geofeatures, item['id']))
        
        traceback.print_exc()
        time.sleep(2)

    # Log processing completion
    log_to_db(f'Processing finished {"un" if not success else ""}successfully', level=DatabaseLogLevel.INFO, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
    
    # Only update geofeatures if we haven't already done so in the error handling
    if success:
        features_json = [json.loads(x.model_dump_json()) for x in geo_features]
        with connection.cursor(cursor_factory=RealDictCursor) as cursor:
            data = json.dumps(features_json)
            cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (data, item['id']))

    # The lock will be automatically released when the connection is returned to the pool
    _logger.info(f'Finished item #{item["id"]} success={success} in {round((get_time_ms() - start_ms) / 1000, 2)}s -- {worker_id}')
    return True


class ImportWorker:
    """
    Improved import worker with proper shutdown handling and race condition prevention.
    """
    
    def __init__(self):
        self.worker_id = str(uuid4())
        self.lock_manager = DBLockManager(self.worker_id)
        self.shutdown_requested = threading.Event()
        self.shutdown_completed = threading.Event()
        _logger.info(f'Import worker {self.worker_id} initialized')

    def _graceful_shutdown(self):
        """Perform graceful shutdown with lock cleanup."""
        _logger.info(f'Starting graceful shutdown -- {self.worker_id}')
        
        # Release any remaining locks
        released_count = self.lock_manager.release_all_locks()
        if released_count > 0:
            _logger.warning(f'Released {released_count} locks during shutdown -- {self.worker_id}')
        
        # Close the lock manager
        self.lock_manager.close()
        _logger.info(f'Graceful shutdown completed -- {self.worker_id}')
        
        # Signal that shutdown is complete
        self.shutdown_completed.set()

    def run(self):
        """Main worker loop with improved race condition handling."""
        _logger.info(f'Import worker {self.worker_id} started')
        
        try:
            while not self.shutdown_requested.is_set():
                # Try to claim and lock one item atomically
                item, connection = _claim_and_lock_item()
                
                if item and connection:
                    # We successfully claimed and locked an item
                    try:
                        _process_item(item, connection, self.worker_id)
                    finally:
                        # Always return the connection to the pool to release the lock
                        Database.return_connection(connection)
                else:
                    # No items available, wait before trying again
                    if self.shutdown_requested.wait(timeout=5.0):
                        break  # Shutdown requested during wait
                    
        except Exception as e:
            _logger.error(f'Unexpected error in worker loop -- {self.worker_id}: {e}')
            traceback.print_exc()
        finally:
            self._graceful_shutdown()


def create_import_worker():
    """
    Factory function to create a new import worker.
    Returns the worker instance for external management.
    """
    return ImportWorker()