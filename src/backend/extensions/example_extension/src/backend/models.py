from django.conf import settings
from django.db import models

EXAMPLE_SCOPE = "example_extension"


class ExampleItem(models.Model):
    """User-owned demo row. Never query this table without a user filter."""

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="example_items",
    )
    name = models.CharField(max_length=255)
    description = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        app_label = "example_extension"

    def __str__(self):
        return self.name
