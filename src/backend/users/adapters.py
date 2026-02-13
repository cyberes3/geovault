import logging
import uuid

from allauth.account.adapter import DefaultAccountAdapter
from django.conf import settings
from django.contrib.auth import get_user_model
from django.contrib.sites.models import Site
from django.core.cache import cache
from django.urls import reverse
from django.utils import timezone

from users.constants import (
    EMAIL_VERIFICATION_CACHE_KEY,
    EMAIL_VERIFICATION_COOLDOWN_SECONDS,
)

logger = logging.getLogger('users')


class NoUsernameAccountAdapter(DefaultAccountAdapter):
    """
    Custom account adapter that generates a unique username but never uses it.
    The username field is required by Django's User model, but we don't want to
    store or use usernames - we only use email addresses.
    
    This adapter also:
    1. Makes the first registered user a superuser/admin (for bootstrapping)
    2. Implements email confirmation cooldown to prevent duplicate sends
    3. Ensures correct domain URLs for email confirmation links
    """

    def populate_username(self, request, user):
        """
        Set username to a UUID. The parent would derive it from email (e.g.
        bob.joe@example.com -> "bob.joe"); we never use username
        and want an opaque value.
        """
        user.username = str(uuid.uuid4())

    def save_user(self, request, user, form, commit=True):
        """
        Set username to a unique UUID (via populate_username) and optionally
        make the first registered user a superuser/admin.
        """
        # Set first registered user to admin
        user_model = get_user_model()
        if not user_model.objects.exists():
            user.is_superuser = True
            user.is_staff = True

        # Parent calls populate_username(); our override sets username to UUID
        user = super().save_user(request, user, form, commit=commit)
        return user

    def send_confirmation_mail(self, request, emailconfirmation, signup=False):
        """
        Override to add a cooldown mechanism to prevent duplicate emails.
        
        This prevents duplicate emails from:
        1. Database transaction issues
        2. Race conditions in concurrent requests
        3. Multiple calls during signup/resend
        
        Uses a unified cache key system with the resend verification API
        with a 60-second cooldown.
        
        Note: Since email verification is optional, this only applies to
        authenticated users who are verifying their email address.
        
        Important: This method handles Django ORM object caching quirks.
        When EmailAddress is created during signup, it captures a reference
        to the user Python object. Even after the user is saved and gets an ID,
        email_address.user still points to the same in-memory Python object
        which might have been instantiated before the ID was assigned.
        We call refresh_from_db() to force Django to reload the user from
        the database and ensure we have the current committed state with the ID.
        """
        # Get the email address and user for cooldown tracking
        email_address = emailconfirmation.email_address
        email = email_address.email.lower()
        user = email_address.user

        # IMPORTANT: Refresh user from database to get the latest committed state
        # This ensures user.pk is properly set even in edge cases like test
        # transactions where object state might be stale
        # Without this, user.pk could be None even though the user exists in the DB.
        user.refresh_from_db()
        user_id = user.pk
        if not user or not user.pk:
            raise ValueError(f"send_confirmation_mail called with unsaved user for {email}.")

        cache_key = EMAIL_VERIFICATION_CACHE_KEY.format(user_id=user_id, email=email)

        # Check cooldown, skip if email was sent recently
        last_sent_time = cache.get(cache_key)
        if last_sent_time:
            elapsed = (timezone.now() - last_sent_time).total_seconds()
            remaining = EMAIL_VERIFICATION_COOLDOWN_SECONDS - elapsed
            if remaining > 0:
                # Skip sending the email
                return

        super().send_confirmation_mail(request, emailconfirmation, signup=signup)
        cache.set(cache_key, timezone.now(), timeout=EMAIL_VERIFICATION_COOLDOWN_SECONDS)  # reset cooldown

    def get_email_confirmation_url(self, request, emailconfirmation):
        """
        Override to ensure URLs use the correct domain from config settings.
        """
        # Use Site model domain for URL
        site = Site.objects.get(id=settings.SITE_ID)
        protocol = 'https' if request.is_secure() or request.META.get('HTTP_X_FORWARDED_PROTO') == 'https' else 'http'
        path = reverse('account_confirm_email', args=[emailconfirmation.key])
        return f"{protocol}://{site.domain}{path}"
