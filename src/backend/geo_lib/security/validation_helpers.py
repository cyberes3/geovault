import os

import magic
from django.core.files.uploadedfile import UploadedFile

from geo_lib.processing.file_types import get_file_type_by_extension, validate_file_size, get_max_file_size, validate_mime_type, validate_file_signature
from geo_lib.security.exceptions import FileValidationError, SecurityError


def _validate_file_size(uploaded_file: UploadedFile):
    """Validate file size limits."""
    try:
        _, ext = os.path.splitext(uploaded_file.name)
        file_type = get_file_type_by_extension(ext)
        if not validate_file_size(uploaded_file.size, file_type):
            max_size_mb = get_max_file_size(file_type) / (1024 * 1024)
            file_size_mb = uploaded_file.size / (1024 * 1024)
            raise FileValidationError(f"File too large: {file_size_mb:.1f}MB exceeds {max_size_mb:.0f}MB limit")
    except ValueError:
        raise FileValidationError("Invalid file type")


def _validate_mime_type(uploaded_file: UploadedFile):
    """Validate MIME type using python-magic."""

    file_data = uploaded_file.read(1024)
    uploaded_file.seek(0)  # Reset file pointer

    mime_type = magic.from_buffer(file_data, mime=True)

    try:
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


def _validate_file_signature(uploaded_file: UploadedFile):
    """Validate file signature (magic numbers)."""
    # Read first 1024 bytes for signature check
    file_data = uploaded_file.read(1024)
    uploaded_file.seek(0)  # Reset file pointer

    try:
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


def _validate_basic_properties(uploaded_file: UploadedFile):
    """Validate basic file properties."""
    if not uploaded_file.name:
        raise FileValidationError("Invalid filename. Please rename the file and try again.")

    if uploaded_file.size == 0:
        raise FileValidationError("The file is empty. Please select a valid file.")

    # Check file extension
    try:
        _, ext = os.path.splitext(uploaded_file.name)
        get_file_type_by_extension(ext)
    except ValueError:
        raise FileValidationError("Only KML, KMZ, and GPX files are supported")
