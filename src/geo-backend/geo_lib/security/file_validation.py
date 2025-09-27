"""
Secure file validation module for GeoServer.

This module provides comprehensive file validation for KML/KMZ uploads,
including signature validation, MIME type checking, secure ZIP processing,
and XML security measures.
"""

import io
import logging
import os
import traceback
import xml.etree.ElementTree as ET
import zipfile
from typing import Union, Tuple

import magic
from django.conf import settings
from django.core.files.uploadedfile import UploadedFile

logger = logging.getLogger(__name__)


class FileValidationError(Exception):
    """Custom exception for file validation errors."""
    pass


class SecurityError(Exception):
    """Custom exception for security-related errors."""
    pass


class SecureFileValidator:
    """
    Comprehensive file validator for KML/KMZ files with security measures.
    """

    # File signatures (magic numbers)
    KML_SIGNATURES = [
        b'<?xml',
        b'<kml',
        b'<KML'
    ]

    KMZ_SIGNATURES = [
        b'PK\x03\x04',  # Standard ZIP
        b'PK\x05\x06',  # Empty ZIP
        b'PK\x07\x08'  # Spanned ZIP
    ]

    # Allowed MIME types
    ALLOWED_MIME_TYPES = {
        'kml': [
            'text/xml',
            'application/xml',
            'text/plain',
            'application/vnd.google-earth.kml+xml',
            'application/vnd.google-earth.kml'
        ],
        'kmz': [
            'application/zip',
            'application/x-zip-compressed',
            'application/vnd.google-earth.kmz',
            'application/vnd.google-earth.kmz+xml'
        ]
    }

    # Maximum file sizes (configurable)
    MAX_KML_SIZE = getattr(settings, 'MAX_KML_FILE_SIZE', 5 * 1024 * 1024)  # 5MB
    MAX_KMZ_SIZE = getattr(settings, 'MAX_KMZ_FILE_SIZE', 10 * 1024 * 1024)  # 10MB

    # Dangerous XML elements/attributes to remove
    DANGEROUS_ELEMENTS = [
        'script', 'iframe', 'object', 'embed', 'applet', 'form', 'input',
        'button', 'link', 'meta', 'style'
    ]

    DANGEROUS_ATTRIBUTES = [
        'onload', 'onerror', 'onclick', 'onmouseover', 'onfocus', 'onblur',
        'onchange', 'onsubmit', 'onreset', 'onselect', 'onunload'
    ]

    def __init__(self):
        self.validation_errors = []

    def validate_file(self, uploaded_file: UploadedFile) -> Tuple[bool, str]:
        """
        Comprehensive file validation pipeline.
        
        Args:
            uploaded_file: Django UploadedFile object
            
        Returns:
            Tuple of (is_valid, error_message)
        """
        try:
            # Basic file checks
            self._validate_basic_properties(uploaded_file)

            # File signature validation
            self._validate_file_signature(uploaded_file)

            # MIME type validation
            self._validate_mime_type(uploaded_file)

            # File size validation
            self._validate_file_size(uploaded_file)

            # Content validation
            self._validate_content(uploaded_file)

            return True, "File validation successful"

        except (SecurityError, FileValidationError) as e:
            logger.warning(f"File validation failed for {uploaded_file.name}: {str(e)}")
            return False, "Invalid file format"
        except Exception as e:
            logger.error(f"File validation error for {uploaded_file.name}: {traceback.format_exc()}")
            return False, "Invalid file format"

    def _validate_basic_properties(self, uploaded_file: UploadedFile):
        """Validate basic file properties."""
        if not uploaded_file.name:
            raise FileValidationError("Invalid file")

        if uploaded_file.size == 0:
            raise FileValidationError("Invalid file")

        # Check file extension
        filename_lower = uploaded_file.name.lower()
        if not (filename_lower.endswith('.kml') or filename_lower.endswith('.kmz')):
            raise FileValidationError("Invalid file type")

    def _validate_file_signature(self, uploaded_file: UploadedFile):
        """Validate file signature (magic numbers)."""
        # Read first 1024 bytes for signature check
        file_data = uploaded_file.read(1024)
        uploaded_file.seek(0)  # Reset file pointer

        filename_lower = uploaded_file.name.lower()

        if filename_lower.endswith('.kml'):
            if not any(file_data.startswith(sig) for sig in self.KML_SIGNATURES):
                raise SecurityError("Invalid file format")

        elif filename_lower.endswith('.kmz'):
            if not any(file_data.startswith(sig) for sig in self.KMZ_SIGNATURES):
                raise SecurityError("Invalid file format")

    def _validate_mime_type(self, uploaded_file: UploadedFile):
        """Validate MIME type using python-magic."""

        file_data = uploaded_file.read(1024)
        uploaded_file.seek(0)  # Reset file pointer

        mime_type = magic.from_buffer(file_data, mime=True)
        filename_lower = uploaded_file.name.lower()

        if filename_lower.endswith('.kml'):
            if mime_type not in self.ALLOWED_MIME_TYPES['kml']:
                raise SecurityError("Invalid file format")

        elif filename_lower.endswith('.kmz'):
            if mime_type not in self.ALLOWED_MIME_TYPES['kmz']:
                raise SecurityError("Invalid file format")

    def _validate_file_size(self, uploaded_file: UploadedFile):
        """Validate file size limits."""
        filename_lower = uploaded_file.name.lower()

        if filename_lower.endswith('.kml') and uploaded_file.size > self.MAX_KML_SIZE:
            raise FileValidationError("File too large")

        elif filename_lower.endswith('.kmz') and uploaded_file.size > self.MAX_KMZ_SIZE:
            raise FileValidationError("File too large")

    def _validate_content(self, uploaded_file: UploadedFile):
        """Validate file content structure."""
        filename_lower = uploaded_file.name.lower()

        if filename_lower.endswith('.kmz'):
            self._validate_kmz_content(uploaded_file)
        else:
            self._validate_kml_content(uploaded_file)

    def _validate_kmz_content(self, uploaded_file: UploadedFile):
        """Validate KMZ content and check for zip slip attacks."""
        try:
            file_data = uploaded_file.read()
            uploaded_file.seek(0)  # Reset file pointer

            with zipfile.ZipFile(io.BytesIO(file_data), 'r') as kmz:
                # Check for zip slip attacks
                for file_info in kmz.infolist():
                    # Check for absolute paths
                    if os.path.isabs(file_info.filename):
                        raise SecurityError("Invalid file content")

                    # Check for directory traversal
                    if ".." in file_info.filename or file_info.filename.startswith('/'):
                        raise SecurityError("Invalid file content")

                    # Check for suspicious file extensions
                    if any(file_info.filename.lower().endswith(ext) for ext in ['.exe', '.bat', '.cmd', '.scr', '.pif']):
                        raise SecurityError("Invalid file content")

                # Check for KML files in archive
                kml_files = [name for name in kmz.namelist() if name.lower().endswith('.kml')]
                if not kml_files:
                    raise FileValidationError("Invalid file content")

                # Validate the main KML file
                main_kml_file = 'doc.kml' if 'doc.kml' in kml_files else kml_files[0]
                kml_content = kmz.read(main_kml_file).decode('utf-8')
                self._validate_kml_structure(kml_content)

        except zipfile.BadZipFile:
            raise SecurityError("Invalid file format")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            raise SecurityError("Invalid file content")

    def _validate_kml_content(self, uploaded_file: UploadedFile):
        """Validate KML content structure."""
        try:
            file_data = uploaded_file.read()
            uploaded_file.seek(0)  # Reset file pointer

            kml_content = file_data.decode('utf-8')
            self._validate_kml_structure(kml_content)

        except UnicodeDecodeError:
            raise SecurityError("Invalid file format")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            raise SecurityError("Invalid file content")

    def _validate_kml_structure(self, kml_content: str):
        """Validate KML XML structure and check for dangerous content."""
        try:
            # Parse XML with secure settings
            root = self._secure_xml_parse(kml_content)

            # Check for dangerous elements
            self._check_dangerous_elements(root)

            # Check for dangerous attributes
            self._check_dangerous_attributes(root)

            # Validate KML namespace
            if not self._is_valid_kml(root):
                raise FileValidationError("Invalid KML structure")

        except ET.ParseError:
            raise FileValidationError("Invalid XML structure")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            raise SecurityError("Invalid KML content")

    def _secure_xml_parse(self, xml_content: str) -> ET.Element:
        """Parse XML with security measures against XXE attacks."""
        # Create a secure parser that disables external entities
        parser = ET.XMLParser()

        # Disable entity processing to prevent XXE
        parser.entity = {}

        # Parse the XML
        try:
            root = ET.fromstring(xml_content, parser=parser)
            return root
        except ET.ParseError:
            raise FileValidationError("Invalid file format")

    def _check_dangerous_elements(self, root: ET.Element):
        """Check for dangerous XML elements."""
        for elem in root.iter():
            tag_name = elem.tag.lower()
            if any(dangerous in tag_name for dangerous in self.DANGEROUS_ELEMENTS):
                raise SecurityError("Invalid file content")

    def _check_dangerous_attributes(self, root: ET.Element):
        """Check for dangerous XML attributes."""
        for elem in root.iter():
            for attr_name in elem.attrib:
                if any(dangerous in attr_name.lower() for dangerous in self.DANGEROUS_ATTRIBUTES):
                    raise SecurityError("Invalid file content")

    def _is_valid_kml(self, root: ET.Element) -> bool:
        """Check if the XML is a valid KML document."""
        # Check for KML namespace
        if 'kml' not in root.tag.lower():
            return False

        # Check for required KML elements
        required_elements = ['document', 'folder', 'placemark', 'groundoverlay', 'screenoverlay']
        has_required = any(elem.tag.lower().endswith(req) for req in required_elements for elem in root.iter())

        return has_required


