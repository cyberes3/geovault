import logging
import sys
import threading
import time

from geo_lib.daemon.database.connection import Database
from geo_lib.daemon.workers.importer import import_worker

logging.basicConfig(level=logging.INFO)  # TODO: config level
_logger = logging.getLogger("DAEMON")

if __name__ == "__main__":
    _logger.debug('Starting daemon...')

    # TODO: config
    _logger.info('Connecting to database...')
    Database.initialise(minconn=1, maxconn=100, host='172.0.2.105', database='geoserver', user='geoserver', password='juu1waigu1pookee1ohcierahMoofie3')

    import_thread = threading.Thread(target=import_worker)
    import_thread.start()
    _logger.info('Started importer')

    while True:
        try:
            time.sleep(3600)
        except KeyboardInterrupt:
            # TODO: shut down workers
            sys.exit(0)
