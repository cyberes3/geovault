from website.extensions.extension_base import ExtensionAppConfig
import logging

logger = logging.getLogger("website.pwa_mint")

class PwaMintConfig(ExtensionAppConfig):
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

        # APKs are stored in data/pwa_mint/ (persistent); no startup cleanup so cache survives restarts.
        # Log download path
        logger.info("PWA Minting enabled")

        # Initial keystore check
        try:
            from .utils import get_keystore_info
            get_keystore_info()
        except Exception as e:
            logger.warning(f"Failed to initialize keystore on startup: {e}")
            
        # Start background APK regeneration worker
        try:
            from .worker import start_worker
            start_worker()
            logger.info("PWA regeneration worker started successfully")
        except Exception as e:
            logger.error(f"Failed to start PWA regeneration worker: {e}", exc_info=True)
