from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_websocket_route


class LiveTrackConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.live_track.src.backend"
    label = "live_track"
    verbose_name = "Live Track"

    def extension_ready(self):
        from .consumers import LiveTrackOnlyConsumer
        register_websocket_route(
            r"ws/extensions/live-track/trackers-live/$",
            LiveTrackOnlyConsumer,
        )
