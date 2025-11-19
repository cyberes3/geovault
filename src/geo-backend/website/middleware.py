import traceback

from django.http import JsonResponse, HttpResponse

from geo_lib.logging.console import get_access_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

access_logger = get_access_logger()


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
        except Exception as e:
            # Log just the traceback
            traceback_str = traceback.format_exc()
            access_logger.error(traceback_str)
            
            # Return appropriate error response based on request path
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
        
        # Log API requests and errors
        if request.path.startswith('/api/'):
            query_string = request.GET.urlencode()
            if query_string:
                log_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}@{client_ip}"
            else:
                log_msg = f"{request.method} {request.path} - {user_identifier}@{client_ip}"
            
            if response.status_code >= 400:
                # Log errors with status
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                # Log successful requests
                access_logger.info(log_msg)
        
        # Log static file requests (no username for static files)
        elif request.path.startswith('/static/'):
            # Get file size if available
            content_length = ''
            try:
                # Try different methods to get Content-Length header
                if hasattr(response, 'get'):
                    content_length = response.get('Content-Length', '')
                if not content_length and hasattr(response, 'headers'):
                    content_length = response.headers.get('Content-Length', '')
                if not content_length and hasattr(response, '_headers'):
                    header_val = response._headers.get('content-length', ('', ''))
                    if isinstance(header_val, tuple) and len(header_val) > 1:
                        content_length = header_val[1]
                    elif isinstance(header_val, str):
                        content_length = header_val
            except Exception:
                # If we can't get content length, just log without it
                pass
            
            # Build log message
            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip}"
            
            # Log based on status code
            if response.status_code >= 400:
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                access_logger.info(log_msg)
        
        # Log favicon requests (no username)
        elif request.path == '/favicon.ico':
            # Get file size if available - try multiple ways to get Content-Length
            content_length = ''
            try:
                content_length = response.get('Content-Length', '')
            except (AttributeError, KeyError):
                pass
            if not content_length:
                try:
                    content_length = response.headers.get('Content-Length', '')
                except (AttributeError, KeyError):
                    pass
            if not content_length:
                try:
                    if hasattr(response, '_headers'):
                        content_length = response._headers.get('content-length', ('', ''))[1]
                except (AttributeError, KeyError, IndexError):
                    pass
            
            if content_length:
                log_msg = f"{request.method} {request.path} - {client_ip} - {content_length} bytes"
            else:
                log_msg = f"{request.method} {request.path} - {client_ip}"
            
            if response.status_code >= 400:
                access_logger.warning(f"{log_msg} - Status: {response.status_code}")
            else:
                access_logger.info(log_msg)
        
        # Log root and other non-API requests (no username)
        elif not request.path.startswith('/api/') and not request.path.startswith('/admin/') and not request.path.startswith('/account/'):
            # This catches root path and other non-API routes
            # Skip static files as they're handled above, but this is a fallback
            if not request.path.startswith('/static/'):
                log_msg = f"{request.method} {request.path} - {client_ip}"
                if response.status_code >= 400:
                    access_logger.warning(f"{log_msg} - Status: {response.status_code}")
                else:
                    access_logger.info(log_msg)
        
        return response


class CustomHeaderMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        response = self.get_response(request)
        response['Access-Control-Allow-Origin'] = '*'
        return response
