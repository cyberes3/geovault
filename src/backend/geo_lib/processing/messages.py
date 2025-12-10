"""
Centralized error and status messages for file processing operations.
"""

# Processing status messages
PROCESSING_FAILED = "Processing failed"
FILE_PROCESSING_FAILED = "File processing failed"
ERROR_OCCURRED_DURING_PROCESSING = "An error occurred during file processing"
FILE_VALIDATION_FAILED = "File validation failed"

# User-facing error messages
PROCESSING_FAILED_WITH_LOGS = "File processing failed. Please check the processing logs above for details."
PROCESSING_TIMEOUT = "File processing timed out: file may be too large or complex"

# Job error messages
JOB_FAILED_GENERIC = "An error occurred while processing the job. Please try again."
BULK_DELETE_JOB_FAILED = "An error occurred while deleting items. Some items may not have been deleted."
BULK_IMPORT_JOB_FAILED = "An error occurred while importing items. Some items may not have been imported."
DELETE_JOB_FAILED = "An error occurred while deleting the item. Please try again."
IMPORT_JOB_FAILED = "An error occurred while importing the item. Please try again."
ITEM_DELETE_FAILED = "An error occurred while deleting this item."
ITEM_IMPORT_FAILED = "An error occurred while importing this item."

# Error types for ImportQueue geofeatures
ERROR_TYPE_VALIDATION_FAILED = "validation_failed"
ERROR_TYPE_PROCESSING_FAILED = "processing_failed"
ERROR_TYPE_FILE_UNPARSABLE = "file_unparsable"
