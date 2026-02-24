import threading
import time
import logging
from datetime import datetime, timedelta
from pathlib import Path
from django.test import RequestFactory
from .views import _perform_pwa_generation
from .utils import get_apk_cache_path
from website.config_loader import get_config_loader

logger = logging.getLogger("website.pwa_mint.worker")

STARTUP_DELAY_SECONDS = 30  # Wait for app/server to be up before fetching manifest


class PWARegenerationWorker:
    """
    Background worker that automatically regenerates the PWA APK:
    - On startup if missing or older than 1 day
    - Every 24 hours thereafter

    Runs in a daemon thread only; start() returns immediately. Never blocks the
    server process or request handling. Not started during tests.
    """

    def __init__(self):
        self.thread = None
        self.running = False
        self.regeneration_interval = 24 * 60 * 60  # 24 hours in seconds

    def start(self):
        """Start the background worker thread. Returns immediately; does not block."""
        if self.running:
            logger.warning("PWA regeneration worker already running")
            return
            
        self.running = True
        self.thread = threading.Thread(target=self._worker_loop, daemon=True, name="PWARegenerationWorker")
        self.thread.start()
        logger.info("PWA regeneration worker started")
        
    def stop(self):
        """Stop the background worker thread."""
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        logger.info("PWA regeneration worker stopped")

    def _worker_loop(self):
        """Main worker loop with auto-restart on failure."""
        # Let the server finish starting so manifest.webmanifest is reachable (avoids 502)
        logger.info(f"Waiting {STARTUP_DELAY_SECONDS}s for server to be ready before first APK check...")
        for _ in range(STARTUP_DELAY_SECONDS):
            if not self.running:
                return
            time.sleep(1)
        while self.running:
            try:
                self._run_regeneration_cycle()
            except Exception as e:
                logger.error(f"PWA regeneration worker crashed: {e}", exc_info=True)
                logger.info("Restarting PWA regeneration worker in 60 seconds...")
                time.sleep(60)
                
    def _run_regeneration_cycle(self):
        """Run a single regeneration cycle."""
        # Check on startup
        if self._should_regenerate():
            logger.info("APK is missing or old, triggering regeneration...")
            self._regenerate_apk()
        else:
            logger.info("APK is up-to-date, skipping initial regeneration")
            
        # Then run every 24 hours
        while self.running:
            time.sleep(self.regeneration_interval)
            if self.running:
                logger.info("Daily APK regeneration triggered")
                self._regenerate_apk()
                
    def _should_regenerate(self):
        """Check if APK needs regeneration."""
        try:
            config = get_config_loader()
            domain = config.get_str('site.domain', 'geovault.example.com')
            package_id = f"com.geovault.webview.{domain.replace('.', '_')}".lower()
            cache_path = get_apk_cache_path(package_id)
            
            if not cache_path.exists():
                logger.info(f"APK not found at {cache_path}")
                return True
                
            # Check if older than 1 day
            mtime = datetime.fromtimestamp(cache_path.stat().st_mtime)
            age = datetime.now() - mtime
            if age > timedelta(days=1):
                logger.info(f"APK is {age.days} days old, regeneration needed")
                return True
                
            logger.info(f"APK is {age.seconds // 3600} hours old, still fresh")
            return False
            
        except Exception as e:
            logger.error(f"Error checking APK status: {e}", exc_info=True)
            return False
            
    def _regenerate_apk(self):
        """Trigger APK regeneration."""
        try:
            # Create a fake request object for the generation function
            factory = RequestFactory()
            config = get_config_loader()
            domain = config.get_str('site.domain', 'geovault.example.com')
            
            # Create a fake HTTPS request to the configured domain
            request = factory.post(f'https://{domain}/api/extensions/pwa-mint/generate/')
            request.META['HTTP_HOST'] = domain
            request.META['wsgi.url_scheme'] = 'https'
            
            logger.info("Starting APK generation...")
            start_time = time.time()
            
            response, success = _perform_pwa_generation(request)
            
            duration = time.time() - start_time
            
            if success:
                logger.info(f"APK regeneration completed successfully in {duration:.1f} seconds")
            else:
                logger.error(f"APK regeneration failed after {duration:.1f} seconds: {response.content.decode()}")
                
        except Exception as e:
            logger.error(f"Error during APK regeneration: {e}", exc_info=True)


# Global worker instance
_worker = None

def start_worker():
    """Start the global PWA regeneration worker."""
    global _worker
    if _worker is None:
        _worker = PWARegenerationWorker()
        _worker.start()
    return _worker

def stop_worker():
    """Stop the global PWA regeneration worker."""
    global _worker
    if _worker is not None:
        _worker.stop()
        _worker = None
