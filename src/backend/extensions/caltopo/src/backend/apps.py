"""AppConfig for the CalTopo extension."""
import logging
from pathlib import Path

from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_logging import register_logging_filter
from website.extensions.import_provider import register_import_provider

from extensions.caltopo.src.backend.logging_filters import SuppressCaltopoFilter

logger = logging.getLogger("caltopo.apps")


class CaltopoExtensionConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.caltopo.src.backend"
    label = "caltopo"
    verbose_name = "CalTopo Extension"
    path = str(Path(__file__).parent.resolve())

    def extension_ready(self):
        from extensions.caltopo.src.backend.import_provider import CaltopoImportProvider

        register_import_provider(CaltopoImportProvider())
        register_logging_filter(SuppressCaltopoFilter())
        logger.info("CalTopo extension initialized")
