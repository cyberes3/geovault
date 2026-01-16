from django.db import models

# ==============================================================================
# Extension Models
# ==============================================================================
# Extensions can define their own Django models just like the main application.
# These models are scoped to the extension and will have their own tables in 
# the database (prefixed with the extension name).

class ExampleItem(models.Model):
    """
    A simple example model for demonstrating extension-specific data storage.
    """
    # Standard Django fields are fully supported
    name = models.CharField(max_length=255)
    description = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        app_label = 'example_extension'

    def __str__(self):
        return self.name
