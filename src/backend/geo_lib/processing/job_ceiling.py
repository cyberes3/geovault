"""
Size-scaled timeout calculations shared by the processing pipeline.

Kept as pure functions (no processor instance required) so both `BaseProcessor` (which knows
the timeout only after it has the file in memory) and the job dispatch code in `ProcessJob`/
`job_recovery` (which only knows the raw byte size at enqueue time, before a processor exists)
compute identical numbers from the same formula.
"""

from website.settings_utils import get_required_setting


def calculate_conversion_timeout_seconds(file_size_bytes: int) -> int:
    """
    Per-conversion timeout for a single file, scaled by size.
    """
    file_size_mb = file_size_bytes / (1024 * 1024)
    timeout_base = get_required_setting('PROCESSING_TIMEOUT_BASE_SECONDS')
    timeout_per_mb = get_required_setting('PROCESSING_TIMEOUT_PER_MB_SECONDS')
    return max(timeout_base, int(timeout_base + (file_size_mb * timeout_per_mb)))


def calculate_job_ceiling_seconds(file_size_bytes: int) -> int:
    """
    Defense-in-depth ceiling for an entire job (all pipeline stages), not just conversion.
    """
    multiplier = get_required_setting('PROCESSING_TIMEOUT_JOB_CEILING_MULTIPLIER')
    return calculate_conversion_timeout_seconds(file_size_bytes) * multiplier
