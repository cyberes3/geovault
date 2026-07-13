"""
Tests for the extension hooks system.

Tests cover:
- Hook registration with extension context
- Hook execution
- Hook ID prefixing
- Integration with execute_import_hooks
"""
import pytest
from unittest.mock import patch
from website.extensions.extension_hooks import (
    register_hook,
    get_hooks,
    execute_hooks,
    get_registered_hooks,
    unregister_hook,
    set_extension_context,
    clear_extension_context,
    register_websocket_route,
    get_registered_websocket_routes,
    register_bg_task,
    register_periodic_bg_task,
    get_registered_bg_tasks,
    get_registered_periodic_bg_tasks,
)
import website.extensions.extension_hooks as extension_hooks_module
from geo_lib.processing.hooks import execute_import_hooks
from api.models import ImportQueue
from django.contrib.auth import get_user_model

User = get_user_model()


@pytest.mark.django_db
class TestExtensionHooks:
    """Test the extension hooks registration system."""
    
    def setup_method(self):
        """Clear extension context before each test."""
        clear_extension_context()
        # Clear any registered hooks
        extension_hooks_module._hook_registry.clear()
        extension_hooks_module._bg_task_registry.clear()
        extension_hooks_module._periodic_bg_task_registry.clear()
    
    def teardown_method(self):
        """Clear extension context after each test."""
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
        extension_hooks_module._bg_task_registry.clear()
        extension_hooks_module._periodic_bg_task_registry.clear()

    def test_register_websocket_route_with_context(self):
        """Registering a WebSocket route with extension context adds it to the registry."""
        class FakeConsumer:
            pass

        set_extension_context("test_extension")
        try:
            register_websocket_route(r"ws/extensions/test-ext/fake/$", FakeConsumer)
            routes = get_registered_websocket_routes()
            path_consumer_pairs = [(p, c) for p, c in routes]
            assert (r"ws/extensions/test-ext/fake/$", FakeConsumer) in path_consumer_pairs
        finally:
            clear_extension_context()
            # Remove the route we added so other tests are unaffected
            extension_hooks_module._websocket_routes[:] = [
                (p, c, e) for p, c, e in extension_hooks_module._websocket_routes
                if p != r"ws/extensions/test-ext/fake/$"
            ]

    def test_register_websocket_route_without_context_raises(self):
        """Registering a WebSocket route without extension context raises ValueError."""
        class FakeConsumer:
            pass

        with pytest.raises(ValueError, match="Cannot register WebSocket route outside of extension context"):
            register_websocket_route(r"ws/extensions/test-ext/fake/$", FakeConsumer)

    def test_register_websocket_route_invalid_path_raises(self):
        """Registering a WebSocket route with path not under ws/extensions/ raises ValueError."""
        class FakeConsumer:
            pass

        set_extension_context("test_extension")
        try:
            with pytest.raises(ValueError, match="WebSocket path must start with 'ws/extensions/'"):
                register_websocket_route(r"ws/other/path/$", FakeConsumer)
        finally:
            clear_extension_context()

    def test_get_registered_websocket_routes_returns_tuples(self):
        """get_registered_websocket_routes returns a list of (path_regex, consumer_class) tuples."""
        routes = get_registered_websocket_routes()
        assert isinstance(routes, list)
        for item in routes:
            assert isinstance(item, tuple)
            assert len(item) == 2
            assert isinstance(item[0], str)
            assert item[0].startswith("ws/extensions/")
    
    def test_set_extension_context(self):
        """Test setting extension context."""
        set_extension_context('test_extension')
        assert extension_hooks_module._current_extension_name == 'test_extension'
        clear_extension_context()
        assert extension_hooks_module._current_extension_name is None
    
    def test_register_hook_with_context(self):
        """Test registering a hook with extension context set."""
        set_extension_context('test_extension')
        
        def test_callback(*args, **kwargs):
            pass
        
        register_hook('import', 'test_hook', test_callback)
        
        hooks = get_hooks('import')
        assert len(hooks) == 1
        assert hooks[0][0] == 'test_extension.test_hook'
        assert hooks[0][1] == test_callback
        
        clear_extension_context()
    
    def test_register_hook_without_context_raises_error(self):
        """Test that registering a hook without context raises ValueError."""
        def test_callback(*args, **kwargs):
            pass
        
        with pytest.raises(ValueError, match="Cannot register hook outside of extension context"):
            register_hook('import', 'test_hook', test_callback)
    
    def test_register_hook_prefixes_id(self):
        """Test that hook IDs are automatically prefixed with extension name."""
        set_extension_context('my_extension')
        
        def test_callback(*args, **kwargs):
            pass
        
        register_hook('import', 'my_hook', test_callback)
        
        hooks = get_hooks('import')
        assert hooks[0][0] == 'my_extension.my_hook'
        
        clear_extension_context()
    
    def test_register_multiple_hooks_same_type(self):
        """Test registering multiple hooks of the same type."""
        set_extension_context('test_extension')
        
        def callback1(*args, **kwargs):
            pass
        
        def callback2(*args, **kwargs):
            pass
        
        register_hook('import', 'hook1', callback1)
        register_hook('import', 'hook2', callback2)
        
        hooks = get_hooks('import')
        assert len(hooks) == 2
        hook_ids = [h[0] for h in hooks]
        assert 'test_extension.hook1' in hook_ids
        assert 'test_extension.hook2' in hook_ids
        
        clear_extension_context()
    
    def test_register_hooks_different_types(self):
        """Test registering hooks of different types."""
        set_extension_context('test_extension')
        
        def import_callback(*args, **kwargs):
            pass
        
        def other_callback(*args, **kwargs):
            pass
        
        register_hook('import', 'import_hook', import_callback)
        register_hook('other_type', 'other_hook', other_callback)
        
        import_hooks = get_hooks('import')
        other_hooks = get_hooks('other_type')
        
        assert len(import_hooks) == 1
        assert len(other_hooks) == 1
        assert import_hooks[0][0] == 'test_extension.import_hook'
        assert other_hooks[0][0] == 'test_extension.other_hook'
        
        clear_extension_context()
    
    def test_register_hook_replaces_existing(self):
        """Test that registering a hook with same ID replaces the existing one."""
        set_extension_context('test_extension')
        
        call_count = {'count': 0}
        
        def callback1(*args, **kwargs):
            call_count['count'] += 1
        
        def callback2(*args, **kwargs):
            call_count['count'] += 10
        
        register_hook('import', 'test_hook', callback1)
        register_hook('import', 'test_hook', callback2)  # Replace
        
        hooks = get_hooks('import')
        assert len(hooks) == 1  # Only one hook, not two
        
        # Execute and verify callback2 was called (not callback1)
        execute_hooks('import', 'arg1', 'arg2')
        assert call_count['count'] == 10
        
        clear_extension_context()
    
    def test_execute_hooks_calls_callbacks(self):
        """Test that execute_hooks calls all registered callbacks."""
        set_extension_context('test_extension')
        
        call_log = []
        
        def callback1(*args, **kwargs):
            call_log.append(('callback1', args, kwargs))
        
        def callback2(*args, **kwargs):
            call_log.append(('callback2', args, kwargs))
        
        register_hook('import', 'hook1', callback1)
        register_hook('import', 'hook2', callback2)
        
        execute_hooks('import', 'arg1', 'arg2', kwarg1='value1')
        
        assert len(call_log) == 2
        assert call_log[0][0] == 'callback1'
        assert call_log[0][1] == ('arg1', 'arg2')
        assert call_log[0][2] == {'kwarg1': 'value1'}
        assert call_log[1][0] == 'callback2'
        
        clear_extension_context()
    
    def test_execute_hooks_handles_exceptions(self):
        """Test that execute_hooks handles exceptions gracefully."""
        set_extension_context('test_extension')
        
        call_log = []
        
        def failing_callback(*args, **kwargs):
            raise Exception("Hook failed!")
        
        def working_callback(*args, **kwargs):
            call_log.append('worked')
        
        register_hook('import', 'failing', failing_callback)
        register_hook('import', 'working', working_callback)
        
        # Should not raise, but log error
        execute_hooks('import', 'arg1')
        
        # Working callback should still be called
        assert len(call_log) == 1
        assert call_log[0] == 'worked'
        
        clear_extension_context()
    
    def test_get_registered_hooks_returns_all_types(self):
        """Test that get_registered_hooks returns hooks grouped by type."""
        set_extension_context('test_extension')
        
        def callback(*args, **kwargs):
            pass
        
        register_hook('import', 'import_hook', callback)
        register_hook('other_type', 'other_hook', callback)
        
        all_hooks = get_registered_hooks()
        
        assert 'import' in all_hooks
        assert 'other_type' in all_hooks
        assert 'test_extension.import_hook' in all_hooks['import']
        assert 'test_extension.other_hook' in all_hooks['other_type']
        
        clear_extension_context()
    
    def test_unregister_hook(self):
        """Test unregistering a hook."""
        set_extension_context('test_extension')
        
        def callback(*args, **kwargs):
            pass
        
        register_hook('import', 'test_hook', callback)
        assert len(get_hooks('import')) == 1
        
        result = unregister_hook('import', 'test_extension.test_hook')
        assert result is True
        assert len(get_hooks('import')) == 0
        
        # Try to unregister non-existent hook
        result = unregister_hook('import', 'test_extension.nonexistent')
        assert result is False
        
        clear_extension_context()
    
    def test_register_hook_validates_callback(self):
        """Test that register_hook validates callback is callable."""
        set_extension_context('test_extension')
        
        with pytest.raises(TypeError, match="Hook callback must be callable"):
            register_hook('import', 'test_hook', 'not a callable')
        
        clear_extension_context()
    
    def test_register_hook_unknown_type_warns(self):
        """Test that registering unknown hook type logs warning."""
        set_extension_context('test_extension')
        
        def callback(*args, **kwargs):
            pass
        
        # Should not raise, but log warning
        with patch('website.extensions.extension_hooks.logger') as mock_logger:
            register_hook('unknown_type', 'test_hook', callback)
            mock_logger.warning.assert_called_once()
        
        clear_extension_context()

    def test_register_bg_task_without_context_raises(self):
        """Background task registration without extension context should fail."""
        def callback():
            return True

        with pytest.raises(ValueError, match="Cannot register background task outside of extension context"):
            register_bg_task("task1", callback)

    def test_register_bg_task_with_context_prefixes_name(self):
        """Background task names should be extension-prefixed."""
        set_extension_context("test_extension")
        try:
            def callback():
                return True

            task_name = register_bg_task("task1", callback, queue="extensions")
            assert task_name == "extensions.test_extension.task1"
            tasks = get_registered_bg_tasks()
            assert len(tasks) == 1
            assert tasks[0]["task_name"] == "extensions.test_extension.task1"
            assert tasks[0]["extension_name"] == "test_extension"
            assert tasks[0]["queue"] == "extensions"
        finally:
            clear_extension_context()

    def test_register_bg_task_applies_hardening_options(self):
        """time_limit/soft_time_limit/autoretry_for/retry_kwargs reach the real Celery task."""
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
            celery_task = extension_hooks_module.current_app.tasks[task_name]
            assert celery_task.time_limit == 90
            assert celery_task.soft_time_limit == 60
            assert OSError in celery_task.autoretry_for
            assert celery_task.retry_kwargs == {"max_retries": 3}
        finally:
            clear_extension_context()

    def test_register_periodic_bg_task_without_context_raises(self):
        """Periodic registration without extension context should fail."""
        with pytest.raises(ValueError, match="Cannot register periodic background task outside of extension context"):
            register_periodic_bg_task("sched1", "extensions.test_extension.task1", 60.0)

    def test_register_periodic_bg_task_with_context(self):
        """Periodic registration stores schedule metadata."""
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
            assert len(items) == 1
            assert items[0]["task_name"] == task_name
            assert items[0]["args"] == [1]
            assert items[0]["kwargs"] == {"a": 2}
            assert items[0]["options"] == {"queue": "extensions"}
        finally:
            clear_extension_context()

    def test_register_periodic_bg_task_rejects_invalid_task_ref(self):
        """A task_ref that's neither a name string nor a Celery task object should raise."""
        set_extension_context("test_extension")
        try:
            with pytest.raises(TypeError, match="task_ref must be a task name string or Celery task object"):
                register_periodic_bg_task("sched1", object(), 60.0)
        finally:
            clear_extension_context()


