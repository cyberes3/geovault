"""Tracks authenticated users' last-activity timestamp, throttled via the cache framework."""
from django.contrib.auth.models import AnonymousUser
from django.core.cache import cache

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.ip_utils import get_user_identifier
from users.models import UserProfile

_logger = get_tagged_logger()

# Throttle activity updates to at most once per 30 seconds per user. Backed by the cache
# framework (Redis) rather than an unbounded module-level dict, so entries expire on their own
# instead of accumulating forever for every user who has ever made a request.
ACTIVITY_TRACKING_THROTTLE_SECONDS = 30
_ACTIVITY_TRACKING_CACHE_KEY_PREFIX = "user_activity_tracking:"


class ActivityTrackingMiddleware:
    """Middleware to track user activity by updating last_activity timestamp."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        # Process request first
        response = self.get_response(request)

        # Update activity for authenticated users
        # Skip static files, icons, fonts, and other non-user-interactive paths
        # Icon and font requests are static assets and shouldn't trigger activity tracking
        if (request.user and
                not isinstance(request.user, AnonymousUser) and
                not request.path.startswith('/static/') and
                not request.path.startswith('/api/icons/') and
                not request.path.startswith('/api/fonts/') and
                request.path != '/favicon.ico'):
            try:
                # Throttle activity updates to reduce database load: only update if the cache
                # key for this user has expired (>= ACTIVITY_TRACKING_THROTTLE_SECONDS old).
                cache_key = f"{_ACTIVITY_TRACKING_CACHE_KEY_PREFIX}{request.user.id}"
                if cache.add(cache_key, True, timeout=ACTIVITY_TRACKING_THROTTLE_SECONDS):
                    profile, _ = UserProfile.get_or_create_profile(request.user)
                    profile.update_activity()
            except Exception as e:
                # Log but don't break the request if activity tracking fails
                user_identifier = get_user_identifier(request)
                _logger.warning(f"Failed to update activity for user {user_identifier} on {request.path}: {str(e)}")

        return response
