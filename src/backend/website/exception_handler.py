"""
Custom exception handler for Django to log unhandled exceptions.
"""
import sys
import traceback
import asyncio
import logging

from django.core.signals import got_request_exception
from django.dispatch import receiver
from geo_lib.logging.console import get_access_logger

access_logger = get_access_logger()

# Get root logger for global exception logging
root_logger = logging.getLogger()


@receiver(got_request_exception)
def log_unhandled_exception(sender, request, **kwargs):
    """
    Signal handler that logs unhandled exceptions.
    This is called by Django when an unhandled exception occurs during request processing.
    """
    exception = kwargs.get('exception')
    if exception is None:
        # Try to get exception from sys.exc_info if not passed
        exc_type, exc_value, exc_traceback = sys.exc_info()
        if exc_value:
            exception = exc_value
    
    if exception:
        # Log just the traceback
        traceback_str = traceback.format_exc()
        access_logger.error(traceback_str)


def custom_exception_handler(request, exception=None):
    """
    Custom 500 handler view that returns appropriate responses.
    Exception logging is handled by the signal handler.
    """
    # Return appropriate error response based on request path
    from django.http import HttpResponse
    from api.utils.responses import server_error_response
    
    if request.path.startswith('/api/'):
        # Return JSON error response for API endpoints
        return server_error_response('Internal server error occurred')
    else:
        # Return generic 500 for non-API endpoints
        return HttpResponse('Internal Server Error', status=500)


def _global_exception_handler(exc_type, exc_value, exc_traceback):
    """
    Global exception handler for unhandled exceptions in the main thread.
    This is set as sys.excepthook to catch any exceptions that aren't handled elsewhere.
    """
    if issubclass(exc_type, KeyboardInterrupt):
        # Don't log keyboard interrupts
        sys.__excepthook__(exc_type, exc_value, exc_traceback)
        return
    
    # Log the exception with full traceback
    traceback_str = ''.join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    root_logger.error(f"Unhandled exception in main thread:\n{traceback_str}")
    
    # Also call the original excepthook to maintain default behavior
    sys.__excepthook__(exc_type, exc_value, exc_traceback)


def _async_exception_handler(loop, context):
    """
    Exception handler for unhandled exceptions in async tasks.
    This is set as the asyncio exception handler.
    """
    exception = context.get('exception')
    if exception:
        traceback_str = ''.join(traceback.format_exception(
            type(exception),
            exception,
            exception.__traceback__
        ))
        root_logger.error(f"Unhandled exception in async task:\n{traceback_str}")
    else:
        # Log the context message if no exception object is available
        message = context.get('message', 'Unknown async exception')
        root_logger.error(f"Unhandled exception in async task: {message}")


def setup_global_exception_handlers():
    """
    Set up global exception handlers for unhandled exceptions.
    This should be called during Django startup to ensure all exceptions are logged.
    """
    # Set global exception handler for main thread
    sys.excepthook = _global_exception_handler
    
    # Set exception handler for async tasks
    try:
        loop = asyncio.get_event_loop()
        if loop is not None:
            loop.set_exception_handler(_async_exception_handler)
    except RuntimeError:
        # No event loop exists yet, we'll set it up when one is created
        # This will be handled by the ASGI middleware
        pass


class ASGIExceptionMiddleware:
    """
    ASGI middleware to catch and log unhandled exceptions in HTTP and WebSocket contexts.
    This ensures all exceptions in async contexts are logged to the console.
    """
    
    def __init__(self, app):
        self.app = app
        
        # Set up async exception handler for the event loop
        # This will be called when the event loop is created
        try:
            loop = asyncio.get_running_loop()
            loop.set_exception_handler(_async_exception_handler)
        except RuntimeError:
            # No running loop, will be set up when loop is created
            pass
    
    async def __call__(self, scope, receive, send):
        """
        Wrap the ASGI application to catch exceptions.
        """
        try:
            # Ensure exception handler is set for this event loop
            try:
                loop = asyncio.get_running_loop()
                loop.set_exception_handler(_async_exception_handler)
            except RuntimeError:
                pass
            
            # Call the wrapped application
            await self.app(scope, receive, send)
        except Exception as e:
            # Log the exception with full traceback
            traceback_str = ''.join(traceback.format_exception(
                type(e),
                e,
                e.__traceback__
            ))
            
            # Determine context for better logging
            if scope['type'] == 'http':
                path = scope.get('path', 'unknown')
                method = scope.get('method', 'unknown')
                root_logger.error(f"Unhandled exception in HTTP request {method} {path}:\n{traceback_str}")
            elif scope['type'] == 'websocket':
                path = scope.get('path', 'unknown')
                root_logger.error(f"Unhandled exception in WebSocket connection {path}:\n{traceback_str}")
            else:
                root_logger.error(f"Unhandled exception in ASGI {scope.get('type', 'unknown')}:\n{traceback_str}")
            
            # Re-raise the exception so it can be handled by Django's error handling
            raise

