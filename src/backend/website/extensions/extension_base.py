"""
Base AppConfig for GeoVault extensions.

Extensions inherit from ExtensionAppConfig and implement extension_ready().
ExtensionRuntime.initialize() calls extension_ready() after all apps load.
"""
from django.apps import AppConfig


class ExtensionAppConfig(AppConfig):
    """
    Base AppConfig for GeoVault extensions.

    1. Inherit from this class in apps.py
    2. Set name to 'extensions.{name}.src.backend'
    3. Set label to the extension name (snake_case)
    4. Implement extension_ready() for hooks, ImportProvider, WS, Celery
    """

    def ready(self) -> None:
        """Lifecycle is ExtensionRuntime.initialize() after every app has loaded."""
        return

    def extension_ready(self) -> None:
        """
        Override to register ImportProvider, well-known paths, websocket routes,
        and background tasks.
        """
        return
