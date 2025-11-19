"""
Background service for cleaning up old orphaned replacement ImportQueue rows.
Runs every minute to remove replacement uploads that are 10 minutes old or older.
"""

import threading
from datetime import timedelta

from django.db import connections
from django.utils import timezone

from api.models import ImportQueue
from geo_lib.logging.console import get_job_logger

logger = get_job_logger()


class ReplacementCleanupService:
    """
    Background service that periodically cleans up old orphaned replacement ImportQueue rows.
    Runs every 60 seconds.
    """

    def __init__(self):
        self._timer = None
        self._running = False
        self._lock = threading.Lock()

    def stop(self):
        """Stop the background cleanup service."""
        with self._lock:
            if not self._running:
                return

            self._running = False
            if self._timer:
                self._timer.cancel()
                self._timer = None
            logger.info("Stopped replacement cleanup service")

    def _schedule_next_run(self):
        """Schedule the next cleanup run in 60 seconds."""
        if not self._running:
            return

        # Cancel any existing timer first (safety check)
        if self._timer is not None:
            try:
                self._timer.cancel()
            except Exception:
                pass

        self._timer = threading.Timer(60.0, self._run_cleanup)
        self._timer.daemon = True
        self._timer.start()

    def _run_cleanup(self):
        """Execute the cleanup logic and schedule the next run."""
        try:
            # Ensure database connection is ready
            connections.close_all()

            # Calculate cutoff time (10 minutes ago)
            cutoff_time = timezone.now() - timedelta(minutes=10)

            # Find orphaned replacement rows
            orphaned_rows = ImportQueue.objects.filter(
                replacement__isnull=False,  # Is a replacement upload
                imported=False,  # Not yet imported/applied
                timestamp__lte=cutoff_time  # 10 minutes old or older
            )

            # Convert to list first to ensure we have all data before deletion
            rows_to_delete = list(orphaned_rows)

            deleted_count, _ = orphaned_rows.delete()

            # Only log if something was actually deleted
            if deleted_count > 0:
                for row in rows_to_delete:
                    age_minutes = (timezone.now() - row.timestamp).total_seconds() / 60
                    logger.info(
                        f'Deleting orphaned replacement ImportQueue row: '
                        f'id={row.id}, replacement_feature_id={row.replacement}, '
                        f'filename="{row.original_filename}", created={row.timestamp}, '
                        f'age_minutes={age_minutes:.1f}'
                    )

        except Exception as e:
            logger.error(f"Error in replacement cleanup service: {str(e)}", exc_info=True)
        finally:
            # Schedule the next run
            if self._running:
                self._schedule_next_run()


# Singleton instance
_cleanup_service = None


def get_cleanup_service():
    """Get the singleton cleanup service instance."""
    global _cleanup_service
    if _cleanup_service is None:
        _cleanup_service = ReplacementCleanupService()
    return _cleanup_service


def ensure_service_started():
    """
    Ensure the cleanup service is started (idempotent).
    
    This function is called from api.apps.DatamanageConfig.ready(), which already
    ensures it's only called in the main process (not the reloader) via the RUN_MAIN
    check. This function provides additional thread-safety to prevent duplicate starts
    if called from multiple threads within the same process.
    
    The service uses a singleton pattern with thread-safe locking to ensure only
    one instance runs, even if this function is called multiple times.
    """
    cleanup_service = get_cleanup_service()
    
    with cleanup_service._lock:
        # Check if service is already running
        if cleanup_service._running or cleanup_service._timer is not None:
            return
        
        # Start the service
        cleanup_service._running = True
        logger.info("Replacement cleanup service started")
        cleanup_service._schedule_next_run()
