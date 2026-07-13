from website.public_url import site_protocol
from website.settings_utils import get_required_setting


class FixRequestHostMiddleware:
    """
    Middleware to fix request host for email URL generation.
    This ensures that request.build_absolute_uri() uses the Site domain
    instead of the request's actual host when they don't match.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        # Fix HTTP_HOST for password reset and email-related requests
        # This ensures allauth uses the correct domain when building URLs
        if (request.path.startswith('/accounts/password/reset/') or
                request.path.startswith('/accounts/email/') or
                request.path.startswith('/api/user/email/')):
            site_domain = get_required_setting('SITE_DOMAIN')
            # Always override HTTP_HOST to use Site domain
            request.META['HTTP_HOST'] = site_domain
            # Also override get_host() method
            def fixed_get_host():
                return site_domain
            request.get_host = fixed_get_host
            # Override build_absolute_uri to use Site domain
            def fixed_build_absolute_uri(location=None):
                if location is None:
                    location = request.get_full_path()
                if not location.startswith('/'):
                    return location
                return f"{site_protocol()}://{site_domain}{location}"
            request.build_absolute_uri = fixed_build_absolute_uri

        return self.get_response(request)
