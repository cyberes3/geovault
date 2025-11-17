"""
Unified file import pipeline processors.
Factory module for creating appropriate processors based on file type.
"""

from typing import Union, Optional

from geo_lib.processing.file_types import FileType
from geo_lib.processing.file_types import detect_file_type
from geo_lib.processing.status_tracker import ProcessingStatusTracker
from .base_processor import BaseProcessor
from .gpx_processor import GPXProcessor
from .kml_processor import KMLProcessor
from .kmz_processor import KMZProcessor


def get_processor(file_data: Union[bytes, str], filename: str = "", 
                  job_id: Optional[str] = None,
                  status_tracker: Optional[ProcessingStatusTracker] = None) -> BaseProcessor:
    """
    Factory function to create the appropriate processor for a file type.
    
    Args:
        file_data: File content as bytes or string
        filename: Optional filename for type detection
        job_id: Optional job ID for cancellation checking
        status_tracker: Optional status tracker for cancellation checking
        
    Returns:
        Appropriate processor instance
        
    Raises:
        ValueError: If file type is not supported
    """
    file_type = detect_file_type(file_data, filename)

    if file_type == FileType.KML:
        return KMLProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker)
    elif file_type == FileType.KMZ:
        return KMZProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker)
    elif file_type == FileType.GPX:
        return GPXProcessor(file_data, filename, job_id=job_id, status_tracker=status_tracker)
    else:
        raise ValueError(f"Unsupported file type: {file_type}")


__all__ = [
    'BaseProcessor',
    'KMLProcessor',
    'KMZProcessor',
    'GPXProcessor',
    'get_processor'
]
