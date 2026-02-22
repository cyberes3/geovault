"""
Redis connection utility for distributed operations.
Provides a centralized way to get Redis connections from Django Channels configuration.
"""

import redis

from website.settings_utils import get_required_setting


def get_redis_connection():
    """
    Get Redis connection from CHANNEL_LAYERS config.
    
    Returns:
        redis.Redis: Redis connection instance with decode_responses=True
        
    Raises:
        KeyError: If CHANNEL_LAYERS config is not properly configured
    """
    channel_layers = get_required_setting('CHANNEL_LAYERS')
    config = channel_layers['default']['CONFIG']
    host, port = config['hosts'][0]
    return redis.Redis(host=host, port=port, decode_responses=True)