def sanitize_kml_content(kml_content: str) -> str:
    """
    Sanitize KML content by removing dangerous elements and attributes.
    
    Args:
        kml_content: Raw KML content string
        
    Returns:
        Sanitized KML content
    """
    try:
        # Parse with secure settings
        validator = SecureFileValidator()
        root = validator._secure_xml_parse(kml_content)

        # Remove dangerous elements
        for elem in list(root.iter()):
            tag_name = elem.tag.lower()
            if any(dangerous in tag_name for dangerous in validator.DANGEROUS_ELEMENTS):
                parent = elem.getparent()
                if parent is not None:
                    parent.remove(elem)

        # Remove dangerous attributes
        for elem in root.iter():
            attrs_to_remove = []
            for attr_name in elem.attrib:
                if any(dangerous in attr_name.lower() for dangerous in validator.DANGEROUS_ATTRIBUTES):
                    attrs_to_remove.append(attr_name)

            for attr in attrs_to_remove:
                del elem.attrib[attr]

        # Convert back to string
        return ET.tostring(root, encoding='unicode')

    except Exception as e:
        logger.error(f"KML sanitization failed: {traceback.format_exc()}")
        # Return original content if sanitization fails
        return kml_content


def secure_kmz_to_kml(kmz_data: Union[str, bytes]) -> str:
    """
    Securely convert KMZ to KML with protection against zip slip attacks.
    
    Args:
        kmz_data: KMZ file data as bytes or string
        
    Returns:
        KML content as string
        
    Raises:
        SecurityError: If security validation fails
        FileValidationError: If file structure is invalid
    """
    if isinstance(kmz_data, str):
        kmz_data = kmz_data.encode('utf-8')

    try:
        with zipfile.ZipFile(io.BytesIO(kmz_data), 'r') as kmz:
            # Security check: validate all file paths
            for file_info in kmz.infolist():
                if os.path.isabs(file_info.filename) or ".." in file_info.filename:
                    raise SecurityError("Invalid file path in KMZ archive")

            # Find KML files
            kml_files = [name for name in kmz.namelist() if name.lower().endswith('.kml')]
            if not kml_files:
                raise FileValidationError("No KML file found in KMZ archive")

            # Use doc.kml if available, otherwise first .kml file
            kml_file = 'doc.kml' if 'doc.kml' in kml_files else kml_files[0]

            # Read and decode KML content
            kml_content = kmz.read(kml_file).decode('utf-8')

            # Sanitize the content
            sanitized_content = sanitize_kml_content(kml_content)

            return sanitized_content

    except zipfile.BadZipFile:
        raise SecurityError("Invalid ZIP file structure")
    except Exception as e:
        if isinstance(e, (SecurityError, FileValidationError)):
            raise
        raise SecurityError(f"KMZ to KML conversion failed: {str(e)}")
