from django.contrib.sessions.middleware import SessionMiddleware

from website.middleware.tile_cookies import is_tile_request, strip_response_cookies, strip_vary_cookie
from website.settings_utils import get_setting


class CustomSessionMiddleware(SessionMiddleware):
    """
    Custom SessionMiddleware that prevents session cookies for tile requests.
    Based on: https://stackoverflow.com/questions/62486176/how-to-disable-cookies-in-django-manually
    """

    def process_response(self, request, response):
        # Call parent to handle normal session processing
        response = super().process_response(request, response)

        # Strip the session/CSRF cookies from tile-proxy responses so they stay cacheable
        # (e.g. by Cloudflare). security_headers.CustomHeaderMiddleware also does a broader
        # cookie strip closer to the view; this catches anything set on the way back out here
        # (e.g. by CsrfViewMiddleware, which runs between the two).
        if is_tile_request(request):
            session_cookie = get_setting('SESSION_COOKIE_NAME', 'sessionid')
            csrf_cookie = get_setting('CSRF_COOKIE_NAME', 'csrftoken')
            strip_response_cookies(response, [session_cookie, csrf_cookie])
            strip_vary_cookie(response)

        return response
