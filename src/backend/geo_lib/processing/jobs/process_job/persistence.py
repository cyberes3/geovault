"""
`ImportQueue` persistence for the process job pipeline: creating the initial recovery-safe
row, saving finalized features, and marking a row as failed/unparsable.
"""

from typing import Any, Dict, List, Optional

from django.contrib.auth.models import User
from django.db import transaction

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatusTracker
from geo_lib.processing.jobs.process_job.broadcasting import broadcast_to_import_queue_module
from geo_lib.processing.jobs.process_job.job_control import check_cancellation
from geo_lib.processing.logging import RealTimeImportLog, DatabaseLogLevel
from geo_lib.processing.messages import ERROR_TYPE_PROCESSING_FAILED
from geo_lib.processing.utils import encode_raw_file_data, build_skipped_feature_ids
from geo_lib.utils.advisory_locks import advisory_lock
from geo_lib.utils.pydantic_serialization import convert_features_to_pydantic
from geo_lib.utils.secure_path import secure_filename

_logger = get_tagged_logger('ProcessJob')


def create_initial_import_queue_entry(filename: str, user_id: int, file_data: bytes,
                                       replacement_feature_id: Optional[int] = None) -> int:
    """
    Create an initial `ImportQueue` entry for async processing.

    The raw file data is saved immediately so that if the server restarts before
    processing completes, the job can be recovered and re-processed.
    """
    with transaction.atomic():
        user = User.objects.get(id=user_id)

        # This will be re-encoded later in finalize_and_save_processed_features, but that's
        # okay as it ensures we have the data available for recovery
        raw_file_content, _ = encode_raw_file_data(file_data)

        safe_filename = secure_filename(filename)
        if not safe_filename:
            safe_filename = "import"

        import_queue = ImportQueue.objects.create(
            raw_file=raw_file_content,  # Save actual file content for recovery
            original_filename=safe_filename,
            user=user,
            geofeatures=[],  # Empty array during processing
            replacement=replacement_feature_id  # Set replacement feature ID if provided
        )
        return import_queue.id


def mark_import_queue_as_failed(import_queue_id: int, error_message: str) -> None:
    """Mark an ImportQueue item as unparsable and save error information."""
    import_queue = ImportQueue.objects.get(id=import_queue_id)
    import_queue.unparsable = True
    import_queue.geofeatures = [{
        'error': ERROR_TYPE_PROCESSING_FAILED,
        'message': error_message
    }]
    import_queue.save()


def finalize_and_save_processed_features(
        status_tracker: ProcessingStatusTracker,
        geojson_data: Dict[str, Any],
        duplicate_features: List[Dict[str, Any]],
        processing_log: RealTimeImportLog,
        user_id: int, job_id: str, geojson_size_mb: float,
        raw_file_data: bytes,
        update_and_broadcast_status) -> int:
    """
    Save processed features to database.

    This is the final database persistence step that:
    - Handles file-level duplicate checking
    - Auto-skips geometry duplicates
    - Persists all data to the ImportQueue entry

    Args:
        status_tracker: Tracker used to look up the job's import_queue_id and check cancellation
        geojson_data: Processed GeoJSON data
        duplicate_features: List of detected duplicate features
        processing_log: Real-time import log
        user_id: User ID
        job_id: Processing job ID
        geojson_size_mb: Size of GeoJSON in MB
        raw_file_data: Raw file content as bytes
        update_and_broadcast_status: `ProcessJob._update_and_broadcast_status`-shaped callable,
            used to report save progress

    Returns:
        ImportQueue entry ID
    """
    job = status_tracker.get_job(job_id)
    if not job or not job.import_queue_id:
        raise Exception("No import queue ID found for job")

    try:
        import_queue = ImportQueue.objects.get(id=job.import_queue_id)
    except ImportQueue.DoesNotExist:
        # ImportQueue was deleted (likely by user deletion), stop processing.
        # Return the ID even though we can't update it.
        return job.import_queue_id

    try:
        # Hash the raw file content for duplicate detection OUTSIDE the transaction
        # This ensures files with the same source content get the same hash,
        # regardless of processing differences or file format (KML vs KMZ)
        raw_file_content, file_hash = encode_raw_file_data(raw_file_data)

        processed_features = geojson_data.get('features', [])
        processing_log.add(f"Saving {len(processed_features)} features to database ({geojson_size_mb:.2f} MB)", "ProcessJob", DatabaseLogLevel.INFO)

        is_replacement = import_queue.replacement is not None

        with transaction.atomic():
            if check_cancellation(status_tracker, job_id, processing_log, "before database save"):
                return import_queue.id

            if is_replacement:
                progress = 100.0  # Fast path: already at 100%
            else:
                progress = 96.0  # Normal path: 96% after duplicate detection
            update_and_broadcast_status(job_id, user_id, import_queue.id, "Saving features to database...", progress)

            import_queue.raw_file = raw_file_content

            # CRITICAL SECTION: Use advisory lock to prevent race conditions when saving file hash
            # This ensures that if two identical files are uploaded simultaneously, one will
            # be properly marked as a duplicate of the other
            with advisory_lock(file_hash):
                # Check for file-level duplicates AFTER acquiring lock
                # This ensures the hash from the first file is saved before the second file checks
                duplicate_imported_file = ImportQueue.objects.filter(
                    user_id=user_id,
                    file_hash=file_hash,
                    imported=True
                ).exclude(id=import_queue.id).first()

                if duplicate_imported_file and not is_replacement:
                    # This file is a duplicate of an already-imported file. We still save the
                    # file but don't set any special status here; the WebSocket module will
                    # detect this and auto-recheck duplicates.
                    processing_log.add(
                        f"File is a duplicate of already imported file: {duplicate_imported_file.original_filename}",
                        "File Duplicate Detection",
                        DatabaseLogLevel.WARNING
                    )

                # Save the hash and all other data
                import_queue.file_hash = file_hash
                import_queue.geofeatures = convert_features_to_pydantic(processed_features)
                import_queue.duplicate_features = convert_features_to_pydantic(duplicate_features)

                # Auto-skip ONLY geometry duplicates by adding their feature IDs to skipped_feature_ids
                # Hash duplicates are always blocked and should not be in skipped_feature_ids
                existing_skipped = set(import_queue.skipped_feature_ids if import_queue.skipped_feature_ids else [])
                import_queue.skipped_feature_ids = build_skipped_feature_ids(duplicate_features, existing_skipped)
                import_queue.save()
            # Advisory lock released here

            processing_log.add("Import queue entry updated successfully", "ProcessJob", DatabaseLogLevel.INFO)

            # Broadcast status update to trigger queue refresh so duplicate status is updated
            broadcast_to_import_queue_module(user_id, 'status_updated', {'id': import_queue.id})

            # Note: No need to call importlog_to_db since RealTimeImportLog writes to DB immediately

            return import_queue.id

    except Exception as e:
        _logger.error(f"Failed to update import queue entry for job {job_id}: {str(e)}")
        raise
