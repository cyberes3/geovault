from functools import wraps

from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt, csrf_protect


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
            # API key (set by APIKeyResolutionMiddleware): bypass CSRF so Bearer token is enough
            if getattr(request, 'is_api_authenticated', False) and allow_api_keys:
                return view_func(request, *args, **kwargs)
            # Session auth: requires CSRF for POST and other state-changing methods
            if request.user.is_authenticated:
                protected_view = csrf_protect(view_func)
                request.is_api_authenticated = False
                return protected_view(request, *args, **kwargs)

            return JsonResponse({'error': 'Unauthorized'}, status=401)

        # Apply csrf_exempt to the outer wrapper so API key requests bypass CSRF
        # Session auth will use csrf_protect internally
        return csrf_exempt(_wrapped_view)

    return decorator
