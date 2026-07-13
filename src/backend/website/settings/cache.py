"""Django cache backends and the Redis-backed Channels layer (WebSockets)."""
from website.config.loader import get_config

_config = get_config()
_redis_host = _config.redis.host
_redis_port = _config.redis.port

CACHES = {
    'default': {
        'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
        'LOCATION': 'unique-snowflake',  # Unique identifier for this cache instance
    },
    # Separate Redis cache for reverse geocoding results.
    # Uses a different Redis DB to persist across restarts (not cleared on startup).
    'reverse_geocoding': {
        'BACKEND': 'django_redis.cache.RedisCache',
        'LOCATION': f'redis://{_redis_host}:{_redis_port}/1',
        'OPTIONS': {
            'CLIENT_CLASS': 'django_redis.client.DefaultClient',
        },
        'KEY_PREFIX': 'reverse_geocode',
        'TIMEOUT': 30 * 24 * 60 * 60,  # 30 days default timeout
    },
    # Redis cache for rate limiting (works across multiple processes).
    # Uses a different Redis DB for rate limiting counters.
    'rate_limiting': {
        'BACKEND': 'django_redis.cache.RedisCache',
        'LOCATION': f'redis://{_redis_host}:{_redis_port}/2',
        'OPTIONS': {
            'CLIENT_CLASS': 'django_redis.client.DefaultClient',
            'SOCKET_CONNECT_TIMEOUT': 2,  # 2 second timeout for connection
            'SOCKET_TIMEOUT': 2,  # 2 second timeout for operations
        },
        'KEY_PREFIX': 'ratelimit',
        'TIMEOUT': 60,  # Rate limit counters expire after 60 seconds
    },
}

# WebSocket channel layer, backed by Redis (used for import job progress, live tracking, etc.)
CHANNEL_LAYERS = {
    'default': {
        'BACKEND': 'channels_redis.core.RedisChannelLayer',
        'CONFIG': {
            'hosts': [(_redis_host, _redis_port)],
        },
    },
}
