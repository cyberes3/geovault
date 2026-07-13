"""
Shared Redis-backed rate limiting.

This is the single rate-limiting primitive used across the codebase (core views,
extensions, and WebSocket consumers) so there is exactly one implementation of
"how do we count requests per window in Redis" to reason about and test.

Backed by the dedicated 'rate_limiting' Redis cache alias (see CACHES in
website/settings.py), which works correctly across multiple worker processes,
unlike the default LocMemCache alias.

This module has no HTTP-response concerns: exceeding a limit raises `RateLimitExceeded`.
Django view decorating (translating that exception into a 429 response) lives in
`api.utils.rate_limiting.rate_limited`, since constructing an HTTP response is an
application-layer concern, not a library one. WebSocket consumer support (`for_consumer`)
stays here since it only ever sends a WS error frame, never a Django response.
"""
import json
import time
from functools import wraps
from typing import Callable, Optional

from asgiref.sync import sync_to_async
from django.core.cache import caches
from django.http import HttpRequest

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_client_ip

_logger = get_tagged_logger('RateLimit')

RATE_LIMIT_MESSAGE = 'Rate limit exceeded, please slow down.'


class RateLimitExceeded(Exception):
    """Raised by `RedisRateLimiter.enforce()` when the caller's identity is over its limit."""

    def __init__(self, limiter_name: str):
        self.limiter_name = limiter_name
        super().__init__(f"Rate limit '{limiter_name}' exceeded")


def default_identity(request: HttpRequest) -> str:
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

    def enforce(self, identity: str) -> None:
        """
        Check `identity` against the limit and raise `RateLimitExceeded` if it's exceeded.

        Use this directly (as a guard clause) when the rate-limit identity can't be derived
        from the request alone and is instead computed partway through a view (e.g. a
        resource id resolved from the request body) — so `api.utils.rate_limiting.rate_limited`
        can't be used as a decorator. Prefer that decorator otherwise.
        """
        if not self.check(identity):
            raise RateLimitExceeded(self.name)

    def for_consumer(self, identity_func: Optional[Callable[[object], str]] = None) -> Callable:
        """
        Decorator factory for an async WebSocket consumer method (typically `receive`): one
        `RedisRateLimiter` per limit tier, decorate the method with `@_my_limiter.for_consumer()`.
        Identity is resolved from the consumer instance (`self`) rather than a `request`, and the
        check itself runs off the event loop via `sync_to_async` since the underlying cache
        client is synchronous.

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
                        'data': {'code': 429, 'message': RATE_LIMIT_MESSAGE},
                    }))
                    return None
                return await method(consumer, *args, **kwargs)

            return _wrapped

        return decorator
