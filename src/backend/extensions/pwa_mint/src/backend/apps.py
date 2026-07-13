import logging
import sys
from datetime import timedelta
from pathlib import Path

from website.extensions.extension_hooks import (
    register_bg_task,
    register_periodic_bg_task,
    register_well_known,
)
from website.extensions.extension_base import ExtensionAppConfig

from .utils import get_keystore_info
from .views import asset_links
from .worker import (
    enqueue_startup_check,
    pwa_check_and_regenerate_task,
    pwa_regenerate_task,
)

logger = logging.getLogger("website.pwa_mint")


def _is_running_tests():
    """True when we're in the test runner (Django test or pytest)."""
    if any("test" in arg for arg in sys.argv):
        return True
    if "pytest" in sys.modules:
        return True
    return False


def _is_management_command():
    """True when we're running a management command (e.g. manage.py regeocode_features). Skip APK worker to avoid log noise."""
    if len(sys.argv) < 2:
        return False
    command = sys.argv[1].lower()
    if command in ('runserver', 'runserver_plus'):
        return False
    if 'manage.py' in sys.argv[0]:
        return True
    return False


def _is_celery_process():
    """True when running a celery worker/beat process."""
    if len(sys.argv) < 1:
        return False
    argv = " ".join(arg.lower() for arg in sys.argv)
    return "celery" in argv and (" worker" in f" {argv}" or " beat" in f" {argv}")


def _should_enqueue_startup_check():
    """
    Enqueue startup check only from the primary web process.
    - Never from tests, management commands, or celery worker/beat.
    - Never from runserver/runserver_plus (dev autoreload can trigger many restarts).
    """
    if _is_running_tests() or _is_management_command() or _is_celery_process():
        return False

    if any(arg in ("runserver", "runserver_plus") for arg in sys.argv):
        return False

    return True


class PwaMintConfig(ExtensionAppConfig):
    """
    PWA Minting extension using Celery tasks for background regeneration.
    """
    name = 'extensions.pwa_mint.src.backend'
    label = 'pwa_mint'
    verbose_name = 'PWA Minting'
    path = str(Path(__file__).parent.resolve())

    def extension_ready(self):
        # Register .well-known items
        try:
            register_well_known('assetlinks.json', asset_links)
        except ValueError as e:
            logger.error(f"Failed to register assetlinks.json: {e}")

        logger.info("PWA Minting enabled")

        # Keystore check is quick; keep it on startup.
        try:
            get_keystore_info()
        except Exception as e:
            logger.warning(f"Failed to initialize keystore on startup: {e}")

        try:
            check_task_name = register_bg_task(
                "check_and_regenerate",
                pwa_check_and_regenerate_task,
                queue="extensions",
            )
            register_bg_task(
                "regenerate",
                pwa_regenerate_task,
                queue="extensions",
            )
            register_periodic_bg_task(
                "daily_check",
                check_task_name,
                timedelta(hours=24),
                options={"queue": "extensions"},
            )

            # Startup enqueue should happen only from the primary web process.
            if _should_enqueue_startup_check():
                enqueue_startup_check(check_task_name)
            logger.info("PWA Celery tasks registered successfully")
        except Exception as e:
            logger.error(f"Failed to register PWA Celery tasks: {e}", exc_info=True)
