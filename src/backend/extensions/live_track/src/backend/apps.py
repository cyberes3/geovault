import logging
from importlib import import_module
from pathlib import Path

import redis

from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_bg_task, register_websocket_route

logger = logging.getLogger(__name__)

# The flush task is just Redis reads/deletes plus an in-process channel-layer send - a few
# seconds is already generous; a hang almost always means Redis/the channel layer is stuck.
FLUSH_SOFT_TIME_LIMIT_SECONDS = 30
FLUSH_TIME_LIMIT_SECONDS = 45
# Only the initial `get_redis_connection()` call is guarded inside `flush_pending_broadcasts`; a
# dropped connection during the read/delete loop should retry rather than lose buffered updates.
FLUSH_RETRY_KWARGS = {"max_retries": 3, "countdown": 2}


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
        # Registered here (not via a `@shared_task` decorator in helpers.py) so registration
        # happens exactly once, against the one callback reference this AppConfig already holds
        # - see `register_bg_task`'s docstring for why a module-level decorator is unsafe for
        # extension backend modules specifically.
        register_bg_task(
            "flush_pending_broadcasts",
            flush_pending_broadcasts_task,
            queue="live_track",
            time_limit=FLUSH_TIME_LIMIT_SECONDS,
            soft_time_limit=FLUSH_SOFT_TIME_LIMIT_SECONDS,
            autoretry_for=(redis.exceptions.ConnectionError, redis.exceptions.TimeoutError),
            retry_kwargs=FLUSH_RETRY_KWARGS,
        )
        logger.info("Live Track Celery flush task registered")
