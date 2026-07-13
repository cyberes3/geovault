import logging
import os

from celery import Celery
from website.extensions.extension_hooks import get_registered_periodic_bg_tasks

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "website.settings")

logger = logging.getLogger("website.celery")

celery_app = Celery("website")
celery_app.config_from_object("django.conf:settings", namespace="CELERY")

# Registers the lazy autodiscovery signal; the app registry isn't ready yet at this point (this
# module is imported before Django finishes loading INSTALLED_APPS), so discovery can't be
# forced here. See `api.apps.DatamanageConfig.ready` for where it's actually forced.
celery_app.autodiscover_tasks()


@celery_app.on_after_finalize.connect
def _register_extension_periodic_tasks(sender, **kwargs):
    """
    Register periodic tasks provided by extensions.
    This runs after task discovery/finalization so task names are resolvable.
    """
    for item in get_registered_periodic_bg_tasks():
        task_name = item["task_name"]
        schedule_name = item["schedule_name"]
        schedule_value = item["schedule"]
        args = item["args"]
        kwargs_value = item["kwargs"]
        options = item["options"]

        task = sender.tasks.get(task_name)
        if task is None:
            logger.warning(
                "Skipping extension periodic task '%s': task '%s' not found",
                schedule_name,
                task_name,
            )
            continue

        sender.add_periodic_task(
            schedule_value,
            task.s(*args, **kwargs_value),
            name=schedule_name,
            **options,
        )
