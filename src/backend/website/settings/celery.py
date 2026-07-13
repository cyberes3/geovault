"""Celery broker/backend, task serialization, and the periodic beat schedule."""
from datetime import timedelta

from website.config.loader import get_config
from website.settings.app_config import TIME_ZONE

_config = get_config()
_celery = _config.celery
_redis_host = _config.redis.host
_redis_port = _config.redis.port

# Redis-derived defaults, used when celery.broker_url/result_backend are left unset in config.yaml.
_default_celery_broker_url = f'redis://{_redis_host}:{_redis_port}/3'
_default_celery_result_backend = f'redis://{_redis_host}:{_redis_port}/4'

CELERY_BROKER_URL = _celery.broker_url or _default_celery_broker_url
CELERY_RESULT_BACKEND = _celery.result_backend or _default_celery_result_backend
CELERY_ACCEPT_CONTENT = ['json']
CELERY_TASK_SERIALIZER = 'json'
CELERY_RESULT_SERIALIZER = 'json'
CELERY_ENABLE_UTC = True
CELERY_TIMEZONE = TIME_ZONE
# Safety-net queue for any task that omits an explicit `queue=` - every real task today sets one
# (maintenance/extensions/live_track/imports), so nothing currently targets this, but it's kept
# in every worker's --queues flag (installation/geovault-celery.service) so an undeclared-queue
# task still runs somewhere instead of silently never executing.
CELERY_TASK_DEFAULT_QUEUE = _celery.default_queue
CELERY_TASK_ALWAYS_EAGER = _celery.task_always_eager
CELERY_TASK_EAGER_PROPAGATES = _celery.task_eager_propagates
CELERY_BEAT_SCHEDULE = {
    'replacement_cleanup_every_60_seconds': {
        'task': 'api.replacement_cleanup.cleanup_orphaned_replacements',
        'schedule': timedelta(seconds=60),
        'options': {'queue': 'maintenance'},
    },
    'celery_beat_heartbeat_every_5_seconds': {
        'task': 'api.celery_health.beat_heartbeat',
        'schedule': timedelta(seconds=5),
        'options': {'queue': 'maintenance'},
    },
}

# Startup/health-check tuning for the checks in website.startup_checks / api.views.health
CELERY_WORKER_STARTUP_TIMEOUT_SECONDS = _celery.worker_startup_timeout_seconds
CELERY_BEAT_HEARTBEAT_MAX_AGE_SECONDS = _celery.beat_heartbeat_max_age_seconds
CELERY_BEAT_STARTUP_WAIT_SECONDS = _celery.beat_startup_wait_seconds
