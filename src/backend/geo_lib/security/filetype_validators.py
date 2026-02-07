import io
import os
import zipfile
from xml.etree import ElementTree as ET

from django.core.files.uploadedfile import UploadedFile

from geo_lib.processing.file_types import get_max_file_size, FileType, get_file_type_by_extension
from geo_lib.security.exceptions import SecurityError, FileValidationError
from geo_lib.security.filetype_checkers import _is_valid_kml, _is_valid_gpx
from geo_lib.security.xml import parse_xml, _check_dangerous_elements, _check_dangerous_attributes


def _validate_kmz_content(uploaded_file: UploadedFile):
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
            kml_size_limit = get_max_file_size(FileType.KML)
            kml_content_size = len(kml_content.encode('utf-8'))

            if kml_content_size > kml_size_limit:
                kml_size_mb = kml_content_size / (1024 * 1024)
                kml_limit_mb = kml_size_limit / (1024 * 1024)
                raise FileValidationError(
                    f"Embedded KML file too large: {kml_size_mb:.1f}MB exceeds {kml_limit_mb:.0f}MB limit for KML content"
                )

            _validate_kml_structure(kml_content)

    except zipfile.BadZipFile:
        raise SecurityError("The KMZ file appears to be corrupted or invalid. Please try re-saving the file or use a different KMZ file.")
    except UnicodeDecodeError:
        raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
    except (SecurityError, FileValidationError):
        raise
    except Exception as e:
        raise SecurityError("KMZ file validation failed")


def _validate_kml_content(uploaded_file: UploadedFile):
    """Validate KML content structure."""
    try:
        file_data = uploaded_file.read()
        uploaded_file.seek(0)  # Reset file pointer

        kml_content = file_data.decode('utf-8')
        _validate_kml_structure(kml_content)

    except UnicodeDecodeError:
        raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
    except (SecurityError, FileValidationError):
        raise
    except:
        raise SecurityError("KML file validation failed")


def _validate_gpx_content(uploaded_file: UploadedFile):
    """Validate GPX content structure."""
    try:
        file_data = uploaded_file.read()
        uploaded_file.seek(0)  # Reset file pointer

        gpx_content = file_data.decode('utf-8')
        _validate_gpx_structure(gpx_content)

    except UnicodeDecodeError:
        raise SecurityError("The file contains invalid text encoding. Please save the file with UTF-8 encoding and try again.")
    except (SecurityError, FileValidationError):
        raise
    except:
        raise SecurityError("GPX file validation failed")


def _validate_kml_structure(kml_content: str):
    """Validate KML XML structure and check for dangerous content."""
    try:
        # Parse XML with secure settings
        root = parse_xml(kml_content)

        # Check for dangerous elements
        _check_dangerous_elements(root, FileType.KML)

        # Check for dangerous attributes
        _check_dangerous_attributes(root)

        # Validate KML namespace
        if not _is_valid_kml(root):
            raise FileValidationError("The KML file doesn't contain valid geographic features. Please ensure it includes placemarks, polygons, or other geographic elements.")

    except ET.ParseError:
        raise FileValidationError("The KML file contains invalid XML structure. Please check the file format and try again.")
    except (SecurityError, FileValidationError):
        raise
    except:
        raise SecurityError("KML file structure validation failed")


def _validate_gpx_structure(gpx_content: str):
    """Validate GPX XML structure and check for dangerous content."""
    try:
        # Parse XML with secure settings
        root = parse_xml(gpx_content)

        # Check for dangerous elements
        _check_dangerous_elements(root, FileType.GPX)

        # Check for dangerous attributes
        _check_dangerous_attributes(root)

        # Validate GPX namespace
        if not _is_valid_gpx(root):
            raise FileValidationError("The GPX file doesn't contain valid tracks, routes, or waypoints. Please ensure it includes GPS data.")

    except ET.ParseError:
        raise FileValidationError("The GPX file contains invalid XML structure. Please check the file format and try again.")
    except (SecurityError, FileValidationError):
        raise
    except:
        raise SecurityError("GPX file structure validation failed")


def _validate_content(uploaded_file: UploadedFile):
    """Validate file content structure."""
    try:
        _, ext = os.path.splitext(uploaded_file.name)
        file_type = get_file_type_by_extension(ext)

        if file_type == FileType.KMZ:
            _validate_kmz_content(uploaded_file)
        elif file_type == FileType.GPX:
            _validate_gpx_content(uploaded_file)
        else:
            _validate_kml_content(uploaded_file)
    except ValueError:
        raise FileValidationError("Invalid file type")
