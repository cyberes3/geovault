"""Access-log middleware: logs every HTTP request/response and re-raises unhandled exceptions
so Django's normal exception handling (see website.exception_handler) still runs."""
import traceback

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

_logger = get_tagged_logger()


def _get_content_length(response):
    """Extract content length from response."""
    try:
        if hasattr(response, 'get'):
            return response.get('Content-Length', '')
        if hasattr(response, 'headers'):
            return response.headers.get('Content-Length', '')
        if hasattr(response, '_headers'):
            header_val = response._headers.get('content-length', ('', ''))
            return header_val[1] if isinstance(header_val, tuple) else header_val
    except Exception:
        pass
    return ''


def _log_request_line(log_msg, status_code):
    """Log one request line: server errors at ERROR, client errors at WARNING, success at INFO."""
    if status_code >= 500:
        _logger.error(log_msg)
    elif status_code >= 400:
        _logger.warning(log_msg)
    else:
        _logger.info(log_msg)


class LoggingMiddleware:
    """Middleware to log all HTTP requests and catch unhandled exceptions."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        client_ip = get_client_ip(request)

        try:
            response = self.get_response(request)
            # Get user identifier AFTER authentication middleware has run
            user_identifier = get_user_identifier(request)
        except Exception:
            # Log just the traceback
            traceback_str = traceback.format_exc()
            _logger.error(traceback_str)

            # Re-raise the exception so Django's exception handling can process it
            # This ensures got_request_exception signal fires and handler500 is called
            # Response formatting is handled by custom_exception_handler in exception_handler.py
            raise

        # Log API requests and errors
        if request.path.startswith('/api/'):
            query_string = request.GET.urlencode()
            if getattr(request, 'oauth2_access_token', None) is not None:
                auth_suffix = ' (OAUTH)'
            elif getattr(request, 'api_key', None) is not None:
                auth_suffix = ' (API KEY)'
            else:
                auth_suffix = ''
            if query_string:
                log_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}{auth_suffix} - {client_ip} - {response.status_code}"
            else:
                log_msg = f"{request.method} {request.path} - {user_identifier}{auth_suffix} - {client_ip} - {response.status_code}"

            _log_request_line(log_msg, response.status_code)

        # Log static file requests (no username for static files)
        elif request.path.startswith('/static/'):
            # Get file size if available
            content_length = _get_content_length(response)

            # Build log message
            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes - {response.status_code}"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip} - {response.status_code}"

            _log_request_line(log_msg, response.status_code)

        # Log favicon requests (no username)
        elif request.path == '/favicon.ico':
            # Get file size if available
            content_length = _get_content_length(response)

            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes - {response.status_code}"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip} - {response.status_code}"

            _log_request_line(log_msg, response.status_code)

        # Log root and other non-API requests (no username)
        elif not request.path.startswith('/api/') and not request.path.startswith('/admin/') and not request.path.startswith('/account/'):
            # This catches root path and other non-API routes
            # Skip static files as they're handled above, but this is a fallback
            if not request.path.startswith('/static/'):
                log_msg = f"{request.method} {request.path} - {client_ip} - {response.status_code}"
                _log_request_line(log_msg, response.status_code)

        return response
