from functools import wraps

from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt, csrf_protect
from django.utils.decorators import method_decorator

from users.api_keys import validate_api_key


def login_required_401(view_func):
    @wraps(view_func)
    def _wrapped_view(request, *args, **kwargs):
        if request.user.is_authenticated:
            return view_func(request, *args, **kwargs)
        else:
            return JsonResponse({'error': 'Unauthorized'}, status=401)

    return _wrapped_view


def api_or_login_required_401(allow_api_keys=True):
    """
    Universal security decorator that handles both authentication and CSRF protection.
    
    - For session-authenticated requests: Enforces CSRF protection
    - For API key-authenticated requests: Bypasses CSRF (API keys don't need CSRF tokens)
    
    This decorator bundles CSRF handling internally, so you don't need separate decorators.
    
    Args:
        allow_api_keys: If True, allows API key authentication. If False, only
                       session authentication is allowed (useful for routes that
                       manage API keys themselves).
    
    Usage:
        @api_or_login_required_401()
        def my_view(request):
            # Works with both session auth (with CSRF) and API key auth (no CSRF)
            ...
        
        @api_or_login_required_401(allow_api_keys=False)
        def manage_keys_view(request):
            # Only session auth allowed, with CSRF protection
            ...
    """
    def decorator(view_func):
        # Wrap the view function
        @wraps(view_func)
        def _wrapped_view(request, *args, **kwargs):
            # Check if user is already authenticated via session
            if request.user.is_authenticated:
                # Session auth: apply CSRF protection
                # Temporarily remove exemption and apply csrf_protect
                # We do this by calling the protected version
                protected_view = csrf_protect(view_func)
                request.is_api_authenticated = False
                return protected_view(request, *args, **kwargs)
            
            # If API keys are not allowed for this route, reject
            if not allow_api_keys:
                return JsonResponse({'error': 'Unauthorized'}, status=401)
            
            # Try to authenticate via API key
            auth_header = request.META.get('HTTP_AUTHORIZATION', '')
            if not auth_header.startswith('Bearer '):
                return JsonResponse({'error': 'Unauthorized'}, status=401)
            
            # Extract the token
            token = auth_header[7:].strip()  # Remove 'Bearer ' prefix
            
            # Validate the API key
            result = validate_api_key(token)
            if result is None:
                return JsonResponse({'error': 'Unauthorized'}, status=401)
            
            user, api_key = result
            
            # Set the user on the request
            request.user = user
            request.api_key = api_key
            request.is_api_authenticated = True
            
            # API key requests bypass CSRF - mark as exempt
            request._dont_enforce_csrf_checks = True
            return view_func(request, *args, **kwargs)
        
        # Apply csrf_exempt to the outer wrapper so API key requests bypass CSRF
        # Session auth will use csrf_protect internally
        return csrf_exempt(_wrapped_view)
    
    return decorator
