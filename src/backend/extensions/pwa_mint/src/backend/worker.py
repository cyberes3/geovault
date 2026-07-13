import logging
from datetime import datetime, timedelta

from django.conf import settings
from django.test import RequestFactory

from .views import _perform_pwa_generation
from .utils import get_apk_cache_path
from website.celery_app import celery_app

logger = logging.getLogger("website.pwa_mint.worker")

# `_perform_pwa_generation` bounds its own outbound HTTP calls (10s manifest pre-call + 300s
# PWABuilder build/sign request) but has no overall ceiling - these give the Celery worker a
# hard backstop over the whole task (HTTP calls + zip extraction + APK cache write).
PWA_REGENERATE_SOFT_TIME_LIMIT_SECONDS = 360
PWA_REGENERATE_TIME_LIMIT_SECONDS = 390
# `_should_regenerate`/`_regenerate_apk` touch the local APK cache directory directly (stat,
# open, os.replace) - retry a few times on transient file I/O errors (e.g. a concurrent
# regeneration briefly holding the temp file, a flaky mount) rather than failing outright.
PWA_REGENERATE_RETRY_KWARGS = {"max_retries": 3, "countdown": 30}

def _should_regenerate() -> bool:
    """Check if the cached APK is missing or older than 24 hours."""
    domain = settings.SITE_DOMAIN
    package_id = f"com.geovault.webview.{domain.replace('.', '_')}".lower()
    cache_path = get_apk_cache_path(package_id)

    if not cache_path.exists():
        logger.info("APK not found at %s", cache_path)
        return True

    mtime = datetime.fromtimestamp(cache_path.stat().st_mtime)
    age = datetime.now() - mtime
    if age > timedelta(days=1):
        logger.info("APK is %s day(s) old, regeneration needed", age.days)
        return True

    logger.info("APK is %s hour(s) old, still fresh", age.seconds // 3600)
    return False


def _regenerate_apk() -> bool:
    """Generate APK by calling the existing generation helper with a synthetic request."""
    factory = RequestFactory()
    domain = settings.SITE_DOMAIN

    request = factory.post(f"https://{domain}/api/extensions/pwa-mint/generate/")
    request.META["HTTP_HOST"] = domain
    request.META["wsgi.url_scheme"] = "https"

    response, success = _perform_pwa_generation(request)
    if success:
        logger.info("APK regeneration completed successfully")
    else:
        logger.error("APK regeneration failed: %s", response.content.decode())
    return success


def pwa_check_and_regenerate_task() -> bool:
    """
    Periodic/startup check task: regenerate only when APK is missing/stale.

    Registered with Celery (with time_limit/soft_time_limit/retry hardening) via
    `register_bg_task` in `apps.py`'s `extension_ready()`, not a `@shared_task` decorator here -
    see that call's comment for why.
    """
    if _should_regenerate():
        logger.info("APK is missing or stale, triggering regeneration")
        return _regenerate_apk()
    logger.info("APK is current, skipping regeneration")
    return True


def pwa_regenerate_task() -> bool:
    """Force regeneration task. Registered with Celery via `apps.py` - see `pwa_check_and_regenerate_task`."""
    return _regenerate_apk()


def enqueue_startup_check(task_name: str) -> None:
    """Enqueue a one-shot startup check task."""
    try:
        celery_app.send_task(task_name, queue="extensions")
        logger.info("Queued PWA startup check task: %s", task_name)
    except Exception as e:
        logger.error("Failed to queue PWA startup check task '%s': %s", task_name, e, exc_info=True)
