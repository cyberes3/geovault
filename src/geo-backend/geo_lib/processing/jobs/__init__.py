"""
Job processors for asynchronous operations.
Provides singleton instances for import and delete jobs.
"""

from geo_lib.processing.status_tracker import status_tracker
from .upload_job import UploadJob
from .delete_job import DeleteJob
from .bulk_import_job import BulkImportJob
from .bulk_delete_job import BulkDeleteJob

# Singleton instances to avoid repeated object creation
upload_job = UploadJob(status_tracker)
delete_job = DeleteJob(status_tracker)
bulk_import_job = BulkImportJob(status_tracker)
bulk_delete_job = BulkDeleteJob(status_tracker)