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
        name = __name__
    return TaggedLoggerAdapter(logging.getLogger(name.lower()), name.upper())
