"""
Unified file import pipeline processors.
Factory module for creating appropriate processors based on file type.
"""

import os
from typing import Union, Optional

from geo_lib.processing.file_types import FileType
from geo_lib.processing.file_types import detect_file_type
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatusTracker
from .base_processor import BaseProcessor
from .gpx_processor import GPXProcessor
from .kml_processor import KMLProcessor
from .kmz_processor import KMZProcessor


def get_processor(file_data: Union[bytes, str], filename: str = "", 
                  job_id: Optional[str] = None,
                  status_tracker: Optional[ProcessingStatusTracker] = None,
                  minimal_processing: bool = False,
                  user_id: Optional[int] = None,
                  import_queue_id: Optional[int] = None) -> BaseProcessor:
    """
    Factory function to create the appropriate processor for a file type.
    
    Args:
        file_data: File content as bytes or string
        filename: Optional filename for type detection
        job_id: Optional job ID for cancellation checking
        status_tracker: Optional status tracker for cancellation checking
        minimal_processing: If True, skip tag generation and other expensive operations
        user_id: Optional user ID for database operations
        import_queue_id: Optional import queue ID for database operations
        
    Returns:
        Appropriate processor instance
        
    Raises:
        ValueError: If file type is not supported
    """
    file_type = detect_file_type(file_data, filename)

    # Check if file extension is supported (more reliable than content detection for unknown files)
    if filename:
        _, ext = os.path.splitext(filename.lower())
        supported_extensions = ['.kml', '.kmz', '.gpx']
        if ext and ext not in supported_extensions:
            raise ValueError(f"Unsupported file type: {ext}")

    if file_type == FileType.KML:
        return KMLProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker, 
                          minimal_processing=minimal_processing, user_id=user_id, import_queue_id=import_queue_id)
    elif file_type == FileType.KMZ:
        return KMZProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker, 
                          minimal_processing=minimal_processing, user_id=user_id, import_queue_id=import_queue_id)
    elif file_type == FileType.GPX:
        return GPXProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker, 
                          minimal_processing=minimal_processing, user_id=user_id, import_queue_id=import_queue_id)
    else:
        raise ValueError(f"Unsupported file type: {file_type}")


__all__ = [
    'BaseProcessor',
    'KMLProcessor',
    'KMZProcessor',
    'GPXProcessor',
    'get_processor'
]
