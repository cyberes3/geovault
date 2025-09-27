import logging
import signal
import sys
import threading
import time

from geo_lib.daemon.database.connection import Database
from geo_lib.daemon.workers.importer import create_import_worker

logging.basicConfig(level=logging.INFO)  # TODO: config level
_logger = logging.getLogger("DAEMON")


class DaemonManager:
    """
    Manages the daemon lifecycle with proper worker coordination and graceful shutdown.
    """
    
    def __init__(self):
        self.workers = []
        self.shutdown_requested = threading.Event()
        self._setup_signal_handlers()
        _logger.info('Daemon manager initialized')

    def _setup_signal_handlers(self):
        """Setup signal handlers for graceful shutdown."""
        def signal_handler(signum, frame):
            _logger.info(f'Received signal {signum}, initiating graceful shutdown')
            self.shutdown_requested.set()
        
        signal.signal(signal.SIGTERM, signal_handler)
        signal.signal(signal.SIGINT, signal_handler)

    def add_worker(self, worker):
        """Add a worker to be managed by the daemon."""
        self.workers.append(worker)
        _logger.info(f'Added worker: {worker.__class__.__name__}')

    def start_workers(self):
        """Start all registered workers in separate threads."""
        worker_threads = []
        
        for worker in self.workers:
            thread = threading.Thread(target=worker.run, name=f"Worker-{worker.worker_id}")
            thread.daemon = True  # Allow main process to exit even if workers are running
            thread.start()
            worker_threads.append(thread)
            _logger.info(f'Started worker thread: {thread.name}')
        
        return worker_threads

    def graceful_shutdown(self):
        """Perform graceful shutdown of all workers."""
        _logger.info('Starting graceful shutdown of all workers...')
        
        # Signal all workers to shutdown
        for worker in self.workers:
            if hasattr(worker, 'shutdown_requested'):
                worker.shutdown_requested.set()
        
        # Wait for workers to finish (with timeout)
        shutdown_timeout = 30  # seconds
        start_time = time.time()
        
        for worker in self.workers:
            if hasattr(worker, 'shutdown_requested'):
                remaining_time = shutdown_timeout - (time.time() - start_time)
                if remaining_time > 0:
                    if worker.shutdown_requested.wait(timeout=remaining_time):
                        _logger.info(f'Worker {worker.worker_id} shutdown completed')
                    else:
                        _logger.warning(f'Worker {worker.worker_id} shutdown timeout')
                else:
                    _logger.warning(f'Worker {worker.worker_id} shutdown timeout (global)')
        
        _logger.info('All workers shutdown completed')

    def run(self):
        """Main daemon loop."""
        _logger.info('Starting daemon...')

        # Initialize database connection
        _logger.info('Connecting to database...')
        Database.initialise(minconn=1, maxconn=100, host='172.0.2.105', database='geoserver', user='geoserver', password='juu1waigu1pookee1ohcierahMoofie3')

        # Create and add workers
        import_worker = create_import_worker()
        self.add_worker(import_worker)

        # Start all workers
        worker_threads = self.start_workers()
        _logger.info('All workers started')

        try:
            # Main loop - wait for shutdown signal
            while not self.shutdown_requested.is_set():
                if self.shutdown_requested.wait(timeout=1.0):
                    break
                
                # Check if any worker threads have died unexpectedly
                for i, thread in enumerate(worker_threads):
                    if not thread.is_alive():
                        _logger.error(f'Worker thread {thread.name} died unexpectedly')
                        # Could restart the worker here if needed
                        
        except Exception as e:
            _logger.error(f'Unexpected error in daemon main loop: {e}')
        finally:
            self.graceful_shutdown()
            _logger.info('Daemon shutdown completed')


if __name__ == "__main__":
    daemon = DaemonManager()
    daemon.run()
