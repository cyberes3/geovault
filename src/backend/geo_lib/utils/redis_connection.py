"""
Redis connection utility for distributed operations.
Provides a centralized way to get Redis connections from Django Channels configuration.
"""

import redis
from django.conf import settings


def get_redis_connection():
    """
    Get Redis connection from CHANNEL_LAYERS config.
    
    Returns:
        redis.Redis: Redis connection instance with decode_responses=True
        
    Raises:
        KeyError: If CHANNEL_LAYERS config is not properly configured
    """
    config = settings.CHANNEL_LAYERS['default']['CONFIG']
    host, port = config['hosts'][0]
    return redis.Redis(host=host, port=port, decode_responses=True)