@pytest.mark.django_db
class TestExtensionHooksIntegration:
    """execute_import_hooks runs import hooks from the extension registry."""

    def setup_method(self):
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
        extension_hooks_module._bg_task_registry.clear()
        extension_hooks_module._periodic_bg_task_registry.clear()

    def teardown_method(self):
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
        extension_hooks_module._bg_task_registry.clear()
        extension_hooks_module._periodic_bg_task_registry.clear()

    def test_execute_import_hooks_runs_extension_import_hooks(self):
        call_log = []

        def cb(import_item, user_id, created_features):
            call_log.append("a")

        set_extension_context("test_extension")
        register_hook("import", "ext_hook", cb)
        clear_extension_context()

        user = User.objects.create_user("test@example.com", "password")
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename="test.kml",
            raw_file="<kml>test</kml>",
            file_hash="test_hash",
            imported=True,
        )

        execute_import_hooks(import_item, user.id, [])

        assert call_log == ["a"]

    def test_execute_import_hooks_calls_multiple_import_hooks(self):
        call_log = []

        def cb_a(import_item, user_id, created_features):
            call_log.append("a")

        def cb_b(import_item, user_id, created_features):
            call_log.append("b")

        set_extension_context("test_extension")
        register_hook("import", "one", cb_a)
        register_hook("import", "two", cb_b)
        clear_extension_context()

        user = User.objects.create_user("test2@example.com", "password")
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename="test.kml",
            raw_file="<kml>test</kml>",
            file_hash="test_hash2",
            imported=True,
        )

        execute_import_hooks(import_item, user.id, [])

        assert set(call_log) == {"a", "b"}


