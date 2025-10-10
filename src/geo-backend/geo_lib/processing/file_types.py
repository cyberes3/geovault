"""
Centralized file type configuration and metadata.
This module provides a unified registry of supported file types with their properties.
"""

from dataclasses import dataclass
from typing import List, Dict, Any
from enum import Enum


class FileType(Enum):
    """Supported file types for processing."""
    KML = "kml"
    KMZ = "kmz"
    GPX = "gpx"


@dataclass
class FileTypeConfig:
    """Configuration for a specific file type."""
    file_type: FileType
    extensions: List[str]
    signatures: List[bytes]
    mime_types: List[str]
    max_size: int
    xml_root_elements: List[str]
    allowed_elements: List[str] = None
    is_archive: bool = False
    archive_extensions: List[str] = None


# Centralized file type configurations
FILE_TYPE_CONFIGS: Dict[FileType, FileTypeConfig] = {
    FileType.KML: FileTypeConfig(
        file_type=FileType.KML,
        extensions=['.kml'],
        signatures=[
            b'<?xml',
            b'<kml',
            b'<KML'
        ],
        mime_types=[
            'text/xml',
            'application/xml',
            'text/plain',
            'application/vnd.google-earth.kml+xml',
            'application/vnd.google-earth.kml'
        ],
        max_size=5 * 1024 * 1024,  # 5MB
        xml_root_elements=['kml'],
        allowed_elements=[
            'style', 'iconstyle', 'linestyle', 'polystyle', 'labelstyle', 'balloonstyle',
            'liststyle', 'itemicon', 'pair', 'hotspot', 'link'
        ]
    ),
    
    FileType.KMZ: FileTypeConfig(
        file_type=FileType.KMZ,
        extensions=['.kmz'],
        signatures=[
            b'PK\x03\x04',  # Standard ZIP
            b'PK\x05\x06',  # Empty ZIP
            b'PK\x07\x08'  # Spanned ZIP
        ],
        mime_types=[
            'application/zip',
            'application/x-zip-compressed',
            'application/vnd.google-earth.kmz',
            'application/vnd.google-earth.kmz+xml'
        ],
        max_size=200 * 1024 * 1024,  # 200MB
        xml_root_elements=['kml'],
        allowed_elements=[
            'style', 'iconstyle', 'linestyle', 'polystyle', 'labelstyle', 'balloonstyle',
            'liststyle', 'itemicon', 'pair', 'hotspot', 'link'
        ],
        is_archive=True,
        archive_extensions=['.kml']
    ),
    
    FileType.GPX: FileTypeConfig(
        file_type=FileType.GPX,
        extensions=['.gpx'],
        signatures=[
            b'<?xml',
            b'<gpx',
            b'<GPX'
        ],
        mime_types=[
            'text/xml',
            'application/xml',
            'text/plain',
            'application/gpx+xml',
            'application/gpx'
        ],
        max_size=5 * 1024 * 1024,  # 5MB
        xml_root_elements=['gpx'],
        allowed_elements=['trk', 'rte', 'wpt', 'name', 'desc', 'time', 'ele']
    )
}


def get_file_type_config(file_type: FileType) -> FileTypeConfig:
    """Get configuration for a specific file type."""
    return FILE_TYPE_CONFIGS[file_type]


def get_all_supported_extensions() -> List[str]:
    """Get all supported file extensions."""
    extensions = []
    for config in FILE_TYPE_CONFIGS.values():
        extensions.extend(config.extensions)
    return extensions


def get_all_supported_mime_types() -> Dict[str, List[str]]:
    """Get all supported MIME types organized by file type."""
    return {config.file_type.value: config.mime_types for config in FILE_TYPE_CONFIGS.values()}


def get_file_type_by_extension(extension: str) -> FileType:
    """Get file type by extension."""
    extension = extension.lower()
    if not extension.startswith('.'):
        extension = f'.{extension}'
    
    for file_type, config in FILE_TYPE_CONFIGS.items():
        if extension in config.extensions:
            return file_type
    
    raise ValueError(f"Unsupported file extension: {extension}")


def get_file_type_by_signature(file_data: bytes) -> FileType:
    """Get file type by file signature (magic numbers)."""
    for file_type, config in FILE_TYPE_CONFIGS.items():
        if any(file_data.startswith(sig) for sig in config.signatures):
            return file_type
    
    raise ValueError("Unsupported file signature")


def get_file_type_by_mime_type(mime_type: str) -> FileType:
    """Get file type by MIME type."""
    for file_type, config in FILE_TYPE_CONFIGS.items():
        if mime_type in config.mime_types:
            return file_type
    
    raise ValueError(f"Unsupported MIME type: {mime_type}")


def is_archive_type(file_type: FileType) -> bool:
    """Check if file type is an archive format."""
    return FILE_TYPE_CONFIGS[file_type].is_archive


def get_max_file_size(file_type: FileType) -> int:
    """Get maximum file size for a file type."""
    return FILE_TYPE_CONFIGS[file_type].max_size


def get_xml_root_elements(file_type: FileType) -> List[str]:
    """Get expected XML root elements for a file type."""
    return FILE_TYPE_CONFIGS[file_type].xml_root_elements


def get_allowed_elements(file_type: FileType) -> List[str]:
    """Get allowed XML elements for a file type."""
    return FILE_TYPE_CONFIGS[file_type].allowed_elements or []


def validate_file_size(file_size: int, file_type: FileType) -> bool:
    """Validate file size against type-specific limits."""
    return file_size <= get_max_file_size(file_type)


def validate_mime_type(mime_type: str, file_type: FileType) -> bool:
    """Validate MIME type against type-specific allowed types."""
    return mime_type in FILE_TYPE_CONFIGS[file_type].mime_types


def validate_file_signature(file_data: bytes, file_type: FileType) -> bool:
    """Validate file signature against type-specific signatures."""
    return any(file_data.startswith(sig) for sig in FILE_TYPE_CONFIGS[file_type].signatures)
