"""
Duplicate detection step.

Checks a batch of processed features for internal duplicates (within the same
file), then against the user's feature store and other in-flight import queue
items, so imports don't create redundant features.
"""
import time
import traceback
from typing import Any, Callable, Dict, List, Optional, Tuple

from api.models import ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.duplicate_detection.duplicate_detection import find_duplicates_for_source, remove_internal_duplicates
from geo_lib.processing.duplicate_detection.models import split_duplicates_by_match_type
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog

_logger = get_tagged_logger('DUPLICATE_STEP')


def _build_duplicate_summary(fs_hash_count: int, fs_geom_count: int,
                              cq_hash_count: int, cq_geom_count: int) -> str:
    """
    Build duplicate summary message for logging.

    Returns:
        Summary string like "Found 5 duplicate(s): 3 in library, 2 in import queue"
    """
    total_existing_duplicates = fs_hash_count + fs_geom_count + cq_hash_count + cq_geom_count

    if total_existing_duplicates == 0:
        return "No duplicates found in library or import queue"

    summary_parts = []
    if fs_hash_count > 0 or fs_geom_count > 0:
        fs_total = fs_hash_count + fs_geom_count
        summary_parts.append(f"{fs_total} in library")
    if cq_hash_count > 0 or cq_geom_count > 0:
        cq_total = cq_hash_count + cq_geom_count
        summary_parts.append(f"{cq_total} in import queue")

    return f"Found {total_existing_duplicates} duplicate(s): {', '.join(summary_parts)}"


def detect_duplicates(
    processed_features: List[Dict[str, Any]],
    user_id: int,
    import_queue_id: Optional[int],
    is_canceled: Callable[[], bool],
) -> Tuple[List[Dict[str, Any]], ImportLog]:
    """
    Detect duplicate features (internal, feature store, and cross-queue).

    Callers should skip invoking this entirely (rather than calling it with an
    empty/None user_id) when duplicate detection isn't applicable, since it's
    inherently user-scoped.
    """
    step_log = ImportLog()
    duplicate_features = []

    try:
        # Check for cancellation before duplicate detection
        if is_canceled():
            step_log.add("Processing canceled before duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
            return duplicate_features, step_log

        # Start duplicate detection timing
        duplicate_detection_start = time.time()

        # First, check for internal duplicates within the file
        unique_internal_features, internal_duplicate_count = remove_internal_duplicates(processed_features)

        if internal_duplicate_count > 0:
            step_log.add(f"Found {internal_duplicate_count} internal duplicate(s)", "Duplicate Detection", DatabaseLogLevel.INFO)
        else:
            step_log.add("No internal duplicates found", "Duplicate Detection", DatabaseLogLevel.INFO)

        # Check for cancellation after internal duplicate detection
        if is_canceled():
            step_log.add("Processing canceled after internal duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
            return duplicate_features, step_log

        # Get ImportQueue for exclude parameters
        import_queue = None
        if import_queue_id:
            try:
                import_queue = ImportQueue.objects.get(id=import_queue_id)
            except ImportQueue.DoesNotExist:
                pass

        # PASS 1: Check feature store (hash + geometry, with hash priority)
        remaining_after_fs, feature_store_duplicates, fs_log = find_duplicates_for_source(
            unique_internal_features,
            user_id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )

        # Split feature store duplicates into hash and geometry for tracking
        feature_store_hash_duplicates, feature_store_geom_duplicates = split_duplicates_by_match_type(
            feature_store_duplicates
        )

        # PASS 2: Check cross-queue (hash + geometry, with hash priority) on remaining features
        exclude_queue_id = import_queue.id if import_queue else None
        exclude_timestamp = import_queue.timestamp if import_queue else None

        remaining_after_cq, cross_queue_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            user_id,
            source='cross_queue',
            exclude_queue_id=exclude_queue_id,
            exclude_timestamp=exclude_timestamp
        )

        # Split cross-queue duplicates into hash and geometry for tracking
        cross_queue_hash_duplicates, cross_queue_geom_duplicates = split_duplicates_by_match_type(
            cross_queue_duplicates
        )

        # Combine all duplicates in priority order
        duplicate_features = (
                feature_store_hash_duplicates +
                feature_store_geom_duplicates +
                cross_queue_hash_duplicates +
                cross_queue_geom_duplicates
        )

        # Log duplicate detection results summary
        fs_hash_count = len(feature_store_hash_duplicates)
        fs_geom_count = len(feature_store_geom_duplicates)
        cq_hash_count = len(cross_queue_hash_duplicates)
        cq_geom_count = len(cross_queue_geom_duplicates)

        summary = _build_duplicate_summary(fs_hash_count, fs_geom_count, cq_hash_count, cq_geom_count)
        step_log.add(summary, "Duplicate Detection", DatabaseLogLevel.INFO)

        duplicate_detection_duration = time.time() - duplicate_detection_start
        step_log.add(f"Duplicate detection completed ({duplicate_detection_duration:.1f}s)", "Duplicate Detection", DatabaseLogLevel.INFO)

        # Check for cancellation after duplicate detection
        if is_canceled():
            step_log.add("Processing canceled after duplicate detection", "Duplicate Detection", DatabaseLogLevel.WARNING)
            return duplicate_features, step_log

    except Exception as e:
        _logger.error(f"Error during duplicate detection: {traceback.format_exc()}")
        step_log.add(f"Duplicate detection failed: {str(e)}", "Duplicate Detection", DatabaseLogLevel.ERROR)

    return duplicate_features, step_log
