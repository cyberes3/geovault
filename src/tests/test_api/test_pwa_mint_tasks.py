from unittest.mock import patch

import pytest
from django.contrib.auth import get_user_model
from django.test import Client
from django.test import RequestFactory
from types import ModuleType

from extensions.pwa_mint.src.backend.apps import PwaMintConfig, _should_enqueue_startup_check
from extensions.pwa_mint.src.backend.views import admin_force_regenerate_pwa_apk
from extensions.pwa_mint.src.backend.worker import (
    enqueue_startup_check,
    pwa_check_and_regenerate_task,
)

User = get_user_model()


@pytest.mark.django_db
class TestPwaMintCeleryTasks:
    def test_check_and_regenerate_triggers_regen_when_stale(self):
        with patch(
            "extensions.pwa_mint.src.backend.worker._should_regenerate",
            return_value=True,
        ), patch(
            "extensions.pwa_mint.src.backend.worker._regenerate_apk",
            return_value=True,
        ) as regenerate_mock:
            assert pwa_check_and_regenerate_task() is True
            regenerate_mock.assert_called_once()

    def test_check_and_regenerate_skips_when_fresh(self):
        with patch(
            "extensions.pwa_mint.src.backend.worker._should_regenerate",
            return_value=False,
        ), patch(
            "extensions.pwa_mint.src.backend.worker._regenerate_apk",
            return_value=True,
        ) as regenerate_mock:
            assert pwa_check_and_regenerate_task() is True
            regenerate_mock.assert_not_called()

    def test_enqueue_startup_check_queues_task(self):
        with patch(
            "extensions.pwa_mint.src.backend.worker.celery_app.send_task"
        ) as send_task_mock:
            enqueue_startup_check("extensions.pwa_mint.check_and_regenerate")
            send_task_mock.assert_called_once_with(
                "extensions.pwa_mint.check_and_regenerate",
                queue="extensions",
            )

    def test_admin_force_regenerate_queues_async_task(self):
        client = Client()
        user = User.objects.create_user("pwa-admin@example.com", "password")
        user.is_staff = True
        user.save(update_fields=["is_staff"])
        client.force_login(user)

        with patch(
            "extensions.pwa_mint.src.backend.views.celery_app.send_task"
        ) as send_task_mock:
            response = client.post("/api/extensions/pwa-mint/admin/force-regenerate/")

        assert response.status_code == 202
        send_task_mock.assert_called_once_with(
            "extensions.pwa_mint.regenerate",
            queue="extensions",
        )

    def test_extension_ready_does_not_enqueue_startup_check_in_celery_process(self):
        mock_module = ModuleType("extensions.pwa_mint.src.backend")
        mock_module.__file__ = "/fake/path/extensions/pwa_mint/src/backend/__init__.py"
        config = PwaMintConfig("pwa_mint", mock_module)

        with patch(
            "extensions.pwa_mint.src.backend.apps.register_well_known"
        ), patch(
            "extensions.pwa_mint.src.backend.apps.get_keystore_info"
        ), patch(
            "extensions.pwa_mint.src.backend.apps.register_bg_task",
            side_effect=["extensions.pwa_mint.check_and_regenerate", "extensions.pwa_mint.regenerate"],
        ), patch(
            "extensions.pwa_mint.src.backend.apps.register_periodic_bg_task"
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_running_tests",
            return_value=False,
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_management_command",
            return_value=False,
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_celery_process",
            return_value=True,
        ), patch(
            "extensions.pwa_mint.src.backend.apps.enqueue_startup_check"
        ) as enqueue_mock:
            config.extension_ready()
            enqueue_mock.assert_not_called()

    def test_should_enqueue_startup_check_runserver_parent_false(self):
        with patch(
            "extensions.pwa_mint.src.backend.apps._is_running_tests", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_management_command", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_celery_process", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps.sys.argv",
            ["manage.py", "runserver"],
        ):
            assert _should_enqueue_startup_check() is False

    def test_should_enqueue_startup_check_runserver_child_false(self):
        with patch(
            "extensions.pwa_mint.src.backend.apps._is_running_tests", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_management_command", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps._is_celery_process", return_value=False
        ), patch(
            "extensions.pwa_mint.src.backend.apps.sys.argv",
            ["manage.py", "runserver"],
        ):
            assert _should_enqueue_startup_check() is False
