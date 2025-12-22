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


def get_tagged_logger(name: str = None):
    if not name:
        full_name = __name__
        # Extract top-level module name for cleaner tags
        # e.g., "geo_lib.logging.console" -> tag is "GEO_LIB", but logger name is still "geo_lib.logging.console"
        tag_name = full_name.split('.')[0] if '.' in full_name else full_name
        return TaggedLoggerAdapter(logging.getLogger(full_name.lower()), tag_name.upper())
    return TaggedLoggerAdapter(logging.getLogger(name.lower()), name.upper())
