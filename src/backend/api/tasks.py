import time

from celery import shared_task

from api.services.replacement_cleanup_service import cleanup_orphaned_replacements
from geo_lib.utils.redis_connection import get_redis_connection

CELERY_BEAT_HEARTBEAT_KEY = "celery_beat_heartbeat"


@shared_task(name="api.celery_health.ping_worker", queue="maintenance")
def ping_worker() -> str:
    """Lightweight task used by startup checks to verify workers can execute jobs."""
    return "pong"


@shared_task(name="api.celery_health.beat_heartbeat", queue="maintenance")
def beat_heartbeat() -> float:
    """Periodic heartbeat task used to verify celery-beat scheduling."""
    now = time.time()
    redis_client = get_redis_connection()
    redis_client.set(CELERY_BEAT_HEARTBEAT_KEY, str(now))
    return now
