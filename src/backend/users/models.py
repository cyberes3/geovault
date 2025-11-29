from django.contrib.auth import get_user_model
from django.db import models
from django.utils import timezone

User = get_user_model()


class UserProfile(models.Model):
    """Extended user profile with activity tracking."""
    user = models.OneToOneField(
        User,
        on_delete=models.CASCADE,
        related_name='profile',
        primary_key=True
    )
    last_activity = models.DateTimeField(
        null=True,
        blank=True,
        help_text="Last time the user was active on the site"
    )

    class Meta:
        db_table = 'users_userprofile'

    def __str__(self):
        return f"Profile for {self.user.email or self.user.username}"

    @classmethod
    def get_or_create_profile(cls, user):
        """Get or create a profile for a user."""
        profile, created = cls.objects.get_or_create(user=user)
        return profile

    def update_activity(self):
        """Update the last activity timestamp to now."""
        self.last_activity = timezone.now()
        self.save(update_fields=['last_activity'])


class ApiKey(models.Model):
    """API keys for programmatic access to the API."""
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='api_keys'
    )
    name = models.CharField(
        max_length=255,
        help_text="User-provided name/label for this API key"
    )
    key_prefix = models.CharField(
        max_length=8,
        db_index=True,
        help_text="First 8 characters of the key for display and lookup"
    )
    key_hash = models.CharField(
        max_length=64,
        help_text="SHA-256 hash of the full API key"
    )
    created_at = models.DateTimeField(
        auto_now_add=True,
        help_text="When this API key was created"
    )
    last_used_at = models.DateTimeField(
        null=True,
        blank=True,
        help_text="When this API key was last used for authentication"
    )
    is_active = models.BooleanField(
        default=True,
        help_text="Whether this API key is active (can be used for authentication)"
    )

    class Meta:
        db_table = 'users_apikey'
        unique_together = [['user', 'key_prefix']]
        indexes = [
            models.Index(fields=['key_prefix']),
        ]

    def __str__(self):
        return f"API key '{self.name}' for {self.user.email or self.user.username} ({self.key_prefix}...)"
