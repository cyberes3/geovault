import traceback

from api.models import DatabaseLogging, ImportQueue
from geo_lib.logging.console import get_job_logger

_logger = get_job_logger()


def delete_associated_logs(import_queue_item: ImportQueue, delete_job_id: str):
    """
    Delete all logs associated with the import queue item.
    """
    try:
        if import_queue_item.log_id:
            deleted_count = DatabaseLogging.objects.filter(log_id=import_queue_item.log_id).delete()[0]
            _logger.info(f"Deleted {deleted_count} log entries for item {import_queue_item.id}")
    except:
        _logger.warning(f"Error deleting logs for item {import_queue_item.id}: {traceback.format_exc()}")
        # Don't fail the delete job for this, just log the warning
