import logging
import os

from celery import Celery

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "website.settings")

logger = logging.getLogger("website.celery")

celery_app = Celery("website")
celery_app.config_from_object("django.conf:settings", namespace="CELERY")

# Registers the lazy autodiscovery signal; the app registry isn't ready yet at this point (this
# module is imported before Django finishes loading INSTALLED_APPS), so discovery can't be
# forced here. See `api.apps.ApiConfig.ready` for where it's actually forced.
celery_app.autodiscover_tasks()


@celery_app.on_after_finalize.connect
def _register_extension_periodic_tasks(sender, **kwargs):
    """
    Bind extension periodic tasks if ExtensionRuntime already collected them.
    ExtensionRuntime.bind_periodic_tasks is idempotent and is the primary path
    (after every extension_ready()).
    """
    from website.extensions.runtime import ExtensionRuntime

    ExtensionRuntime.bind_periodic_tasks()
