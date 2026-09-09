"""Django cache backends and the Redis-backed Channels layer (WebSockets)."""
from website.config.loader import get_config

_config = get_config()
_redis_host = _config.redis.host
_redis_port = _config.redis.port

# Redis DB map:
#   2 = rate_limiting
#   3 = Celery broker (see celery.py)
#   4 = Celery result (see celery.py)
#   5 = shared (Hauk, social preview, forward geocode, activity, storage)
# Channel layer uses the default Redis DB with an explicit capacity / group_expiry.
CACHES = {
    'default': {
        'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
        'LOCATION': 'unique-snowflake',
    },
    'rate_limiting': {
        'BACKEND': 'django_redis.cache.RedisCache',
        'LOCATION': f'redis://{_redis_host}:{_redis_port}/2',
        'OPTIONS': {
            'CLIENT_CLASS': 'django_redis.client.DefaultClient',
            'SOCKET_CONNECT_TIMEOUT': 2,
            'SOCKET_TIMEOUT': 2,
        },
        'KEY_PREFIX': 'ratelimit',
        'TIMEOUT': 60,
    },
    'shared': {
        'BACKEND': 'django_redis.cache.RedisCache',
        'LOCATION': f'redis://{_redis_host}:{_redis_port}/5',
        'OPTIONS': {
            'CLIENT_CLASS': 'django_redis.client.DefaultClient',
            'SOCKET_CONNECT_TIMEOUT': 2,
            'SOCKET_TIMEOUT': 2,
        },
        'KEY_PREFIX': 'shared',
    },
}

CHANNEL_LAYER_CAPACITY = 1000
CHANNEL_LAYER_GROUP_EXPIRY = 86400

CHANNEL_LAYERS = {
    'default': {
        'BACKEND': 'channels_redis.core.RedisChannelLayer',
        'CONFIG': {
            'hosts': [(_redis_host, _redis_port)],
            'capacity': CHANNEL_LAYER_CAPACITY,
            'group_expiry': CHANNEL_LAYER_GROUP_EXPIRY,
        },
    },
}