@pytest.mark.django_db
class TestExtensionAppConfig:
    """Test ExtensionAppConfig base class."""
    
    def test_extension_app_config_inheritance(self):
        """Test that ExtensionAppConfig can be inherited."""
        from website.extensions.extension_base import ExtensionAppConfig
        
        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'
            verbose_name = 'Test Extension'
        
        # Just verify the class can be defined and has correct attributes
        assert TestExtensionConfig.name == 'test_extension.src.backend'
        assert TestExtensionConfig.label == 'test_extension'
        assert issubclass(TestExtensionConfig, ExtensionAppConfig)
    
    def test_extension_ready_method_called(self):
        """Test that extension_ready() is called during ready()."""
        from website.extensions.extension_base import ExtensionAppConfig
        import os
        from types import ModuleType
        
        ready_called = {'called': False}
        
        # Create a mock module
        mock_module = ModuleType('test_extension.src.backend')
        mock_module.__file__ = '/fake/path/test_extension/src/backend/__init__.py'
        
        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'
            
            def extension_ready(self):
                ready_called['called'] = True
        
        config = TestExtensionConfig('test_extension', mock_module)
        
        # Mock RUN_MAIN to allow ready() to execute
        with patch.dict(os.environ, {'RUN_MAIN': 'true'}):
            with patch('sys.argv', ['manage.py', 'runserver']):
                config.ready()
        
        assert ready_called['called'] is True
    
    def test_extension_ready_can_register_hooks(self):
        """Test that extension_ready() can register hooks."""
        from website.extensions.extension_base import ExtensionAppConfig
        import os
        from types import ModuleType
        
        hook_called = {'called': False}
        
        # Create a mock module
        mock_module = ModuleType('test_extension.src.backend')
        mock_module.__file__ = '/fake/path/test_extension/src/backend/__init__.py'
        
        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'
            
            def extension_ready(self):
                from website.extensions.extension_hooks import register_hook
                
                def test_hook(*args, **kwargs):
                    hook_called['called'] = True
                
                register_hook('import', 'test_hook', test_hook)
        
        config = TestExtensionConfig('test_extension', mock_module)
        
        with patch.dict(os.environ, {'RUN_MAIN': 'true'}):
            with patch('sys.argv', ['manage.py', 'runserver']):
                config.ready()
        
        # Verify hook was registered
        hooks = get_hooks('import')
        assert len(hooks) == 1
        assert hooks[0][0] == 'test_extension.test_hook'
        
        # Execute hook
        execute_hooks('import')
        assert hook_called['called'] is True
    
    def test_ready_skips_in_reloader_process(self):
        """Test that ready() skips execution in reloader process."""
        from website.extensions.extension_base import ExtensionAppConfig
        import os
        from types import ModuleType
        
        ready_called = {'called': False}
        
        # Create a mock module
        mock_module = ModuleType('test_extension.src.backend')
        mock_module.__file__ = '/fake/path/test_extension/src/backend/__init__.py'
        
        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'
            
            def extension_ready(self):
                ready_called['called'] = True
        
        config = TestExtensionConfig('test_extension', mock_module)
        
        # Set RUN_MAIN to something other than 'true' (reloader process)
        with patch.dict(os.environ, {'RUN_MAIN': 'false'}):
            config.ready()
        
        # Should not be called in reloader
        assert ready_called['called'] is False

    def test_ready_skips_in_runserver_parent_when_run_main_missing(self):
        """Test that ready() skips runserver parent process when RUN_MAIN is missing."""
        from website.extensions.extension_base import ExtensionAppConfig
        import os
        from types import ModuleType

        ready_called = {'called': False}

        mock_module = ModuleType('test_extension.src.backend')
        mock_module.__file__ = '/fake/path/test_extension/src/backend/__init__.py'

        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'

            def extension_ready(self):
                ready_called['called'] = True

        config = TestExtensionConfig('test_extension', mock_module)

        with patch.dict(os.environ, {}, clear=True):
            with patch('sys.argv', ['manage.py', 'runserver']):
                config.ready()

        assert ready_called['called'] is False
    
    def test_ready_skips_during_migrations(self):
        """Test that ready() skips during migrations."""
        from website.extensions.extension_base import ExtensionAppConfig
        import os
        from types import ModuleType
        
        ready_called = {'called': False}
        
        # Create a mock module
        mock_module = ModuleType('test_extension.src.backend')
        mock_module.__file__ = '/fake/path/test_extension/src/backend/__init__.py'
        
        class TestExtensionConfig(ExtensionAppConfig):
            name = 'test_extension.src.backend'
            label = 'test_extension'
            
            def extension_ready(self):
                ready_called['called'] = True
        
        config = TestExtensionConfig('test_extension', mock_module)
        
        with patch.dict(os.environ, {'RUN_MAIN': 'true'}):
            with patch('sys.argv', ['manage.py', 'migrate']):
                config.ready()
        
        # Should not be called during migrations
        assert ready_called['called'] is False


