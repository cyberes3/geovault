"""
AppConfig for the CalTopo extension.

This extension provides integration with CalTopo for importing maps and features.
"""
import logging
from pathlib import Path

from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_hook
from website.extensions.extension_logging import register_logging_filter

logger = logging.getLogger('caltopo.apps')


class CaltopoExtensionConfig(ExtensionAppConfig):
    """
    AppConfig for the CalTopo extension.
    """
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'extensions.caltopo.src.backend'
    label = 'caltopo'
    verbose_name = 'CalTopo Extension'
    path = str(Path(__file__).parent.resolve())

    def extension_ready(self):
        """
        Called after Django is fully initialized.
        
        This is where we register hooks and configure logging for the extension.
        """
        logger.info("CalTopo extension initializing...")

        # Register import hook to track imported features
        register_hook('import', 'caltopo_import', self.handle_import)
        logger.info("Registered import hook: caltopo.caltopo_import")

        # Register logging filter to suppress caltopo_python library messages
        # Import here to avoid importing at module level (which can trigger model imports)
        from extensions.caltopo.src.backend.logging_filters import SuppressCaltopoFilter
        register_logging_filter(SuppressCaltopoFilter())

        logger.info("CalTopo extension initialized successfully")

    def handle_import(self, import_item, user_id, created_features):
        """
        Import hook callback to update CalTopo imported_features mapping after import completes.
        
        This function is called after a successful import. It receives:
        - import_item: The ImportQueue item that was imported
        - user_id: ID of the user who imported the item
        - created_features: List of FeatureStore objects that were created
        
        Args:
            import_item: ImportQueue instance
            user_id: Integer user ID
            created_features: List of FeatureStore instances
        """
        if not created_features:
            return

        # Filter features with CalTopo metadata
        caltopo_features = [
            f for f in created_features
            if f.geojson.get('properties', {}).get('caltopo_map_id')
        ]

        if not caltopo_features:
            return

        # Get or create CalTopoUser record
        from django.contrib.auth import get_user_model
        User = get_user_model()
        try:
            user = User.objects.get(id=user_id)
            from extensions.caltopo.src.backend.models import CalTopoUser
            caltopo_user, _ = CalTopoUser.objects.get_or_create(user=user)
        except User.DoesNotExist:
            return

        # Group features by map_id for efficient updates
        features_by_map = {}
        for feature in caltopo_features:
            props = feature.geojson.get('properties', {})
            map_id = props.get('caltopo_map_id')
            feature_id = props.get('caltopo_feature_id')

            if map_id and feature_id:
                if map_id not in features_by_map:
                    features_by_map[map_id] = {}
                features_by_map[map_id][feature_id] = feature.id

        # Update imported_features mapping
        if features_by_map:
            if not caltopo_user.imported_features:
                caltopo_user.imported_features = {}

            for map_id, feature_mapping in features_by_map.items():
                if map_id not in caltopo_user.imported_features:
                    caltopo_user.imported_features[map_id] = {}
                caltopo_user.imported_features[map_id].update(feature_mapping)

            caltopo_user.save()
