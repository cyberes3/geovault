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
        user_identifier = get_user_identifier(request)
        client_ip = get_client_ip(request)
        
        try:
            response = self.get_response(request)
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
        
        return response


class CustomHeaderMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        response = self.get_response(request)
        response['Access-Control-Allow-Origin'] = '*'
        return response
