"""
Mutating startup operations for the GeoVault Django application.

Unlike `website.startup_checks` (which only observes and reports pass/fail), the functions
here change state on every server start: clearing the default cache and redispatching
interrupted import jobs. Both are non-critical (a failure here logs a warning but does not
abort startup).
"""
import traceback

from django.core.cache import cache

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.job_recovery import recover_interrupted_jobs as _recover_interrupted_jobs

_logger = get_tagged_logger('startup')


def clear_default_cache():
    """
    Clear Django's default cache on startup.

    This ensures fresh data after server restarts and prevents stale
    cached data (especially important for reverse geocoding which caches
    results for 30 days). Despite the name of Django's CACHES['default'] backend
    (Redis), this clears whichever backend is configured as 'default', not
    all Redis-backed caches (e.g. Channels' Redis layer is untouched).
    """
    try:
        cache.clear()
        _logger.info("✓ Cleared default cache (ensures fresh data on startup)")
        return True
    except Exception as e:
        _logger.warning(f"⚠ Failed to clear default cache: {e}")
        # This is not critical - server can still start
        return True


def recover_interrupted_jobs():
    """
    Recover and redispatch jobs that were interrupted during processing.

    ImportQueue entries persist in the database independently of Celery, so this finds jobs
    that were being processed but didn't complete (e.g. a worker was killed mid-job) and
    redispatches them to the `imports` Celery queue.

    This is non-critical - if recovery fails, the server can still start.
    """
    try:
        result = _recover_interrupted_jobs()

        if result['total_found'] == 0:
            _logger.info("✓ No interrupted jobs to recover")
        else:
            _logger.info(f"✓ Job recovery: {result['recovered']}/{result['total_found']} jobs recovered")
            if result['failed'] > 0:
                _logger.warning(f"⚠ Failed to recover {result['failed']} job(s)")
            if result['users_affected'] > 0:
                _logger.info(f"  Affected users: {result['users_affected']}")

    except Exception:
        _logger.warning(f"⚠ Failed to recover interrupted jobs: {traceback.format_exc()}")
        # This is not critical - server can still start
