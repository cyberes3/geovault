"""
Business logic for cleaning up old orphaned replacement ImportQueue rows.

The Celery task wrapper lives in `api.tasks` (registration/scheduling concern); this module
only owns the actual cleanup query, kept separately so it stays independently unit-testable.
"""

from datetime import timedelta

from django.utils import timezone

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger("ReplacementCleanupService")


def cleanup_orphaned_replacements() -> int:
    """
    Delete replacement-upload rows that were never imported and are >= 10 minutes old.

    Returns:
        Number of deleted rows.
    """
    cutoff_time = timezone.now() - timedelta(minutes=10)

    orphaned_rows = ImportQueue.objects.filter(
        replacement__isnull=False,
        imported=False,
        timestamp__lte=cutoff_time,
    )

    rows_to_delete = list(orphaned_rows)
    deleted_count, _ = orphaned_rows.delete()

    if deleted_count > 0:
        for row in rows_to_delete:
            age_minutes = (timezone.now() - row.timestamp).total_seconds() / 60
            _logger.info(
                "Deleting orphaned replacement ImportQueue row: "
                "id=%s, replacement_feature_id=%s, filename=%r, created=%s, age_minutes=%.1f",
                row.id,
                row.replacement,
                row.original_filename,
                row.timestamp,
                age_minutes,
            )

    return deleted_count
