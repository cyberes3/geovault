import logging
from datetime import datetime, timedelta

from django.test import RequestFactory

from .views import _perform_pwa_generation
from .utils import get_apk_cache_path
from website.config_loader import get_config_loader
from website.celery_app import celery_app

logger = logging.getLogger("website.pwa_mint.worker")

def _should_regenerate() -> bool:
    """Check if the cached APK is missing or older than 24 hours."""
    config = get_config_loader()
    domain = config.get_str("site.domain", "geovault.example.com")
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
    config = get_config_loader()
    domain = config.get_str("site.domain", "geovault.example.com")

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
    """Periodic/startup check task: regenerate only when APK is missing/stale."""
    if _should_regenerate():
        logger.info("APK is missing or stale, triggering regeneration")
        return _regenerate_apk()
    logger.info("APK is current, skipping regeneration")
    return True


def pwa_regenerate_task() -> bool:
    """Force regeneration task."""
    return _regenerate_apk()


def enqueue_startup_check(task_name: str) -> None:
    """Enqueue a one-shot startup check task."""
    try:
        celery_app.send_task(task_name, queue="extensions")
        logger.info("Queued PWA startup check task: %s", task_name)
    except Exception as e:
        logger.error("Failed to queue PWA startup check task '%s': %s", task_name, e, exc_info=True)
