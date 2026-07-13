"""
Application-wide logging configuration.

Named `logging.py` for the settings concern it configures; `import logging` anywhere in this
package resolves to the stdlib module (absolute imports), not this submodule.
"""
from website.config.loader import get_config

_config = get_config()

# Python logging uses uppercase; LoggingConfig.log_level is already validated/normalized.
LOG_LEVEL = _config.logging.log_level.upper()

LOGGING = {
    'version': 1,
    'disable_existing_loggers': True,  # Disable all existing loggers (system loggers)
    'formatters': {
        'console': {
            'format': '[{asctime}] {levelname} {message}',
            'style': '{',
            'datefmt': '%Y-%m-%d %H:%M:%S',
        },
    },
    'filters': {
    },
    'handlers': {
        'console': {
            'level': LOG_LEVEL,
            'class': 'logging.StreamHandler',
            'formatter': 'console',
            'stream': 'ext://sys.stdout',
        },
    },
    'loggers': {
        # Root logger - handles all our custom loggers
        '': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        # Explicitly enable our custom tagged loggers (they use lowercase names)
        'processjob': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        'replacementcleanupservice': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        'startup': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        'website.middleware': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        # Enable all loggers under our application namespaces
        'geo_lib': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        'api': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        'website': {
            'handlers': ['console'],
            'level': LOG_LEVEL,
            'propagate': False,
        },
        # Explicitly disable system loggers we don't want
        'daphne': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'daphne.server': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'daphne.ws_protocol': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'daphne.http_protocol': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'django.utils.autoreload': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'django.server': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'channels': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'channels.server': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        'twisted': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
        # Disable caltopo_python library logging
        'caltopo_python': {
            'handlers': [],
            'level': 'WARNING',
            'propagate': False,
        },
    },
}
