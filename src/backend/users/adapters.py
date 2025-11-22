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

