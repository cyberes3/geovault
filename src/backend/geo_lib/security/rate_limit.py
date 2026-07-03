"""
Shared Redis-backed rate limiting.

This is the single rate-limiting primitive used across the codebase (core views,
extensions, and WebSocket consumers) so there is exactly one implementation of
"how do we count requests per window in Redis" to reason about and test.

Backed by the dedicated 'rate_limiting' Redis cache alias (see CACHES in
website/settings.py), which works correctly across multiple worker processes,
unlike the default LocMemCache alias.
"""
import json
import time
from functools import wraps
from typing import Callable, Optional

from asgiref.sync import sync_to_async
from django.core.cache import caches
from django.http import HttpRequest, HttpResponse

from api.utils.responses import error_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_client_ip

_logger = get_tagged_logger('RateLimit')

_RATE_LIMIT_MESSAGE = 'Rate limit exceeded, please slow down.'


def _rate_limit_response() -> HttpResponse:
    return error_response(_RATE_LIMIT_MESSAGE, code=429)


def _default_identity(request: HttpRequest) -> str:
    """Rate-limit identity: authenticated user id if available, else client IP."""
    user = getattr(request, 'user', None)
    if user is not None and getattr(user, 'is_authenticated', False):
        return f"user:{user.id}"
    return f"ip:{get_client_ip(request)}"


def _default_consumer_identity(consumer) -> str:
    """Rate-limit identity for a WebSocket consumer method: authenticated user id if available,
    else the connection's own channel name (every connection has a distinct one, so unauthenticated
    connections are still rate-limited individually rather than sharing one global bucket)."""
    user = getattr(consumer, 'user', None)
    if user is not None and getattr(user, 'is_authenticated', False):
        return f"user:{user.id}"
    return f"channel:{getattr(consumer, 'channel_name', 'unknown')}"


class RedisRateLimiter:
    """
    Fixed-window rate limiter backed by the Redis 'rate_limiting' cache alias.

    Fails open (allows the request) only when Redis itself is unreachable, so a
    Redis outage degrades to "unprotected" rather than taking down unrelated
    features. Fails closed (rejects) whenever the limit is genuinely exceeded.
    """

    def __init__(self, name: str, limit: int, window_seconds: float = 1.0):
        """
        Args:
            name: Unique identifier for this limiter, used as part of the cache key.
            limit: Maximum number of calls allowed per identity within window_seconds.
            window_seconds: Length of the fixed window, in seconds.
        """
        self.name = name
        self.limit = limit
        self.window_seconds = window_seconds

    def _cache(self):
        return caches['rate_limiting']

    def _cache_key(self, identity: str) -> str:
        window = int(time.time() / self.window_seconds)
        return f"{self.name}:{identity}:{window}"

    def check(self, identity: str) -> bool:
        """
        Record one call for `identity` and return whether it's within the limit.
        Returns True (allowed) if the Redis cache is unreachable.
        """
        key = self._cache_key(identity)
        try:
            cache = self._cache()
            try:
                count = cache.incr(key)
            except ValueError:
                # Key doesn't exist yet in this window; create it. A concurrent
                # request may win this race, in which case its incr() above wins
                # and we simply treat ours as the first hit of the window too.
                cache.add(key, 1, timeout=int(self.window_seconds) + 1)
                count = 1
        except Exception as e:
            _logger.warning(f"Rate limiter '{self.name}' cache unavailable, allowing request: {e}")
            return True
        return count <= self.limit

    def enforce(self, identity: str) -> Optional[HttpResponse]:
        """
        Check `identity` against the limit and return a ready-to-return 429
        HttpResponse if it's exceeded, or None if the request may proceed.

        Use this directly (as a guard clause) only when the rate-limit identity
        can't be derived from the request alone and is instead computed partway
        through a view (e.g. a resource id resolved from the request body) — so
        the `__call__` decorator can't be used. Prefer `@limiter()` otherwise.
        """
        if self.check(identity):
            return None
        return _rate_limit_response()

    def __call__(self, identity_func: Optional[Callable[[HttpRequest], str]] = None) -> Callable:
        """
        Decorator factory for Django views. This is the standard way to rate-limit
        a view: instantiate one `RedisRateLimiter` per limit tier and decorate the
        view(s) with it, e.g. `@_my_limiter()`.

        Identity defaults to the authenticated user's id (falling back to client
        IP), namespaced per decorated view so each endpoint gets its own bucket
        automatically — the same limiter instance can be reused across multiple
        views without their counters colliding. Pass `identity_func` to customize
        (e.g. to key on something other than user/IP).
        """
        resolve_identity = identity_func or _default_identity

        def decorator(view_func: Callable) -> Callable:
            bucket = f"{view_func.__module__}.{view_func.__qualname__}"

            @wraps(view_func)
            def _wrapped(request: HttpRequest, *args, **kwargs):
                identity = f"{bucket}:{resolve_identity(request)}"
                response = self.enforce(identity)
                if response is not None:
                    return response
                return view_func(request, *args, **kwargs)

            return _wrapped

        return decorator

    def for_consumer(self, identity_func: Optional[Callable[[object], str]] = None) -> Callable:
        """
        Decorator factory for an async WebSocket consumer method (typically `receive`). Same
        one-limiter-per-tier, `@_my_limiter.for_consumer()` usage as the view decorator above, just
        adapted for consumers: identity is resolved from the consumer instance (`self`) rather than
        a `request`, and the check itself runs off the event loop via `sync_to_async` since the
        underlying cache client is synchronous.

        Exceeding the limit drops the incoming message and sends a rate_limit error frame back to
        the client instead of closing the connection -- one noisy message shouldn't kill an
        otherwise-legitimate, possibly-long-lived session.
        """
        resolve_identity = identity_func or _default_consumer_identity

        def decorator(method: Callable) -> Callable:
            bucket = f"{method.__module__}.{method.__qualname__}"

            @wraps(method)
            async def _wrapped(consumer, *args, **kwargs):
                identity = f"{bucket}:{resolve_identity(consumer)}"
                allowed = await sync_to_async(self.check, thread_sensitive=False)(identity)
                if not allowed:
                    await consumer.send(text_data=json.dumps({
                        'type': 'error',
                        'data': {'code': 429, 'message': _RATE_LIMIT_MESSAGE},
                    }))
                    return None
                return await method(consumer, *args, **kwargs)

            return _wrapped

        return decorator
