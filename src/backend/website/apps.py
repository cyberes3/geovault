from django.apps import AppConfig

from website.extensions.runtime import ExtensionRuntime


class WebsiteConfig(AppConfig):
    """Last INSTALLED_APPS entry so ExtensionRuntime runs after every extension app."""

    name = "website"
    label = "website"
    verbose_name = "GeoVault Website"

    def ready(self) -> None:
        ExtensionRuntime.initialize()
