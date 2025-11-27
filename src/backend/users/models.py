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
