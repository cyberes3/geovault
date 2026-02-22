"""
Centralized file type configuration and metadata.
This module provides a unified registry of supported file types with their properties.
"""

import os
from dataclasses import dataclass
from enum import Enum
from typing import List, Dict, Union

from website.settings_utils import get_required_setting


class FileType(Enum):
    """Supported file types for processing."""
    KML = "kml"
    KMZ = "kmz"
    GPX = "gpx"
    GEOJSON = "geojson"


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
            'application/vnd.google-earth.kml',
            # Some KML files with rich HTML content in CDATA (like simpledata.kml)
            # are detected by libmagic/python-magic as text/html. We still treat
            # these as valid KML as long as the subsequent XML/content validation
            # passes, so allow text/html here.
            'text/html'
        ],
        max_size=get_required_setting('FILE_UPLOAD_MAX_MEMORY_SIZE'),
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
        max_size=get_required_setting('FILE_UPLOAD_MAX_MEMORY_SIZE'),
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
        max_size=get_required_setting('FILE_UPLOAD_MAX_MEMORY_SIZE'),
        xml_root_elements=['gpx'],
        allowed_elements=['trk', 'rte', 'wpt', 'name', 'desc', 'time', 'ele']
    ),
    
    FileType.GEOJSON: FileTypeConfig(
        file_type=FileType.GEOJSON,
        extensions=['.geojson', '.json'],  # Note: .geojson uploads are blocked, but processing is allowed
        signatures=[
            b'{',  # JSON object start
            b'[',  # JSON array start (for FeatureCollection)
        ],
        mime_types=[
            'application/json',
            'application/geo+json',
            'application/vnd.geo+json',
            'text/json'
        ],
        max_size=get_required_setting('FILE_UPLOAD_MAX_MEMORY_SIZE'),
        xml_root_elements=[],  # Not XML
        allowed_elements=[]  # Not XML
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
    # Check for more specific signatures first (root elements) before generic XML
    # This prevents GPX files from being misidentified as KML
    file_data_lower = file_data.lower()
    
    # Strip whitespace for JSON detection
    file_data_stripped = file_data.lstrip()

    # Check for root elements first (most specific)
    if b'<gpx' in file_data_lower or b'<GPX' in file_data:
        return FileType.GPX
    if b'<kml' in file_data_lower or b'<KML' in file_data:
        return FileType.KML
    
    # Check for GeoJSON (JSON object or array)
    if file_data_stripped.startswith(b'{') or file_data_stripped.startswith(b'['):
        # Verify it's valid JSON by checking for GeoJSON keywords
        try:
            content = file_data.decode('utf-8').strip()
            if '"type"' in content and ('"Feature"' in content or '"FeatureCollection"' in content):
                return FileType.GEOJSON
        except (UnicodeDecodeError, AttributeError):
            pass  # Not valid UTF-8, continue to other checks

    # Then check other signatures
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


def strip_bom_and_whitespace(file_data: bytes) -> bytes:
    """
    Strip BOM (Byte Order Mark) and leading whitespace from file data.
    Handles UTF-8, UTF-16 LE, and UTF-16 BE BOMs.
    
    Args:
        file_data: Raw file bytes
        
    Returns:
        File data with BOM and leading whitespace removed
    """
    # Remove BOM markers
    if file_data.startswith(b'\xef\xbb\xbf'):  # UTF-8 BOM
        file_data = file_data[3:]
    elif file_data.startswith(b'\xff\xfe'):  # UTF-16 LE BOM
        file_data = file_data[2:]
    elif file_data.startswith(b'\xfe\xff'):  # UTF-16 BE BOM
        file_data = file_data[2:]

    # Remove leading whitespace (spaces, tabs, newlines, carriage returns)
    # But only if it's text-based (XML) files, not binary (ZIP/KMZ)
    # Check if it looks like text (starts with XML declaration or tag)
    if file_data.startswith(b'<?xml') or file_data.startswith(b'<'):
        # Strip leading whitespace for XML-based formats
        file_data = file_data.lstrip(b' \t\n\r')

    return file_data


def validate_file_signature(file_data: bytes, file_type: FileType) -> bool:
    """
    Validate file signature against type-specific signatures.
    Automatically strips BOM and leading whitespace for text-based formats.
    """
    # For text-based formats (KML, GPX), strip BOM and whitespace
    # For binary formats (KMZ/ZIP), check as-is
    if file_type == FileType.KML or file_type == FileType.GPX:
        file_data = strip_bom_and_whitespace(file_data)

    return any(file_data.startswith(sig) for sig in FILE_TYPE_CONFIGS[file_type].signatures)


def detect_file_type(file_data: Union[bytes, str], filename: str = "") -> FileType:
    """
    Detect the file type based on content and filename.
    
    Args:
        file_data: File content as bytes or string
        filename: Optional filename for extension-based detection
        
    Returns:
        FileType enum value
    """
    # First check filename extension
    if filename:
        try:
            _, ext = os.path.splitext(filename)
            return get_file_type_by_extension(ext)
        except ValueError:
            pass  # Continue to content-based detection

    # Check file content signatures
    if isinstance(file_data, bytes):
        try:
            return get_file_type_by_signature(file_data)
        except ValueError:
            # Check for XML-based formats
            try:
                content = file_data.decode('utf-8')
            except UnicodeDecodeError:
                return FileType.KMZ  # Assume KMZ if not decodable as UTF-8
    else:
        content = file_data

    # Check for KML/GPX XML signatures in content
    # Check for root elements first (more specific), then generic XML
    content_lower = content.lower().strip()
    if '<gpx' in content_lower:
        return FileType.GPX
    elif '<kml' in content_lower:
        return FileType.KML
    elif content_lower.startswith('<?xml'):
        # Generic XML - default to KML
        return FileType.KML
    
    # Check for GeoJSON
    content_stripped = content.strip()
    if (content_stripped.startswith('{') or content_stripped.startswith('[')) and \
       '"type"' in content_lower and ('"feature"' in content_lower or '"featurecollection"' in content_lower):
        return FileType.GEOJSON

    # Default to KML if we can't determine
    return FileType.KML
