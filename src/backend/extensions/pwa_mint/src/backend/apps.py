import logging
import sys

from website.extensions.extension_base import ExtensionAppConfig

logger = logging.getLogger("website.pwa_mint")


def _is_running_tests():
    """True when we're in the test runner (Django test or pytest)."""
    if any("test" in arg for arg in sys.argv):
        return True
    if "pytest" in sys.modules:
        return True
    return False


class PwaMintConfig(ExtensionAppConfig):
    """
    PWA Minting extension. Does not block server or tests:
    - Worker is never started during tests.
    - When running the server, the APK regeneration worker runs in a daemon thread
      (start_worker() returns immediately); no startup or request path is blocked.
    """
    name = 'extensions.pwa_mint.src.backend'
    label = 'pwa_mint'
    verbose_name = 'PWA Minting'

    def extension_ready(self):
        # Register .well-known items
        from website.extensions.extension_hooks import register_well_known
        from .views import asset_links

        try:
            register_well_known('assetlinks.json', asset_links)
        except ValueError as e:
            logger.error(f"Failed to register assetlinks.json: {e}")

        logger.info("PWA Minting enabled")

        # Keystore check is quick; keep it on startup.
        try:
            from .utils import get_keystore_info
            get_keystore_info()
        except Exception as e:
            logger.warning(f"Failed to initialize keystore on startup: {e}")

        # Do not start worker during tests (would block: 30s wait + APK HTTP request).
        if _is_running_tests():
            return

        # Worker runs in a daemon thread; returns immediately, never blocks server.
        try:
            from .worker import start_worker
            start_worker()
            logger.info("PWA regeneration worker started successfully")
        except Exception as e:
            logger.error(f"Failed to start PWA regeneration worker: {e}", exc_info=True)
