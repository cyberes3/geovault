"""
Shared helpers for stripping session/CSRF cookies from tile-proxy responses so Cloudflare (and
other CDNs) can cache them - a Set-Cookie header on a response makes most CDNs treat it as
uncacheable. Used by both session.CustomSessionMiddleware and security_headers.CustomHeaderMiddleware
(the latter as a fallback safety net, since it runs closer to the view and catches cookies set by
middleware - e.g. CsrfViewMiddleware - that runs between the two).
"""
TILE_PATH_PREFIX = '/api/tiles/'


def is_tile_request(request) -> bool:
    """True if this request is served by the tile proxy (candidate for CDN caching)."""
    return request.path.startswith(TILE_PATH_PREFIX)


def strip_response_cookies(response, cookie_names=None) -> None:
    """
    Remove cookies (and their Set-Cookie headers) from a response.

    If cookie_names is None, removes every cookie on the response. Otherwise only removes the
    given cookie names, leaving any others (and the Set-Cookie header) intact.
    """
    if cookie_names is None:
        response.cookies.clear()
        while response.has_header('Set-Cookie'):
            del response['Set-Cookie']
        return
    for name in cookie_names:
        if name in response.cookies:
            del response.cookies[name]


def strip_vary_cookie(response) -> None:
    """Remove 'Cookie' from the response's Vary header, if present, leaving other values intact.
    Django's SessionMiddleware sets Vary: Cookie whenever a session cookie is present, which
    itself prevents CDN caching even after the cookie is stripped."""
    if not response.has_header('Vary'):
        return
    vary_parts = [v.strip() for v in response['Vary'].split(',')]
    vary_parts = [v for v in vary_parts if v.lower() != 'cookie']
    if vary_parts:
        response['Vary'] = ', '.join(vary_parts)
    else:
        del response['Vary']
