import json
import logging
import time
import traceback
from uuid import uuid4

from psycopg2.extras import RealDictCursor

from geo_lib.daemon.database.connection import CursorFromConnectionFromPool
from geo_lib.daemon.database.locking import DBLockManager
from geo_lib.daemon.workers.workers_lib.importer.kml import kml_to_geojson
from geo_lib.logging.database import log_to_db, DatabaseLogLevel, DatabaseLogSource
from geo_lib.time import get_time_ms
from geo_lib.types.feature import geojson_to_geofeature

_SQL_GET_UNPROCESSED_ITEMS = "SELECT * FROM public.data_importqueue WHERE geofeatures = '[]'::jsonb AND imported = false AND unparsable = false ORDER BY id ASC LIMIT 25"
_SQL_INSERT_PROCESSED_ITEM = "UPDATE public.data_importqueue SET geofeatures = %s WHERE id = %s"
_SQL_MARK_UNPARSABLE = "UPDATE public.data_importqueue SET unparsable = true WHERE id = %s"
_SQL_DELETE_ITEM = "DELETE FROM public.data_importqueue WHERE id = %s"

_logger = logging.getLogger("DAEMON").getChild("IMPORTER")


# TODO: support multiple workers


def _fetch_candidate_items():
    with CursorFromConnectionFromPool(cursor_factory=RealDictCursor) as cursor:
        cursor.execute(_SQL_GET_UNPROCESSED_ITEMS)
        return cursor.fetchall()


def _process_item(item, lock_manager: DBLockManager, worker_id: str):
    if not lock_manager.lock_row('data_importqueue', item['id']):
        _logger.debug(f'Skip locked item #{item["id"]} -- {worker_id}')
        return False

    _logger.debug(f'Acquired lock for item #{item["id"]} -- {worker_id}')
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
            with CursorFromConnectionFromPool(cursor_factory=RealDictCursor) as cursor:
                cursor.execute(_SQL_MARK_UNPARSABLE, (item['id'],))
                # Also mark as processed with error indicator to show it's finished
                error_geofeatures = json.dumps([{"error": "unparsable", "message": err_msg}])
                cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (error_geofeatures, item['id']))
        else:
            # For other types of errors, still mark as processed to show it's finished
            # but don't mark as unparsable (might be retryable)
            with CursorFromConnectionFromPool(cursor_factory=RealDictCursor) as cursor:
                error_geofeatures = json.dumps([{"error": "processing_failed", "message": err_msg}])
                cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (error_geofeatures, item['id']))
        
        traceback.print_exc()
        time.sleep(2)

    # Log processing completion
    log_to_db(f'Processing finished {"un" if not success else ""}successfully', level=DatabaseLogLevel.INFO, user_id=item['user_id'], source=DatabaseLogSource.IMPORT, log_id=log_id)
    
    # Only update geofeatures if we haven't already done so in the error handling
    if success:
        features_json = [json.loads(x.model_dump_json()) for x in geo_features]
        with CursorFromConnectionFromPool(cursor_factory=RealDictCursor) as cursor:
            data = json.dumps(features_json)
            cursor.execute(_SQL_INSERT_PROCESSED_ITEM, (data, item['id']))

    lock_manager.unlock_row('data_importqueue', item['id'])
    _logger.info(f'Finished item #{item["id"]} success={success} in {round((get_time_ms() - start_ms) / 1000, 2)}s -- {worker_id}')
    return True


def import_worker():
    worker_id = str(uuid4())
    lock_manager = DBLockManager(worker_id)
    while True:
        processed_one = False
        import_queue_items = _fetch_candidate_items()
        total_candidates = len(import_queue_items)
        for item in import_queue_items:
            if _process_item(item, lock_manager, worker_id):
                processed_one = True
                break

        if not processed_one:
            if total_candidates:
                _logger.info(f'No lock acquired from {total_candidates} candidates; retrying after backoff -- {worker_id}')
            time.sleep(5)
