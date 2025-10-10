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

from geo_lib.processing.file_types import (
    FileType, get_file_type_by_extension, validate_file_size, validate_mime_type, 
    validate_file_signature, get_allowed_elements, get_max_file_size
)

logger = logging.getLogger(__name__)


class FileValidationError(Exception):
    """Custom exception for file validation errors."""
    pass


class SecurityError(Exception):
    """Custom exception for security-related errors."""
    pass


class SecureFileValidator:
    """
    Comprehensive file validator for KML/KMZ/GPX files with security measures.
    """

    # Dangerous XML elements/attributes to remove
    DANGEROUS_ELEMENTS = [
        'script', 'iframe', 'object', 'embed', 'applet', 'form', 'input',
        'button', 'meta'
    ]
    
    # HTML-specific dangerous elements (not KML elements)
    HTML_DANGEROUS_ELEMENTS = [
        'link'  # HTML link elements, but KML link elements are allowed
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
            return False, str(e)
        except Exception as e:
            logger.error(f"File validation error for {uploaded_file.name}: {traceback.format_exc()}")
            return False, "Invalid file format"

    def _validate_basic_properties(self, uploaded_file: UploadedFile):
        """Validate basic file properties."""
        if not uploaded_file.name:
            raise FileValidationError("Invalid filename. Please rename the file and try again.")

        if uploaded_file.size == 0:
            raise FileValidationError("The file is empty. Please select a valid file.")

        # Check file extension
        try:
            import os
            _, ext = os.path.splitext(uploaded_file.name)
            get_file_type_by_extension(ext)
        except ValueError:
            raise FileValidationError("Only KML, KMZ, and GPX files are supported")

    def _validate_file_signature(self, uploaded_file: UploadedFile):
        """Validate file signature (magic numbers)."""
        # Read first 1024 bytes for signature check
        file_data = uploaded_file.read(1024)
        uploaded_file.seek(0)  # Reset file pointer

        try:
            import os
            _, ext = os.path.splitext(uploaded_file.name)
            file_type = get_file_type_by_extension(ext)
            if not validate_file_signature(file_data, file_type):
                if file_type.value.upper() == 'KMZ':
                    raise SecurityError("This file does not appear to be a valid KMZ file. Please ensure it's a properly formatted KMZ archive.")
                elif file_type.value.upper() == 'KML':
                    raise SecurityError("This file does not appear to be a valid KML file. Please ensure it's a properly formatted KML document.")
                elif file_type.value.upper() == 'GPX':
                    raise SecurityError("This file does not appear to be a valid GPX file. Please ensure it's a properly formatted GPX document.")
                else:
                    raise SecurityError("File format validation failed. Please ensure the file is a valid KML, KMZ, or GPX file.")
        except ValueError:
            raise SecurityError("File format is not recognized")

    def _validate_mime_type(self, uploaded_file: UploadedFile):
        """Validate MIME type using python-magic."""

        file_data = uploaded_file.read(1024)
        uploaded_file.seek(0)  # Reset file pointer

        mime_type = magic.from_buffer(file_data, mime=True)
        
        try:
            import os
            _, ext = os.path.splitext(uploaded_file.name)
            file_type = get_file_type_by_extension(ext)
            if not validate_mime_type(mime_type, file_type):
                if file_type.value.upper() == 'KMZ':
                    raise SecurityError("This file's content type doesn't match a KMZ file. Please ensure it's a valid KMZ archive.")
                elif file_type.value.upper() == 'KML':
                    raise SecurityError("This file's content type doesn't match a KML file. Please ensure it's a valid KML document.")
                elif file_type.value.upper() == 'GPX':
                    raise SecurityError("This file's content type doesn't match a GPX file. Please ensure it's a valid GPX document.")
                else:
                    raise SecurityError("File content type validation failed. Please ensure the file is a valid KML, KMZ, or GPX file.")
        except ValueError:
            raise SecurityError("File format is not recognized")

    def _validate_file_size(self, uploaded_file: UploadedFile):
        """Validate file size limits."""
        try:
            import os
            _, ext = os.path.splitext(uploaded_file.name)
            file_type = get_file_type_by_extension(ext)
            if not validate_file_size(uploaded_file.size, file_type):
                max_size_mb = get_max_file_size(file_type) / (1024 * 1024)
                file_size_mb = uploaded_file.size / (1024 * 1024)
                raise FileValidationError(f"File too large: {file_size_mb:.1f}MB exceeds {max_size_mb:.0f}MB limit")
        except ValueError:
            raise FileValidationError("Invalid file type")

    def _validate_content(self, uploaded_file: UploadedFile):
        """Validate file content structure."""
        try:
            import os
            _, ext = os.path.splitext(uploaded_file.name)
            file_type = get_file_type_by_extension(ext)
            
            if file_type == FileType.KMZ:
                self._validate_kmz_content(uploaded_file)
            elif file_type == FileType.GPX:
                self._validate_gpx_content(uploaded_file)
            else:
                self._validate_kml_content(uploaded_file)
        except ValueError:
            raise FileValidationError("Invalid file type")

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
                        raise SecurityError("The KMZ file contains invalid file paths. Please recreate the KMZ file with proper file structure.")

                    # Check for directory traversal
                    if ".." in file_info.filename or file_info.filename.startswith('/'):
                        raise SecurityError("The KMZ file contains invalid file paths. Please recreate the KMZ file with proper file structure.")

                    # Check for suspicious file extensions
                    if any(file_info.filename.lower().endswith(ext) for ext in ['.exe', '.bat', '.cmd', '.scr', '.pif']):
                        raise SecurityError("The KMZ file contains unsupported file types. Please ensure the KMZ only contains KML files and supported image formats.")

                # Check for KML files in archive
                kml_files = [name for name in kmz.namelist() if name.lower().endswith('.kml')]
                if not kml_files:
                    raise FileValidationError("The KMZ file must contain at least one KML file. Please ensure your KMZ archive includes a KML document.")

                # Validate the main KML file
                main_kml_file = 'doc.kml' if 'doc.kml' in kml_files else kml_files[0]
                kml_content = kmz.read(main_kml_file).decode('utf-8')
                
                # Check embedded KML size against KML file type limit (not KMZ limit)
                from geo_lib.processing.file_types import FILE_TYPE_CONFIGS, FileType, get_max_file_size
                kml_size_limit = get_max_file_size(FileType.KML)
                kml_content_size = len(kml_content.encode('utf-8'))
                
                if kml_content_size > kml_size_limit:
                    kml_size_mb = kml_content_size / (1024 * 1024)
                    kml_limit_mb = kml_size_limit / (1024 * 1024)
                    raise FileValidationError(
                        f"Embedded KML file too large: {kml_size_mb:.1f}MB exceeds {kml_limit_mb:.0f}MB limit for KML content"
                    )
                
                self._validate_kml_structure(kml_content)

        except zipfile.BadZipFile:
            raise SecurityError("The KMZ file appears to be corrupted or invalid. Please try re-saving the file or use a different KMZ file.")
        except UnicodeDecodeError:
            raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            # Log internal error for debugging
            logger.warning(f"KMZ validation error: {type(e).__name__}")
            raise SecurityError("KMZ file validation failed")

    def _validate_kml_content(self, uploaded_file: UploadedFile):
        """Validate KML content structure."""
        try:
            file_data = uploaded_file.read()
            uploaded_file.seek(0)  # Reset file pointer

            kml_content = file_data.decode('utf-8')
            self._validate_kml_structure(kml_content)

        except UnicodeDecodeError:
            raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            # Log internal error for debugging
            logger.warning(f"KML validation error: {type(e).__name__}")
            raise SecurityError("KML file validation failed")

    def _validate_gpx_content(self, uploaded_file: UploadedFile):
        """Validate GPX content structure."""
        try:
            file_data = uploaded_file.read()
            uploaded_file.seek(0)  # Reset file pointer

            gpx_content = file_data.decode('utf-8')
            self._validate_gpx_structure(gpx_content)

        except UnicodeDecodeError:
            raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            # Log internal error for debugging
            logger.warning(f"GPX validation error: {type(e).__name__}")
            raise SecurityError("GPX file validation failed")

    def _validate_kml_structure(self, kml_content: str):
        """Validate KML XML structure and check for dangerous content."""
        try:
            # Parse XML with secure settings
            root = self._secure_xml_parse(kml_content)

            # Check for dangerous elements
            self._check_dangerous_elements(root, FileType.KML)

            # Check for dangerous attributes
            self._check_dangerous_attributes(root)

            # Validate KML namespace
            if not self._is_valid_kml(root):
                raise FileValidationError("The KML file doesn't contain valid geographic features. Please ensure it includes placemarks, polygons, or other geographic elements.")

        except ET.ParseError:
            raise FileValidationError("The KML file contains invalid XML structure. Please check the file format and try again.")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            # Log internal error for debugging
            logger.warning(f"KML structure validation error: {type(e).__name__}")
            raise SecurityError("KML file structure validation failed")

    def _validate_gpx_structure(self, gpx_content: str):
        """Validate GPX XML structure and check for dangerous content."""
        try:
            # Parse XML with secure settings
            root = self._secure_xml_parse(gpx_content)

            # Check for dangerous elements
            self._check_dangerous_elements(root, FileType.GPX)

            # Check for dangerous attributes
            self._check_dangerous_attributes(root)

            # Validate GPX namespace
            if not self._is_valid_gpx(root):
                raise FileValidationError("The GPX file doesn't contain valid tracks, routes, or waypoints. Please ensure it includes GPS data.")

        except ET.ParseError:
            raise FileValidationError("The GPX file contains invalid XML structure. Please check the file format and try again.")
        except Exception as e:
            if isinstance(e, (SecurityError, FileValidationError)):
                raise
            # Log internal error for debugging
            logger.warning(f"GPX structure validation error: {type(e).__name__}")
            raise SecurityError("GPX file structure validation failed")

    def _secure_xml_parse(self, xml_content: str) -> ET.Element:
        """Parse XML with security measures against XXE attacks."""
        # Create a secure parser that disables external entities
        parser = ET.XMLParser()
        
        # Disable entity processing to prevent XXE attacks
        # Note: In newer Python versions, parser.entity is readonly, so we use a different approach
        try:
            # Try to disable entity processing (works in older Python versions)
            parser.entity = {}
        except (AttributeError, TypeError):
            # In newer versions, we rely on the default secure behavior
            pass

        # Parse the XML
        try:
            root = ET.fromstring(xml_content, parser=parser)
            return root
        except ET.ParseError as e:
            # Log internal error for debugging
            logger.warning(f"XML parse error: {str(e)}")
            raise FileValidationError("The file contains invalid XML structure. Please check the file format and try again.")

    def _check_dangerous_elements(self, root: ET.Element, file_type: FileType = None):
        """Check for dangerous XML elements."""
        allowed_elements = []
        if file_type:
            allowed_elements = get_allowed_elements(file_type)
            
        for elem in root.iter():
            # Extract the local name from namespaced tags (e.g., {namespace}tag -> tag)
            tag_name = elem.tag.split('}')[-1].lower() if '}' in elem.tag else elem.tag.lower()
            
            # Allow file-type-specific elements
            if tag_name in allowed_elements:
                continue
                
            # Check for dangerous elements
            if tag_name in self.DANGEROUS_ELEMENTS:
                # Log internal details for debugging, but keep user message generic
                logger.warning(f"Dangerous element detected: {tag_name}")
                raise SecurityError("The file contains content that cannot be processed safely. Please remove any scripts, forms, or other potentially unsafe elements and try again.")

    def _check_dangerous_attributes(self, root: ET.Element):
        """Check for dangerous XML attributes."""
        for elem in root.iter():
            for attr_name in elem.attrib:
                if any(dangerous in attr_name.lower() for dangerous in self.DANGEROUS_ATTRIBUTES):
                    # Log internal details for debugging, but keep user message generic
                    logger.warning(f"Dangerous attribute detected: {attr_name}")
                    raise SecurityError("The file contains attributes that cannot be processed safely. Please remove any event handlers or other potentially unsafe attributes and try again.")

    def _is_valid_kml(self, root: ET.Element) -> bool:
        """Check if the XML is a valid KML document."""
        # Check for KML namespace
        if 'kml' not in root.tag.lower():
            return False

        # Check for required KML elements (handle namespaced tags)
        required_elements = ['document', 'folder', 'placemark', 'groundoverlay', 'screenoverlay']
        has_required = any(
            any(req in elem.tag.lower() for req in required_elements) 
            for elem in root.iter()
        )

        return has_required

    def _is_valid_gpx(self, root: ET.Element) -> bool:
        """Check if the XML is a valid GPX document."""
        # Check for GPX namespace
        if 'gpx' not in root.tag.lower():
            return False

        # Check for required GPX elements (handle namespaced tags)
        required_elements = ['trk', 'rte', 'wpt']
        has_required = any(
            any(req in elem.tag.lower() for req in required_elements) 
            for elem in root.iter()
        )

        return has_required


def validate_kml_content(kml_content: str) -> bool:
    """
    Validate KML content by checking for dangerous elements and attributes.
    Does NOT modify the content - only validates and rejects if dangerous.
    
    Args:
        kml_content: Raw KML content string
        
    Returns:
        True if content is safe, False if dangerous elements found
        
    Raises:
        SecurityError: If dangerous content is found
    """
    try:
        # Parse with secure settings
        validator = SecureFileValidator()
        root = validator._secure_xml_parse(kml_content)

        # Check for dangerous elements
        for elem in root.iter():
            # Only check elements in the default namespace (no namespace prefix)
            # Namespaced elements like {http://www.w3.org/2005/atom}link are generally safe
            if '}' not in elem.tag:
                local_name = elem.tag.lower()
                
                # Check for dangerous elements only in default namespace
                if local_name in [dangerous.lower() for dangerous in validator.DANGEROUS_ELEMENTS]:
                    raise SecurityError(f"Dangerous element found: {local_name}")
                
                # Check for HTML-specific dangerous elements in default namespace
                if local_name in [dangerous.lower() for dangerous in validator.HTML_DANGEROUS_ELEMENTS]:
                    raise SecurityError(f"HTML dangerous element found: {local_name}")

        # Check for dangerous attributes
        for elem in root.iter():
            for attr_name in elem.attrib:
                # Extract local name from namespaced attributes
                if '}' in attr_name:
                    local_attr_name = attr_name.split('}')[-1].lower()
                else:
                    local_attr_name = attr_name.lower()
                
                # Only check for exact matches of dangerous attributes
                if local_attr_name in [dangerous.lower() for dangerous in validator.DANGEROUS_ATTRIBUTES]:
                    raise SecurityError(f"Dangerous attribute found: {local_attr_name}")

        return True

    except SecurityError:
        # Re-raise security errors
        raise
    except Exception as e:
        logger.error(f"KML validation failed: {traceback.format_exc()}")
        raise SecurityError(f"Invalid KML content: {str(e)}")


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

            # Validate the content (don't modify it)
            validate_kml_content(kml_content)

            return kml_content

    except zipfile.BadZipFile:
        raise SecurityError("Invalid ZIP file structure")
    except Exception as e:
        if isinstance(e, (SecurityError, FileValidationError)):
            raise
        raise SecurityError(f"KMZ to KML conversion failed: {str(e)}")
