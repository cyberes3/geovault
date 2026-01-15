"""
Tests for the extension hooks system.

Tests cover:
- Hook registration with extension context
- Hook execution
- Hook ID prefixing
- Integration with legacy import hooks
"""
import pytest
from unittest.mock import MagicMock, patch
from website.extension_hooks import (
    register_hook,
    get_hooks,
    execute_hooks,
    get_registered_hooks,
    unregister_hook,
    set_extension_context,
    clear_extension_context
)
import website.extension_hooks as extension_hooks_module
from geo_lib.processing.hooks import register_import_hook, execute_import_hooks, get_registered_hooks as get_legacy_hooks
from api.models import ImportQueue, FeatureStore
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
    
    def teardown_method(self):
        """Clear extension context after each test."""
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
    
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
        
        def processing_callback(*args, **kwargs):
            pass
        
        register_hook('import', 'import_hook', import_callback)
        register_hook('processing', 'processing_hook', processing_callback)
        
        import_hooks = get_hooks('import')
        processing_hooks = get_hooks('processing')
        
        assert len(import_hooks) == 1
        assert len(processing_hooks) == 1
        assert import_hooks[0][0] == 'test_extension.import_hook'
        assert processing_hooks[0][0] == 'test_extension.processing_hook'
        
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
        register_hook('processing', 'processing_hook', callback)
        
        all_hooks = get_registered_hooks()
        
        assert 'import' in all_hooks
        assert 'processing' in all_hooks
        assert 'test_extension.import_hook' in all_hooks['import']
        assert 'test_extension.processing_hook' in all_hooks['processing']
        
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
        with patch('website.extension_hooks.logger') as mock_logger:
            register_hook('unknown_type', 'test_hook', callback)
            mock_logger.warning.assert_called_once()
        
        clear_extension_context()


@pytest.mark.django_db
class TestExtensionHooksIntegration:
    """Test integration between extension hooks and legacy import hooks."""
    
    def setup_method(self):
        """Clear hooks before each test."""
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
        from geo_lib.processing.hooks import _import_hooks
        _import_hooks.clear()
    
    def teardown_method(self):
        """Clear hooks after each test."""
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()
        from geo_lib.processing.hooks import _import_hooks
        _import_hooks.clear()
    
    def test_legacy_register_import_hook_still_works(self):
        """Test that legacy register_import_hook still works."""
        call_log = []
        
        def legacy_callback(import_item, user_id, created_features):
            call_log.append('legacy')
        
        # Register via legacy API (no extension context)
        register_import_hook('legacy_hook', legacy_callback)
        
        # Create mock import item
        user = User.objects.create_user('test@example.com', 'password')
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='test.kml',
            raw_file='<kml>test</kml>',
            file_hash='test_hash',
            imported=True
        )
        
        execute_import_hooks(import_item, user.id, [])
        
        assert len(call_log) == 1
        assert call_log[0] == 'legacy'
    
    def test_extension_hooks_executed_with_import_hooks(self):
        """Test that extension hooks are executed along with legacy hooks."""
        set_extension_context('test_extension')
        
        call_log = []
        
        def extension_callback(import_item, user_id, created_features):
            call_log.append('extension')
        
        def legacy_callback(import_item, user_id, created_features):
            call_log.append('legacy')
        
        # Register extension hook
        register_hook('import', 'ext_hook', extension_callback)
        
        # Register legacy hook
        register_import_hook('legacy_hook', legacy_callback)
        
        # Create mock import item
        user = User.objects.create_user('test@example.com', 'password')
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='test.kml',
            raw_file='<kml>test</kml>',
            file_hash='test_hash',
            imported=True
        )
        
        execute_import_hooks(import_item, user.id, [])
        
        # Both should be called
        assert len(call_log) == 2
        assert 'extension' in call_log
        assert 'legacy' in call_log
        
        clear_extension_context()
    
    def test_register_import_hook_in_extension_context_uses_new_system(self):
        """Test that register_import_hook in extension context uses new system."""
        set_extension_context('test_extension')
        
        call_log = []
        
        def callback(import_item, user_id, created_features):
            call_log.append('called')
        
        # Register via legacy API but in extension context
        register_import_hook('test_hook', callback)
        
        # Should be registered in extension hooks system
        hooks = get_hooks('import')
        assert len(hooks) == 1
        assert hooks[0][0] == 'test_extension.test_hook'
        
        clear_extension_context()


@pytest.mark.django_db
class TestExtensionAppConfig:
    """Test ExtensionAppConfig base class."""
    
    def test_extension_app_config_inheritance(self):
        """Test that ExtensionAppConfig can be inherited."""
        from website.extension_base import ExtensionAppConfig
        
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
        from website.extension_base import ExtensionAppConfig
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
        from website.extension_base import ExtensionAppConfig
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
                from website.extension_hooks import register_hook
                
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
        from website.extension_base import ExtensionAppConfig
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
    
    def test_ready_skips_during_migrations(self):
        """Test that ready() skips during migrations."""
        from website.extension_base import ExtensionAppConfig
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
        from website.extension_loader import ExtensionRegistry
        from website.extension_base import ExtensionAppConfig
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
            with patch('website.extension_loader.get_config_loader') as mock_loader_get:
                mock_config = MagicMock()
                mock_config.get_bool.return_value = True
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
