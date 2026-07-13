from django.apps import AppConfig

from website.celery_app import celery_app


class ApiConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'api'

    def ready(self):
        """
        Finalize Celery task discovery now that Django's app registry is ready.

        `celery_app.autodiscover_tasks()` connects a lazy signal that only fires when a real
        Celery worker/beat process imports its default modules; the plain Django process (and
        anything dispatching a task by name via `celery_app.tasks[name]`, e.g.
        `geo_lib.processing.jobs.process_job.dispatch.dispatch_import_job`) never triggers that on its
        own. `force=True` runs discovery immediately instead, which needs the app registry
        (not ready yet when `website.celery_app` itself is first imported) - hence doing it
        here rather than in that module.
        """
        celery_app.autodiscover_tasks(force=True)
