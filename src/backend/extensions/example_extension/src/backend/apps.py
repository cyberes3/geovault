"""
AppConfig for the example extension.

This demonstrates how to:
1. Inherit from ExtensionAppConfig
2. Implement extension_ready() for initialization
3. Register hooks using the extension hooks system
4. Perform startup validation and checks
"""
import logging

from django.apps import AppConfig
from website.extension_base import ExtensionAppConfig
from website.extension_hooks import register_hook

logger = logging.getLogger('example_extension.apps')


class ExampleExtensionConfig(ExtensionAppConfig):
    """
    AppConfig for the example extension.
    
    This class demonstrates the recommended pattern for extension initialization:
    - Inherit from ExtensionAppConfig (provides ready() lifecycle)
    - Set name, label, and verbose_name
    - Implement extension_ready() for initialization tasks
    """
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'example_extension.src.backend'
    label = 'example_extension'
    verbose_name = 'Example Extension'
    
    def extension_ready(self):
        """
        Called after Django is fully initialized.
        
        This is where you should:
        - Register hooks
        - Validate configuration
        - Initialize logging
        - Perform startup checks
        
        The extension context is automatically set during this method,
        so hooks registered here will be prefixed with 'example_extension'.
        """
        logger.info("Example extension initializing...")
        
        # 1. Register import hook
        # This hook will be called after every successful import
        register_hook('import', 'example_import_handler', self.handle_import)
        logger.info("Registered import hook: example_extension.example_import_handler")
        
        # 2. Validate configuration (example)
        # In a real extension, you might check config values here
        if not self.validate_config():
            logger.warning("Example extension configuration validation failed")
        
        # 3. Perform startup checks (example)
        # In a real extension, you might check dependencies, external services, etc.
        self.perform_startup_checks()
        
        logger.info("Example extension initialized successfully")
    
    def handle_import(self, import_item, user_id, created_features):
        """
        Example import hook callback.
        
        This function is called after a successful import. It receives:
        - import_item: The ImportQueue item that was imported
        - user_id: ID of the user who imported the item
        - created_features: List of FeatureStore objects that were created
        
        Args:
            import_item: ImportQueue instance
            user_id: Integer user ID
            created_features: List of FeatureStore instances
        """
        logger.info(
            f"Example extension: Import hook triggered for import_item {import_item.id}, "
            f"user {user_id}, {len(created_features)} features created"
        )
        
        # Example: Log import statistics
        # In a real extension, you might:
        # - Update extension-specific tables
        # - Send notifications
        # - Trigger background processing
        # - Update analytics
        
        # This is just a demonstration - the hook is registered but doesn't
        # perform any actual processing in the example extension
    
    def validate_config(self) -> bool:
        """
        Validate extension configuration.
        
        Returns:
            True if configuration is valid, False otherwise
        """
        # Example validation - in a real extension, check actual config values
        # from website.config_loader import get_config_loader
        # config = get_config_loader()
        # some_setting = config.get('extensions.example_extension.some_setting')
        # if not some_setting:
        #     return False
        
        return True
    
    def perform_startup_checks(self):
        """
        Perform startup validation checks.
        
        In a real extension, you might:
        - Check database connectivity
        - Verify external service availability
        - Validate required settings
        - Check for required migrations
        """
        # Example: Check if models are accessible
        try:
            from .models import ExampleItem
            logger.debug("Example extension: Models accessible")
        except Exception as e:
            logger.error(f"Example extension: Failed to access models: {e}")
        
        # Example: Check if views module is importable
        try:
            from . import views
            logger.debug("Example extension: Views module accessible")
        except Exception as e:
            logger.error(f"Example extension: Failed to import views: {e}")
