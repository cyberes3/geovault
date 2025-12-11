"""
API views package.

This package has been reorganized into a more maintainable structure:

- features/     - Feature-related operations (CRUD, search, updates)
- imports/      - Import queue operations
- collections/  - Collection management
- sharing/      - Sharing functionality
- assets/       - Asset management (icons, fonts)
- services/     - External services (tiles, geocoding, geolocation)
- user/         - User settings
- config.py     - Configuration endpoint
- health.py     - Health check endpoint

For backward compatibility, key functions are re-exported below.
However, it's recommended to import directly from the new modules.
"""

# Re-export commonly used functions for backward compatibility
# (if any external code imports from api.views directly)

# Most imports should now come from the specific modules:
# from api.views.features.creation import create_quick_point
# from api.views.imports.upload import upload_item
# etc.
