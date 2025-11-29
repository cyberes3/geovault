"""
Tests for file type detection.
"""
import pytest
from geo_lib.processing.file_types import (
    FileType,
    detect_file_type,
    get_file_type_by_extension,
    get_file_type_by_signature,
    validate_file_signature,
)


class TestFileTypes:
    """Test file type detection."""

    def test_detect_kml_by_extension(self):
        """Test detecting KML by file extension."""
        file_type = detect_file_type(b'', 'test.kml')
        assert file_type == FileType.KML

    def test_detect_kmz_by_extension(self):
        """Test detecting KMZ by file extension."""
        file_type = detect_file_type(b'', 'test.kmz')
        assert file_type == FileType.KMZ

    def test_detect_gpx_by_extension(self):
        """Test detecting GPX by file extension."""
        file_type = detect_file_type(b'', 'test.gpx')
        assert file_type == FileType.GPX

    def test_detect_kml_by_signature(self):
        """Test detecting KML by content signature."""
        kml_content = b'<?xml version="1.0"?><kml></kml>'
        file_type = detect_file_type(kml_content, '')
        assert file_type == FileType.KML

    def test_detect_kml_by_kml_tag(self):
        """Test detecting KML by <kml> tag."""
        kml_content = b'<kml></kml>'
        file_type = detect_file_type(kml_content, '')
        assert file_type == FileType.KML

    def test_detect_kmz_by_signature(self):
        """Test detecting KMZ by ZIP signature."""
        # PK\x03\x04 is the ZIP file signature
        kmz_content = b'PK\x03\x04' + b'fake zip content'
        file_type = detect_file_type(kmz_content, '')
        assert file_type == FileType.KMZ

    def test_detect_gpx_by_signature(self):
        """Test detecting GPX by content signature."""
        gpx_content = b'<?xml version="1.0"?><gpx></gpx>'
        file_type = detect_file_type(gpx_content, '')
        assert file_type == FileType.GPX

    def test_get_file_type_by_extension_kml(self):
        """Test getting file type by extension for KML."""
        file_type = get_file_type_by_extension('.kml')
        assert file_type == FileType.KML

    def test_get_file_type_by_extension_kmz(self):
        """Test getting file type by extension for KMZ."""
        file_type = get_file_type_by_extension('.kmz')
        assert file_type == FileType.KMZ

    def test_get_file_type_by_extension_gpx(self):
        """Test getting file type by extension for GPX."""
        file_type = get_file_type_by_extension('.kmz')
        assert file_type == FileType.KMZ

    def test_get_file_type_by_extension_invalid(self):
        """Test getting file type for invalid extension."""
        with pytest.raises(ValueError):
            get_file_type_by_extension('.txt')

    def test_get_file_type_by_signature_kml(self):
        """Test getting file type by signature for KML."""
        file_type = get_file_type_by_signature(b'<?xml')
        assert file_type == FileType.KML

    def test_get_file_type_by_signature_kmz(self):
        """Test getting file type by signature for KMZ."""
        file_type = get_file_type_by_signature(b'PK\x03\x04')
        assert file_type == FileType.KMZ

    def test_get_file_type_by_signature_gpx(self):
        """Test getting file type by signature for GPX."""
        file_type = get_file_type_by_signature(b'<gpx')
        assert file_type == FileType.GPX

    def test_get_file_type_by_signature_invalid(self):
        """Test getting file type for invalid signature."""
        with pytest.raises(ValueError):
            get_file_type_by_signature(b'invalid')

    def test_validate_file_signature_kml(self):
        """Test validating KML file signature."""
        assert validate_file_signature(b'<?xml', FileType.KML) is True
        assert validate_file_signature(b'<kml', FileType.KML) is True

    def test_validate_file_signature_kmz(self):
        """Test validating KMZ file signature."""
        assert validate_file_signature(b'PK\x03\x04', FileType.KMZ) is True
        assert validate_file_signature(b'PK\x05\x06', FileType.KMZ) is True

    def test_validate_file_signature_gpx(self):
        """Test validating GPX file signature."""
        assert validate_file_signature(b'<?xml', FileType.GPX) is True
        assert validate_file_signature(b'<gpx', FileType.GPX) is True

    def test_validate_file_signature_invalid(self):
        """Test validating invalid file signature."""
        assert validate_file_signature(b'invalid', FileType.KML) is False

    def test_detect_file_type_defaults_to_kml(self):
        """Test that unknown files default to KML."""
        file_type = detect_file_type(b'unknown content', 'unknown.xyz')
        assert file_type == FileType.KML

    def test_detect_file_type_extension_overrides_content(self):
        """Test that extension takes precedence over content."""
        # Content looks like KML but extension is GPX
        kml_content = b'<?xml version="1.0"?><kml></kml>'
        file_type = detect_file_type(kml_content, 'test.gpx')
        assert file_type == FileType.GPX

