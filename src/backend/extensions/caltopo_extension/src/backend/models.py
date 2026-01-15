from django.conf import settings
from django.db import models as django_models

from api.fields import EncryptedTextField


class CalTopoUser(django_models.Model):
    """Stores CalTopo credentials and import tracking for users."""
    user = django_models.OneToOneField(
        settings.AUTH_USER_MODEL,
        on_delete=django_models.CASCADE,
        related_name='caltopo_user',
        primary_key=True
    )
    account_id = django_models.CharField(
        max_length=6,
        help_text="6-character CalTopo account ID"
    )
    credential_id = django_models.CharField(
        max_length=12,
        help_text="12-character CalTopo credential code"
    )
    credential_key = EncryptedTextField(
        help_text="CalTopo credential key (encrypted at rest)"
    )
    imported_features = django_models.JSONField(
        default=dict,
        help_text="Maps CalTopo map_id -> dict of feature_id -> FeatureStore.id. Structure: {map_id: {caltopo_feature_id: geovault_feature_id, ...}, ...}"
    )
    last_synced = django_models.DateTimeField(
        null=True,
        blank=True,
        help_text="Last time CalTopo data was synced"
    )
    created_at = django_models.DateTimeField(auto_now_add=True)
    updated_at = django_models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'caltopo_extension_caltopouser'
        indexes = [
            django_models.Index(fields=['user'], name='caltopo_ext_user'),
        ]

    def __str__(self):
        return f"CalTopo user for {self.user.email or self.user.username}"
