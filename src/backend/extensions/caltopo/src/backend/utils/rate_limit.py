"""
Shared rate limiter for CalTopo API endpoints: 1 request/sec per user per route.

Decorate any view with `@caltopo_rate_limited` and it gets its own bucket automatically
(keyed by the view's module + qualname), so this one `RedisRateLimiter` instance is reused
across every CalTopo view without their counters colliding.
"""
from api.utils.rate_limiting import rate_limited
from geo_lib.security.rate_limit import RedisRateLimiter

_caltopo_rate_limiter = RedisRateLimiter(name='caltopo', limit=1, window_seconds=1.0)

caltopo_rate_limited = rate_limited(_caltopo_rate_limiter)
