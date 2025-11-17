"""
Custom exception handler for Django to log unhandled exceptions.
"""
import traceback

from django.core.signals import got_request_exception
from django.dispatch import receiver
from geo_lib.logging.console import get_access_logger

access_logger = get_access_logger()


@receiver(got_request_exception)
def log_unhandled_exception(sender, request, **kwargs):
    """
    Signal handler that logs unhandled exceptions.
    This is called by Django when an unhandled exception occurs during request processing.
    """
    exception = kwargs.get('exception')
    if exception is None:
        # Try to get exception from sys.exc_info if not passed
        import sys
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
    from django.http import JsonResponse, HttpResponse
    if request.path.startswith('/api/'):
        # Return JSON error response for API endpoints
        return JsonResponse({
            'success': False,
            'msg': 'Internal server error occurred',
            'code': 500
        }, status=500)
    else:
        # Return generic 500 for non-API endpoints
        return HttpResponse('Internal Server Error', status=500)

