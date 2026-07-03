"""
Shared rate limiter for CalTopo API endpoints: 1 request/sec per user per route.

This is just a `RedisRateLimiter` instance (see geo_lib.security.rate_limit) — decorate
any view with `@caltopo_rate_limiter()` and it gets its own bucket automatically (keyed
by the view's module + qualname), so this one instance is reused across every CalTopo
view without their counters colliding.
"""
from geo_lib.security.rate_limit import RedisRateLimiter

caltopo_rate_limiter = RedisRateLimiter(name='caltopo', limit=1, window_seconds=1.0)
