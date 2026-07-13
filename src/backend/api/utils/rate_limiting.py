"""
Django-view decorator built on `geo_lib.security.rate_limit`.

Translating a `RateLimitExceeded` into a 429 `error_response()` is an application-layer
concern (constructing an HTTP response), so it lives here rather than in `geo_lib` itself.
"""
from functools import wraps
from typing import Callable, Optional

from django.http import HttpRequest

from api.utils.responses import error_response
from geo_lib.security.rate_limit import RATE_LIMIT_MESSAGE, RateLimitExceeded, RedisRateLimiter, default_identity


def rate_limited(limiter: RedisRateLimiter, identity_func: Optional[Callable[[HttpRequest], str]] = None) -> Callable:
    """
    Decorator factory for Django views. This is the standard way to rate-limit a view:
    instantiate one `RedisRateLimiter` per limit tier and decorate the view(s) with
    `@rate_limited(my_limiter)`.

    Identity defaults to the authenticated user's id (falling back to client IP), namespaced
    per decorated view so each endpoint gets its own bucket automatically — the same limiter
    instance can be reused across multiple views without their counters colliding. Pass
    `identity_func` to customize (e.g. to key on something other than user/IP).
    """
    resolve_identity = identity_func or default_identity

    def decorator(view_func: Callable) -> Callable:
        bucket = f"{view_func.__module__}.{view_func.__qualname__}"

        @wraps(view_func)
        def _wrapped(request: HttpRequest, *args, **kwargs):
            identity = f"{bucket}:{resolve_identity(request)}"
            try:
                limiter.enforce(identity)
            except RateLimitExceeded:
                return error_response(RATE_LIMIT_MESSAGE, code=429)
            return view_func(request, *args, **kwargs)

        return _wrapped

    return decorator
