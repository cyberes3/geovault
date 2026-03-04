from website.extensions.extension_base import ExtensionAppConfig

from geo_lib.websocket.registry import register_websocket_module


class LiveTrackConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.live_track.src.backend"
    label = "live_track"
    verbose_name = "Live Track"

    def extension_ready(self):
        from .websocket import LiveTrackModule
        register_websocket_module("live_track", LiveTrackModule)
