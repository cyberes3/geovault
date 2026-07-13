import logging
from importlib import import_module
from pathlib import Path

from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_bg_task, register_websocket_route

logger = logging.getLogger(__name__)


class LiveTrackConfig(ExtensionAppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "extensions.live_track.src.backend"
    label = "live_track"
    verbose_name = "Live Track"
    path = str(Path(__file__).parent.resolve())

    def extension_ready(self):
        base_module = self.module.__name__
        consumers_module = import_module(f"{base_module}.consumers")
        helpers_module = import_module(f"{base_module}.helpers")

        live_track_consumer = getattr(consumers_module, "LiveTrackOnlyConsumer")
        flush_pending_broadcasts_task = getattr(
            helpers_module, "flush_pending_broadcasts_task"
        )
        register_websocket_route(
            r"ws/extensions/live-track/trackers-live/$",
            live_track_consumer,
        )
        register_bg_task(
            "flush_pending_broadcasts",
            flush_pending_broadcasts_task,
            queue="live_track",
        )
        logger.info("Live Track Celery flush task registered")
