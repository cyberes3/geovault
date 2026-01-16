"""
Base AppConfig class for GeoVault extensions.

Extensions should inherit from ExtensionAppConfig and implement the
extension_ready() method to perform initialization tasks like hook registration.
"""
import logging
import os

from django.apps import AppConfig

from website.extensions.extension_hooks import set_extension_context, clear_extension_context

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
        from website.extensions.extension_base import ExtensionAppConfig
        from website.extensions.extension_hooks import register_hook
        
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

    def extension_ready(self) -> None:
        """
        Override this method in your extension to perform initialization.
        
        This method is called after Django is fully initialized and is the
        recommended place to:
        - Register hooks using register_hook()
        - Validate configuration
        - Initialize logging
        - Perform startup checks
        - Set up background tasks (if needed)
        """
        pass
