import io
import os
import zipfile
from typing import Tuple, Union

from django.core.files.uploadedfile import UploadedFile

from geo_lib.processing.file_types import get_file_type_by_extension, validate_file_size, get_max_file_size, validate_file_signature
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.security.filetype_validators import _validate_content
from geo_lib.security.validation_helpers import _validate_basic_properties, _validate_file_signature, _validate_mime_type, _validate_file_size
from geo_lib.security.xml import parse_xml, DANGEROUS_ELEMENTS, HTML_DANGEROUS_ELEMENTS, DANGEROUS_ATTRIBUTES


def validate_file(uploaded_file: UploadedFile) -> Tuple[bool, str]:
    """
    Comprehensive file validation pipeline.

    Args:
        uploaded_file: Django UploadedFile object

    Returns:
        Tuple of (is_valid, error_message)
    """
    try:
        # Basic file checks
        _validate_basic_properties(uploaded_file)

        # File signature validation
        _validate_file_signature(uploaded_file)

        # MIME type validation
        _validate_mime_type(uploaded_file)

        # File size validation
        _validate_file_size(uploaded_file)

        # Content validation
        _validate_content(uploaded_file)

        return True, "File validation successful"

    except (SecurityError, FileValidationError) as e:
        return False, str(e)
    except:
        return False, "Invalid file format"


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
        root = parse_xml(kml_content)

        # Check for dangerous elements
        for elem in root.iter():
            # Extract local name from namespaced tags (e.g., {namespace}tag -> tag)
            # Check both namespaced and non-namespaced elements
            if '}' in elem.tag:
                local_name = elem.tag.split('}')[-1].lower()
            else:
                local_name = elem.tag.lower()

            # Check for dangerous elements (in any namespace)
            if local_name in [dangerous.lower() for dangerous in DANGEROUS_ELEMENTS]:
                raise SecurityError(f"Dangerous element found: {local_name}")

            # Check for HTML-specific dangerous elements (only in default namespace to avoid false positives)
            if '}' not in elem.tag and local_name in [dangerous.lower() for dangerous in HTML_DANGEROUS_ELEMENTS]:
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
                if local_attr_name in [dangerous.lower() for dangerous in DANGEROUS_ATTRIBUTES]:
                    raise SecurityError(f"Dangerous attribute found: {local_attr_name}")

        return True

    except SecurityError:
        # Re-raise security errors
        raise
    except:
        raise SecurityError("Invalid KML content")


def basic_file_security_check(uploaded_file: UploadedFile) -> Tuple[bool, str]:
    """
    Perform basic security checks for quick rejection before async processing.
    Only checks that can be done quickly without reading the entire file.

    Checks performed:
    - File size limit (before reading full file)
    - File extension validation
    - Empty file check
    - Basic file signature check (first few bytes)

    Args:
        uploaded_file: Django UploadedFile object

    Returns:
        Tuple of (is_valid, error_message)
    """
    try:
        # Check for empty file
        if uploaded_file.size == 0:
            return False, "The file is empty. Please select a valid file."

        # Check filename
        if not uploaded_file.name:
            return False, "Invalid filename. Please rename the file and try again."

        # Check file extension
        try:
            _, ext = os.path.splitext(uploaded_file.name)
            file_type = get_file_type_by_extension(ext)
        except ValueError:
            return False, "Only KML, KMZ, and GPX files are supported"

        # Check file size limit (quick check before reading full file)
        try:
            if not validate_file_size(uploaded_file.size, file_type):
                max_size_mb = get_max_file_size(file_type) / (1024 * 1024)
                file_size_mb = uploaded_file.size / (1024 * 1024)
                return False, f"File too large: {file_size_mb:.1f}MB exceeds {max_size_mb:.0f}MB limit"
        except ValueError:
            return False, "Invalid file type"

        # Basic file signature check (read only first 1024 bytes)
        file_data = uploaded_file.read(1024)
        uploaded_file.seek(0)  # Reset file pointer

        try:
            if not validate_file_signature(file_data, file_type):
                if file_type.value.upper() == 'KMZ':
                    return False, "This file does not appear to be a valid KMZ file. Please ensure it's a properly formatted KMZ archive."
                elif file_type.value.upper() == 'KML':
                    return False, "This file does not appear to be a valid KML file. Please ensure it's a properly formatted KML document."
                elif file_type.value.upper() == 'GPX':
                    return False, "This file does not appear to be a valid GPX file. Please ensure it's a properly formatted GPX document."
                else:
                    return False, "File format validation failed. Please ensure the file is a valid KML, KMZ, or GPX file."
        except ValueError:
            return False, "File format is not recognized"

        return True, "Basic security check passed"

    except:
        return False, "File validation error"


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
    except (SecurityError, FileValidationError):
        raise
    except:
        raise SecurityError(f"KMZ to KML conversion failed")
