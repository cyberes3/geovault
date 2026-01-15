"""
Base AppConfig class for GeoVault extensions.

Extensions should inherit from ExtensionAppConfig and implement the
extension_ready() method to perform initialization tasks like hook registration.
"""
import os
import logging
from django.apps import AppConfig

from website.extension_hooks import set_extension_context, clear_extension_context

logger = logging.getLogger('website.extension_base')


class ExtensionAppConfig(AppConfig):
    """
    Base AppConfig class for GeoVault extensions.
    
    This class provides a standardized way for extensions to initialize and
    register hooks. Extensions should:
    
    1. Inherit from this class in their apps.py
    2. Set the 'name' attribute to their module path
    3. Set the 'label' attribute to their extension name (snake_case)
    4. Implement extension_ready() method for initialization
    
    Example:
        from website.extension_base import ExtensionAppConfig
        from website.extension_hooks import register_hook
        
        class MyExtensionConfig(ExtensionAppConfig):
            default_auto_field = 'django.db.models.BigAutoField'
            name = 'my_extension.src.backend'
            label = 'my_extension'
            verbose_name = 'My Extension'
            
            def extension_ready(self):
                # Register hooks
                register_hook('import', 'my_hook', self.my_import_callback)
                
            def my_import_callback(self, import_item, user_id, created_features):
                # Process imports
                pass
    """
    
    def ready(self):
        """
        Called when Django is fully initialized.
        
        This method:
        1. Handles RUN_MAIN check to prevent duplicate initialization in dev mode
        2. Sets extension context for hook registration
        3. Calls extension_ready() if implemented
        4. Clears extension context after initialization
        
        IMPORTANT: Preventing duplicate initialization
        ==============================================
        Django's development server (runserver) uses an autoreload mechanism that spawns
        two processes:
        1. A parent "reloader" process that monitors file changes
        2. A child "main" process that actually runs the server
        
        The AppConfig.ready() method is called in BOTH processes, which would cause
        initialization to run twice if we didn't prevent it.
        
        Django sets the RUN_MAIN environment variable to 'true' ONLY in the child
        process that actually runs the server. In the reloader process, RUN_MAIN is
        either not set or set to a different value.
        
        Our logic:
        - If RUN_MAIN is set but not 'true': we're in the reloader → skip
        - If RUN_MAIN is 'true': we're in the main dev process → initialize
        - If RUN_MAIN is not set: we're in production (WSGI/ASGI) → initialize
          (In production, ready() is only called once per process anyway)
        """
        # Skip if we're in the reloader process (development only)
        run_main = os.environ.get('RUN_MAIN')
        if run_main is not None and run_main != 'true':
            return
        
        # Don't initialize during migrations, management commands, or tests
        # Check if we're in a management command context
        import sys
        if 'migrate' in sys.argv or 'makemigrations' in sys.argv:
            return
        
        # Set extension context for hook registration
        extension_name = self.label or self.name.split('.')[-1]
        set_extension_context(extension_name)
        
        try:
            logger.info(f"Initializing extension: {extension_name}")
            
            # Call extension_ready() if implemented
            if hasattr(self, 'extension_ready') and callable(getattr(self, 'extension_ready')):
                self.extension_ready()
                logger.info(f"Extension '{extension_name}' initialized successfully")
            else:
                logger.debug(f"Extension '{extension_name}' has no extension_ready() method")
                
        except Exception as e:
            logger.error(
                f"Error initializing extension '{extension_name}': {e}",
                exc_info=True
            )
        finally:
            # Always clear extension context
            clear_extension_context()
    
    def extension_ready(self):
        """
        Override this method in your extension to perform initialization.
        
        This method is called after Django is fully initialized and is the
        recommended place to:
        - Register hooks using register_hook()
        - Validate configuration
        - Initialize logging
        - Perform startup checks
        - Set up background tasks (if needed)
        
        The extension context is automatically set during this method, so
        hooks registered here will be automatically prefixed with your
        extension name.
        
        Example:
            def extension_ready(self):
                from website.extension_hooks import register_hook
                
                # Register import hook
                register_hook('import', 'process_features', self.handle_import)
                
                # Validate configuration
                if not self.validate_config():
                    logger.warning("Extension configuration is invalid")
        """
        pass
