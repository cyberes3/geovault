from functools import wraps

from django.http import JsonResponse


def login_required_401(view_func):
    @wraps(view_func)
    def _wrapped_view(request, *args, **kwargs):
        if request.user.is_authenticated:
            return view_func(request, *args, **kwargs)
        else:
            return JsonResponse({'error': 'Unauthorized'}, status=401)

    return _wrapped_view
