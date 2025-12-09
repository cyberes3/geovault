"""
Secure file validation module for GeoVault.

This module provides comprehensive file validation for KML/KMZ uploads,
including signature validation, MIME type checking, secure ZIP processing,
and XML security measures.
"""

import os
from typing import Tuple

from django.core.files.uploadedfile import UploadedFile

from geo_lib.processing.file_types import (
    get_file_type_by_extension, get_max_file_size,
    validate_file_size, validate_file_signature
)


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
        # _logger.error(f"Basic security check error for {uploaded_file.name}: {traceback.format_exc()}")
        return False, "File validation error"
