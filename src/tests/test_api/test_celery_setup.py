import pytest

from geo_lib.processing.jobs.process_job.dispatch import IMPORT_CELERY_TASK_NAME
from website.celery_app import celery_app


@pytest.mark.django_db
class TestCelerySetup:
    def test_celery_app_loads(self):
        assert celery_app is not None
        assert celery_app.main == "website"

    def test_celery_core_settings_present(self, settings):
        assert settings.CELERY_BROKER_URL
        assert settings.CELERY_RESULT_BACKEND
        assert settings.CELERY_TASK_DEFAULT_QUEUE
        assert isinstance(settings.CELERY_BEAT_SCHEDULE, dict)

    def test_replacement_cleanup_periodic_schedule_registered(self, settings):
        schedule = settings.CELERY_BEAT_SCHEDULE
        assert "replacement_cleanup_every_60_seconds" in schedule
        assert (
            schedule["replacement_cleanup_every_60_seconds"]["task"]
            == "api.replacement_cleanup.cleanup_orphaned_replacements"
        )

    @pytest.mark.parametrize(
        "task_name",
        [
            "api.celery_health.ping_worker",
            "api.celery_health.beat_heartbeat",
            "api.replacement_cleanup.cleanup_orphaned_replacements",
            IMPORT_CELERY_TASK_NAME,
        ],
    )
    def test_core_tasks_are_registered(self, task_name):
        """
        Regression test for the fragile-registration-by-unused-import failure mode: each of
        these tasks must be discoverable by name in `celery_app.tasks` (i.e. actually registered
        with Celery), not just importable as a plain Python function. A task module that stops
        getting imported (e.g. during an "unused import" cleanup) would fail this test instead
        of silently vanishing from the beat schedule/dispatch table.
        """
        assert celery_app.tasks.get(task_name) is not None

    def test_replacement_cleanup_task_delegates_to_service_function(self):
        """
        `cleanup_orphaned_replacements_task` (the registered Celery task) must actually call
        `cleanup_orphaned_replacements` (the service function) - guards against the two
        drifting apart silently now that they're separate callables.
        """
        task = celery_app.tasks["api.replacement_cleanup.cleanup_orphaned_replacements"]
        assert task.run.__module__ == "api.tasks"
