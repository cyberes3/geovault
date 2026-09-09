"""
Tests for extension capabilities and ImportProvider registration.
"""
from types import ModuleType
from unittest.mock import MagicMock, patch

import pytest
from django.contrib.auth import get_user_model
from django.test import TestCase

import website.extensions.capabilities as capabilities_module
from api.models import ImportQueue
from geo_lib.processing.hooks import execute_import_hooks
from website.extensions.capabilities import (
    clear_extension_context,
    get_registered_bg_tasks,
    get_registered_periodic_bg_tasks,
    get_registered_websocket_routes,
    register_bg_task,
    register_periodic_bg_task,
    register_websocket_route,
    set_extension_context,
)
from website.extensions.import_provider import (
    ExternalIds,
    ImportHookPayload,
    clear_import_providers,
    list_import_providers,
    register_import_provider,
)
from website.extensions.runtime import ExtensionRuntime

User = get_user_model()


class _RecordingProvider:
    def __init__(self):
        self.extracted = []
        self.finalized = []

    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        self.extracted.append(geojson)
        name = (geojson.get("properties") or {}).get("name")
        if not name:
            return ExternalIds()
        return ExternalIds(by_provider={"test_extension": {"name": str(name)}})

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        self.finalized.append(payload)


class _FailingProvider:
    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        raise RuntimeError("extract failed")

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        raise RuntimeError("finalize failed")


@pytest.mark.django_db
class TestExtensionCapabilities:
    def setup_method(self):
        clear_extension_context()
        capabilities_module._bg_task_registry.clear()
        capabilities_module._periodic_bg_task_registry.clear()
        capabilities_module._websocket_routes[:] = [
            item for item in capabilities_module._websocket_routes if item[2] != "test_extension"
        ]
        clear_import_providers()

    def teardown_method(self):
        clear_extension_context()
        capabilities_module._bg_task_registry.clear()
        capabilities_module._periodic_bg_task_registry.clear()
        capabilities_module._websocket_routes[:] = [
            item for item in capabilities_module._websocket_routes if item[2] != "test_extension"
        ]
        clear_import_providers()

    def test_register_websocket_route_with_context(self):
        class FakeConsumer:
            pass

        set_extension_context("test_extension")
        try:
            register_websocket_route(r"ws/extensions/test-ext/fake/$", FakeConsumer)
            routes = get_registered_websocket_routes()
            assert (r"ws/extensions/test-ext/fake/$", FakeConsumer) in routes
        finally:
            clear_extension_context()

    def test_register_websocket_route_without_context_raises(self):
        class FakeConsumer:
            pass

        with pytest.raises(ValueError, match="Cannot register WebSocket route outside of extension context"):
            register_websocket_route(r"ws/extensions/test-ext/fake/$", FakeConsumer)

    def test_register_websocket_route_invalid_path_raises(self):
        class FakeConsumer:
            pass

        set_extension_context("test_extension")
        try:
            with pytest.raises(ValueError, match="WebSocket path must start with 'ws/extensions/'"):
                register_websocket_route(r"ws/other/path/$", FakeConsumer)
        finally:
            clear_extension_context()

    def test_set_extension_context(self):
        set_extension_context("test_extension")
        assert capabilities_module._current_extension_name == "test_extension"
        clear_extension_context()
        assert capabilities_module._current_extension_name is None

    def test_register_import_provider_requires_context(self):
        with pytest.raises(ValueError, match="Cannot register ImportProvider outside of extension context"):
            register_import_provider(_RecordingProvider())

    def test_register_import_provider_validates_methods(self):
        set_extension_context("test_extension")
        try:
            with pytest.raises(TypeError, match="extract_external_ids"):
                register_import_provider(object())
        finally:
            clear_extension_context()

    def test_register_bg_task_without_context_raises(self):
        def callback():
            return True

        with pytest.raises(ValueError, match="Cannot register background task outside of extension context"):
            register_bg_task("task1", callback)

    def test_register_bg_task_with_context_prefixes_name(self):
        set_extension_context("test_extension")
        try:
            def callback():
                return True

            task_name = register_bg_task("task1", callback, queue="extensions")
            assert task_name == "extensions.test_extension.task1"
            tasks = get_registered_bg_tasks()
            assert tasks[0]["task_name"] == "extensions.test_extension.task1"
            assert tasks[0]["extension_name"] == "test_extension"
            assert tasks[0]["queue"] == "extensions"
        finally:
            clear_extension_context()

    def test_register_bg_task_applies_hardening_options(self):
        set_extension_context("test_extension")
        try:
            def callback():
                return True

            task_name = register_bg_task(
                "hardened_task",
                callback,
                queue="extensions",
                time_limit=90,
                soft_time_limit=60,
                autoretry_for=(OSError,),
                retry_kwargs={"max_retries": 3},
            )
            celery_task = capabilities_module.current_app.tasks[task_name]
            assert celery_task.time_limit == 90
            assert celery_task.soft_time_limit == 60
            assert OSError in celery_task.autoretry_for
            assert celery_task.retry_kwargs == {"max_retries": 3}
        finally:
            clear_extension_context()

    def test_register_periodic_bg_task_without_context_raises(self):
        with pytest.raises(ValueError, match="Cannot register periodic background task outside of extension context"):
            register_periodic_bg_task("sched1", "extensions.test_extension.task1", 60.0)

    def test_register_periodic_bg_task_with_context(self):
        set_extension_context("test_extension")
        try:
            def callback():
                return True

            task_name = register_bg_task("task1", callback)
            schedule_name = register_periodic_bg_task(
                "every_minute",
                task_name,
                60.0,
                args=[1],
                kwargs={"a": 2},
                options={"queue": "extensions"},
            )
            assert schedule_name == "extensions.test_extension.every_minute"
            items = get_registered_periodic_bg_tasks()
            assert items[0]["task_name"] == task_name
            assert items[0]["args"] == [1]
            assert items[0]["kwargs"] == {"a": 2}
            assert items[0]["options"] == {"queue": "extensions"}
        finally:
            clear_extension_context()

    def test_register_periodic_bg_task_rejects_invalid_task_ref(self):
        set_extension_context("test_extension")
        try:
            with pytest.raises(TypeError, match="task_ref must be a task name string or Celery task object"):
                register_periodic_bg_task("sched1", object(), 60.0)
        finally:
            clear_extension_context()


