import io
import os
import zipfile
from typing import Union

from geo_lib.security.SecureFileValidator import SecureFileValidator
from geo_lib.security.exceptions import SecurityError, FileValidationError


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
        root = validator.secure_xml_parse(kml_content)

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
    except:
        # _logger.error(f"KML validation failed: {traceback.format_exc()}")
        raise SecurityError(f"Invalid KML content")


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
        raise SecurityError(f"KMZ to KML conversion failed")
