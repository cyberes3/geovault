"""Checks that Celery worker and beat processes are reachable and healthy."""
import time

from celery.exceptions import TimeoutError as CeleryTimeoutError

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.redis_connection import get_redis_connection
from website.celery_app import celery_app
from website.settings_utils import get_required_setting
from api.tasks import CELERY_BEAT_HEARTBEAT_KEY

_logger = get_tagged_logger('startup')


def _log_celery_service_help(service_name: str, additional_hint: str = "") -> None:
    """Log concise operator guidance when a Celery startup check fails."""
    _logger.error("  Celery startup help:")
    _logger.error(f"  - Check status: sudo systemctl status {service_name}")
    _logger.error(f"  - View logs: sudo journalctl -u {service_name} -n 120 --no-pager")
    _logger.error("  - Verify Redis: sudo systemctl status redis redis-server")
    _logger.error("  - Restart: sudo systemctl restart geovault-celery geovault-celery-beat geovault")
    if additional_hint:
        _logger.error(f"  - Hint: {additional_hint}")


def check_celery_worker(suppress_logging=False):
    """
    Verify Celery worker availability by dispatching a lightweight task and awaiting result.

    Args:
        suppress_logging: If True, do not log success or failure (for health endpoint).
    """
    try:
        timeout_seconds = max(1, get_required_setting('CELERY_WORKER_STARTUP_TIMEOUT_SECONDS'))
        result = celery_app.send_task("api.celery_health.ping_worker", queue="maintenance")
        value = result.get(timeout=timeout_seconds)
        if value != "pong":
            if not suppress_logging:
                _logger.error("✗ Celery worker check failed: unexpected response %r", value)
            return False
        if not suppress_logging:
            _logger.info("✓ Celery worker is reachable")
        return True
    except CeleryTimeoutError:
        if not suppress_logging:
            _logger.error(
                "✗ Celery worker check failed: timed out waiting for ping task result "
                "(worker did not respond before timeout)."
            )
            _log_celery_service_help(
                "geovault-celery",
                "Ensure the worker is running and subscribed to the 'maintenance' queue.",
            )
        return False
    except Exception as e:
        if not suppress_logging:
            _logger.error(f"✗ Celery worker check failed: {e}")
            _log_celery_service_help("geovault-celery")
        return False


def check_celery_beat(suppress_logging=False, wait_for_heartbeat=True):
    """
    Verify celery-beat scheduling by checking for a recent Redis heartbeat timestamp.

    Args:
        suppress_logging: If True, do not log success or failure (for health endpoint).
        wait_for_heartbeat: If True, loop until deadline waiting for a recent heartbeat.
            If False, check once and return immediately (for health endpoint).
    """
    try:
        max_age = max(5, get_required_setting('CELERY_BEAT_HEARTBEAT_MAX_AGE_SECONDS'))
        wait_seconds = max(0, get_required_setting('CELERY_BEAT_STARTUP_WAIT_SECONDS'))
        deadline = time.time() + wait_seconds
        redis_client = get_redis_connection()

        while True:
            raw = redis_client.get(CELERY_BEAT_HEARTBEAT_KEY)
            if raw:
                try:
                    heartbeat_ts = float(raw)
                    if (time.time() - heartbeat_ts) <= max_age:
                        if not suppress_logging:
                            _logger.info("✓ Celery beat heartbeat is recent")
                        return True
                except (TypeError, ValueError):
                    pass

            if not wait_for_heartbeat or time.time() >= deadline:
                break
            time.sleep(1)

        if not suppress_logging:
            _logger.error("✗ Celery beat check failed: heartbeat missing or stale.")
            _log_celery_service_help(
                "geovault-celery-beat",
                "Beat must be running so periodic tasks can update the heartbeat key.",
            )
        return False
    except Exception as e:
        if not suppress_logging:
            _logger.error(f"✗ Celery beat check failed: {e}")
            _log_celery_service_help("geovault-celery-beat")
        return False