@pytest.mark.django_db
class TestExtensionRuntime:
    def test_extension_ready_is_not_called_from_ready(self):
        from website.extensions.extension_base import ExtensionAppConfig

        ready_called = {"called": False}

        class TestExtensionConfig(ExtensionAppConfig):
            name = "test_extension.src.backend"
            label = "test_extension"

            def extension_ready(self):
                ready_called["called"] = True

        mock_module = ModuleType("test_extension.src.backend")
        mock_module.__file__ = "/fake/path/test_extension/src/backend/__init__.py"
        config = TestExtensionConfig("test_extension", mock_module)
        config.ready()
        assert ready_called["called"] is False

    def test_runtime_skips_runserver_parent(self):
        with patch.dict("os.environ", {}, clear=True):
            with patch("sys.argv", ["manage.py", "runserver"]):
                assert ExtensionRuntime.should_skip() is True

    def test_runtime_skips_migrate(self):
        with patch.dict("os.environ", {"RUN_MAIN": "true"}):
            with patch("sys.argv", ["manage.py", "migrate"]):
                assert ExtensionRuntime.should_skip() is True

    def test_extension_ready_can_register_import_provider(self):
        from website.extensions.extension_base import ExtensionAppConfig

        class TestExtensionConfig(ExtensionAppConfig):
            name = "test_extension.src.backend"
            label = "test_extension"

            def extension_ready(self):
                register_import_provider(_RecordingProvider())

        mock_module = ModuleType("test_extension.src.backend")
        mock_module.__file__ = "/fake/path/test_extension/src/backend/__init__.py"
        config = TestExtensionConfig("test_extension", mock_module)
        set_extension_context("test_extension")
        try:
            config.extension_ready()
        finally:
            clear_extension_context()
        assert len(list_import_providers()) == 1

    def test_dynamic_app_config_inherits_extension_app_config(self):
        import tempfile
        from pathlib import Path

        from website.extensions.extension_base import ExtensionAppConfig
        from website.extensions.registry import ExtensionRegistry

        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / "test_ext"
            ext_path.mkdir()
            (ext_path / "manifest.toml").write_text('name = "test_ext"\nversion = "1.0.0"')
            backend_path = ext_path / "src" / "backend"
            backend_path.mkdir(parents=True)
            (backend_path / "__init__.py").write_text("")

            registry = ExtensionRegistry(ext_dir)
            with patch("website.extensions.registry.get_config") as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {"enabled": True}
                mock_loader_get.return_value = mock_config
                apps = registry.discover_extensions()

            assert len(apps) == 1
            module_path, class_name = apps[0].rsplit(".", 1)
            module = __import__(module_path, fromlist=[class_name])
            app_config_class = getattr(module, class_name)
            assert issubclass(app_config_class, ExtensionAppConfig)


class TestImportProviderExecution(TestCase):
    def setUp(self):
        clear_extension_context()
        clear_import_providers()

    def tearDown(self):
        clear_extension_context()
        clear_import_providers()

    def test_register_import_provider_and_execute(self):
        provider = _RecordingProvider()
        set_extension_context("test_extension")
        register_import_provider(provider)
        clear_extension_context()

        user = User.objects.create_user(
            username="provider_user",
            email="provider@example.com",
            password="password",
        )
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename="test.kml",
            raw_file="<kml>test</kml>",
            file_hash="provider_hash",
            imported=True,
        )
        execute_import_hooks(import_item, user.id, [])
        self.assertEqual(len(provider.finalized), 1)
        self.assertEqual(provider.finalized[0].import_item, import_item)
        self.assertEqual(provider.finalized[0].user_id, user.id)
        self.assertEqual(provider.finalized[0].created_features, [])

    def test_failing_provider_does_not_block_others(self):
        working = _RecordingProvider()
        set_extension_context("test_extension")
        register_import_provider(_FailingProvider())
        register_import_provider(working)
        clear_extension_context()

        user = User.objects.create_user(
            username="failopen_user",
            email="failopen@example.com",
            password="password",
        )
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename="test.kml",
            raw_file="<kml>test</kml>",
            file_hash="failopen_hash",
            imported=True,
        )
        execute_import_hooks(import_item, user.id, [])
        self.assertEqual(len(working.finalized), 1)
