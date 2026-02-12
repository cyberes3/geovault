from website.extensions.extension_base import ExtensionAppConfig as ExtBase

class PlacesConfig(ExtBase):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'extensions.places.src.backend'
    label = 'places'
    verbose_name = 'Places'

    def extension_ready(self):
        pass
