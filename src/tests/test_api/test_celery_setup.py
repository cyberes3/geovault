import pytest

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
