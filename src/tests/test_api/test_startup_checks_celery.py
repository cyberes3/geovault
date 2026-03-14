from unittest.mock import Mock, patch

import pytest

from website.startup_checks import check_celery_beat, check_celery_worker


@pytest.mark.django_db
class TestStartupChecksCelery:
    def test_check_celery_worker_success(self):
        config = Mock()
        config.get_int.return_value = 3
        task_result = Mock()
        task_result.get.return_value = "pong"

        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.celery_app.send_task", return_value=task_result
        ):
            assert check_celery_worker() is True

    def test_check_celery_worker_failure(self):
        config = Mock()
        config.get_int.return_value = 3

        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.celery_app.send_task",
            side_effect=RuntimeError("worker unavailable"),
        ):
            assert check_celery_worker() is False

    def test_check_celery_beat_success(self):
        config = Mock()

        def get_int_side_effect(key, default):
            if key == "celery.beat_heartbeat_max_age_seconds":
                return 20
            if key == "celery.beat_startup_wait_seconds":
                return 1
            return default

        config.get_int.side_effect = get_int_side_effect
        redis_client = Mock()
        redis_client.get.return_value = "1000.0"

        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_redis_connection", return_value=redis_client
        ), patch("website.startup_checks.time.time", return_value=1010.0):
            assert check_celery_beat() is True

    def test_check_celery_beat_failure(self):
        config = Mock()

        def get_int_side_effect(key, default):
            if key == "celery.beat_heartbeat_max_age_seconds":
                return 5
            if key == "celery.beat_startup_wait_seconds":
                return 0
            return default

        config.get_int.side_effect = get_int_side_effect
        redis_client = Mock()
        redis_client.get.return_value = None

        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_redis_connection", return_value=redis_client
        ):
            assert check_celery_beat() is False
