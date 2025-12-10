"""
Job processors for asynchronous operations.
Provides singleton instances for import and delete jobs.
"""

from geo_lib.processing.status_tracker import status_tracker
from .process_job import ProcessJob
from .delete_job import DeleteJob
from .import_job import ImportJob
from .bulk_import_job import BulkImportJob
from .bulk_delete_job import BulkDeleteJob
from ...logging.console import get_job_logger

# Singleton instances to avoid repeated object creation
process_job = ProcessJob(status_tracker)
delete_job = DeleteJob(status_tracker)
import_job = ImportJob(status_tracker)
bulk_import_job = BulkImportJob(status_tracker)
bulk_delete_job = BulkDeleteJob(status_tracker)
_logger = get_job_logger()
