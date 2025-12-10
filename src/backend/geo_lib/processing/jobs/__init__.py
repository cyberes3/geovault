"""
Job processors for asynchronous operations.
Provides singleton instances for import and delete jobs.
"""

from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from .bulk_delete_job import BulkDeleteJob
from .bulk_import_job import BulkImportJob
from .delete_job import DeleteJob
from .import_job import ImportJob
from .process_job import ProcessJob

# Singleton instances to avoid repeated object creation
process_job = ProcessJob(status_tracker)
delete_job = DeleteJob(status_tracker)
import_job = ImportJob(status_tracker)
bulk_import_job = BulkImportJob(status_tracker)
bulk_delete_job = BulkDeleteJob(status_tracker)
