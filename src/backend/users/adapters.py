"""
Custom allauth adapters to prevent username from being used.
"""
import uuid
from allauth.account.adapter import DefaultAccountAdapter


class NoUsernameAccountAdapter(DefaultAccountAdapter):
    """
    Custom account adapter that generates a unique username but never uses it.
    The username field is required by Django's User model, but we don't want to
    store or use usernames - we only use email addresses.
    """
    
    def save_user(self, request, user, form, commit=True):
        """
        Override save_user to set username to a unique UUID.
        This satisfies Django's requirement for a username field while
        ensuring we never actually use it (we only use email).
        """
        # Call parent method first to handle email, password, etc.
        user = super().save_user(request, user, form, commit=False)
        
        # Generate a unique username that we'll never use
        # Using UUID ensures uniqueness and prevents conflicts
        # Set it after parent processing to ensure it's not overwritten
        user.username = str(uuid.uuid4())
        
        if commit:
            user.save()
        
        return user
    
    def get_email_confirmation_url(self, request, emailconfirmation):
        """
        Override to ensure URLs use the correct domain from config settings.
        Updates Site model if needed, then uses it for URL generation.
        """
        from django.urls import reverse
        from django.conf import settings
        from django.contrib.sites.models import Site
        
        # Update Site model from settings if needed (lazy update)
        if hasattr(settings, 'SITE_DOMAIN') and hasattr(settings, 'SITE_NAME'):
            try:
                site, _ = Site.objects.get_or_create(id=settings.SITE_ID)
                if site.domain != settings.SITE_DOMAIN or site.name != settings.SITE_NAME:
                    site.domain = settings.SITE_DOMAIN
                    site.name = settings.SITE_NAME
                    site.save()
            except Exception:
                pass  # If update fails, continue with existing Site values
        
        # Use Site model domain for URL
        try:
            site = Site.objects.get(id=settings.SITE_ID)
            protocol = 'https' if request.is_secure() or request.META.get('HTTP_X_FORWARDED_PROTO') == 'https' else 'http'
            path = reverse('account_confirm_email', args=[emailconfirmation.key])
            return f"{protocol}://{site.domain}{path}"
        except Site.DoesNotExist:
            # Fallback to request.build_absolute_uri() if Site doesn't exist
            path = reverse('account_confirm_email', args=[emailconfirmation.key])
            return request.build_absolute_uri(path)

