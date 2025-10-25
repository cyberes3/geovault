"""
Job processors for asynchronous operations.
Provides singleton instances for import and delete jobs.
"""

from geo_lib.processing.status_tracker import status_tracker
from .import_job import ImportJob
from .delete_job import DeleteJob

# Singleton instances to avoid repeated object creation
import_job = ImportJob(status_tracker)
delete_job = DeleteJob(status_tracker)