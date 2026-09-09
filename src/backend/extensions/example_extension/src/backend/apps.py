"""AppConfig for the example extension."""
import logging
from pathlib import Path

from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.import_provider import register_import_provider

logger = logging.getLogger("example_extension.apps")


class ExampleExtensionConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.example_extension.src.backend"
    label = "example_extension"
    verbose_name = "Example Extension"
    path = str(Path(__file__).parent.resolve())

    def extension_ready(self):
        from extensions.example_extension.src.backend.import_provider import ExampleImportProvider

        register_import_provider(ExampleImportProvider())
        logger.info("Example extension initialized")
