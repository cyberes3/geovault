from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_websocket_route

from geo_lib.websocket.registry import register_websocket_module


class LiveTrackConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.live_track.src.backend"
    label = "live_track"
    verbose_name = "Live Track"

    def extension_ready(self):
        from .websocket import LiveTrackModule
        register_websocket_module("live_track", LiveTrackModule)
        from .consumers import LiveTrackOnlyConsumer
        register_websocket_route(
            r"ws/extensions/live-track/trackers-live/$",
            LiveTrackOnlyConsumer,
        )
