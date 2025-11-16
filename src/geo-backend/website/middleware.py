from geo_lib.logging.console import get_access_logger
from geo_lib.utils.ip_utils import get_client_ip, get_user_identifier

access_logger = get_access_logger()


class LoggingMiddleware:
    """Middleware to log all HTTP requests."""
    
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        user_identifier = get_user_identifier(request)
        client_ip = get_client_ip(request)
        
        # Log API requests only (static resources will be logged by nginx)
        if request.path.startswith('/api/'):
            query_string = request.GET.urlencode()
            if query_string:
                log_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}@{client_ip}"
            else:
                log_msg = f"{request.method} {request.path} - {user_identifier}@{client_ip}"
            access_logger.info(log_msg)
        
        response = self.get_response(request)
        
        # Log errors for all requests
        if response.status_code >= 400:
            query_string = request.GET.urlencode()
            if query_string:
                error_msg = f"{request.method} {request.path}?{query_string} - {user_identifier}@{client_ip} - Status: {response.status_code}"
            else:
                error_msg = f"{request.method} {request.path} - {user_identifier}@{client_ip} - Status: {response.status_code}"
            access_logger.warning(error_msg)
        
        return response


class CustomHeaderMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        response = self.get_response(request)
        response['Access-Control-Allow-Origin'] = '*'
        return response
