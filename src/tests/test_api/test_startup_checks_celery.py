from unittest.mock import Mock, patch

import pytest
from django.test import override_settings

from website.startup_checks.celery import check_celery_beat, check_celery_worker


@pytest.mark.django_db
class TestStartupChecksCelery:
    def test_check_celery_worker_success(self):
        task_result = Mock()
        task_result.get.return_value = "pong"

        with override_settings(CELERY_WORKER_STARTUP_TIMEOUT_SECONDS=3), patch(
            "website.startup_checks.celery.celery_app.send_task", return_value=task_result
        ):
            assert check_celery_worker() is True

    def test_check_celery_worker_failure(self):
        with override_settings(CELERY_WORKER_STARTUP_TIMEOUT_SECONDS=3), patch(
            "website.startup_checks.celery.celery_app.send_task",
            side_effect=RuntimeError("worker unavailable"),
        ):
            assert check_celery_worker() is False

    def test_check_celery_beat_success(self):
        redis_client = Mock()
        redis_client.get.return_value = "1000.0"

        with override_settings(
            CELERY_BEAT_HEARTBEAT_MAX_AGE_SECONDS=20,
            CELERY_BEAT_STARTUP_WAIT_SECONDS=1,
        ), patch(
            "website.startup_checks.celery.get_redis_connection", return_value=redis_client
        ), patch("website.startup_checks.celery.time.time", return_value=1010.0):
            assert check_celery_beat() is True

    def test_check_celery_beat_failure(self):
        redis_client = Mock()
        redis_client.get.return_value = None

        with override_settings(
            CELERY_BEAT_HEARTBEAT_MAX_AGE_SECONDS=5,
            CELERY_BEAT_STARTUP_WAIT_SECONDS=0,
        ), patch(
            "website.startup_checks.celery.get_redis_connection", return_value=redis_client
        ):
            assert check_celery_beat() is False
