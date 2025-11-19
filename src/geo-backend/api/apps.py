import os
import sys
from django.apps import AppConfig


class DatamanageConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'api'

    def ready(self):
        """
        Start background services when Django is ready.
        
        IMPORTANT: Preventing duplicate background service starts
        =========================================================
        Django's development server (runserver) uses an autoreload mechanism that spawns
        two processes:
        1. A parent "reloader" process that monitors file changes
        2. A child "main" process that actually runs the server
        
        The AppConfig.ready() method is called in BOTH processes, which would cause
        background services to start twice if we didn't prevent it.
        
        Django sets the RUN_MAIN environment variable to 'true' ONLY in the child
        process that actually runs the server. In the reloader process, RUN_MAIN is
        either not set or set to a different value.
        
        Our logic:
        - If RUN_MAIN is set but not 'true': we're in the reloader → skip
        - If RUN_MAIN is 'true': we're in the main dev process → start service
        - If RUN_MAIN is not set: we're in production (WSGI/ASGI) → start service
          (In production, ready() is only called once per process anyway)
        """
        import logging
        apps_logger = logging.getLogger('api.apps')
        
        # Skip if we're in the reloader process (development only)
        # In development (runserver), Django sets RUN_MAIN='true' only in the main process.
        # If RUN_MAIN is set but not 'true', we're in the reloader process - skip.
        # If RUN_MAIN is not set at all, we're in production (WSGI/ASGI) - continue.
        run_main = os.environ.get('RUN_MAIN')
        if run_main is not None and run_main != 'true':
            return
        
        # Don't start services during migrations, management commands, or tests
        # Only start when running the server (runserver) or in production (WSGI/ASGI)
        if len(sys.argv) > 1:
            command = sys.argv[1]
            # Skip for management commands that shouldn't run background services
            if command in ['migrate', 'makemigrations', 'test', 'shell', 'dbshell', 'collectstatic', 'flush']:
                return
            # Only start for runserver or if not a management command (WSGI/ASGI context)
            if command != 'runserver' and 'manage.py' in sys.argv[0]:
                return

        # Start the replacement cleanup service (idempotent - only starts once)
        # Import here to avoid circular imports and ensure Django is fully initialized
        try:
            from api.services.replacement_cleanup_service import ensure_service_started
            ensure_service_started()
        except Exception as e:
            # Log but don't fail app initialization if service fails to start
            apps_logger.error(f"Failed to start replacement cleanup service: {e}", exc_info=True)