@pytest.mark.django_db
class TestExtensionLoaderWithAppConfig:
    """Test extension loader with ExtensionAppConfig."""
    
    def test_dynamic_app_config_inherits_extension_app_config(self):
        """Test that dynamically created AppConfig inherits from ExtensionAppConfig."""
        import tempfile
        from pathlib import Path
        from website.extensions.extension_loader import ExtensionRegistry
        from website.extensions.extension_base import ExtensionAppConfig
        from unittest.mock import patch, MagicMock
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ext_dir = Path(tmpdir)
            ext_path = ext_dir / 'test_ext'
            ext_path.mkdir()
            
            manifest_path = ext_path / 'manifest.py'
            manifest_path.write_text('name = "test_ext"\nversion = "1.0.0"')
            
            backend_path = ext_path / 'src' / 'backend'
            backend_path.mkdir(parents=True)
            (backend_path / '__init__.py').write_text('')
            # Don't create apps.py - should use dynamic config
            
            registry = ExtensionRegistry(ext_dir)
            with patch('website.extensions.extension_loader.get_config') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.extension_settings.return_value = {'enabled': True}
                mock_loader_get.return_value = mock_config
                
                apps = registry.discover_extensions()
                
                # Should have created dynamic AppConfig
                assert len(apps) == 1
                app_config_path = apps[0]
                
                # Import and verify it inherits from ExtensionAppConfig
                module_path, class_name = app_config_path.rsplit('.', 1)
                module = __import__(module_path, fromlist=[class_name])
                app_config_class = getattr(module, class_name)
                
                assert issubclass(app_config_class, ExtensionAppConfig)
