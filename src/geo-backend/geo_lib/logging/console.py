"""
Console logging utilities with activity tags.

Provides helper functions to get loggers with appropriate tags for different
server activities. All log messages will be prefixed with their activity tag.
"""
import logging


class TaggedLoggerAdapter(logging.LoggerAdapter):
    """Logger adapter that adds a tag prefix to all log messages."""
    
    def __init__(self, logger, tag):
        super().__init__(logger, {})
        self.tag = tag
    
    def process(self, msg, kwargs):
        """Add the tag prefix to the message."""
        return f"[{self.tag}] {msg}", kwargs


def get_access_logger():
    """Get logger for HTTP requests, API endpoints, and views."""
    return TaggedLoggerAdapter(logging.getLogger('access'), 'ACCESS')


def get_import_logger():
    """Get logger for file uploads, processing, and import operations."""
    return TaggedLoggerAdapter(logging.getLogger('import'), 'IMPORT')


def get_websocket_logger():
    """Get logger for WebSocket connections, messages, and disconnections."""
    return TaggedLoggerAdapter(logging.getLogger('websocket'), 'WEBSOCKET')


def get_job_logger():
    """Get logger for background job processing."""
    return TaggedLoggerAdapter(logging.getLogger('job'), 'JOB')


def get_database_logger():
    """Get logger for database operations and queries."""
    return TaggedLoggerAdapter(logging.getLogger('database'), 'DATABASE')


def get_security_logger():
    """Get logger for security events and file validation."""
    return TaggedLoggerAdapter(logging.getLogger('security'), 'SECURITY')


def get_tile_logger():
    """Get logger for tile proxy and caching operations."""
    return TaggedLoggerAdapter(logging.getLogger('tile'), 'TILE')


def get_geocode_logger():
    """Get logger for geocoding and reverse geocoding."""
    return TaggedLoggerAdapter(logging.getLogger('geocode'), 'GEOCODE')


def get_startup_logger():
    """Get logger for server startup checks."""
    return TaggedLoggerAdapter(logging.getLogger('startup'), 'STARTUP')


def get_config_logger():
    """Get logger for configuration loading."""
    return TaggedLoggerAdapter(logging.getLogger('config'), 'CONFIG')

