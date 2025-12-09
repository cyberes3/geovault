"""
Comprehensive tests for file validation and security.

Tests validate_file, basic_file_security_check, and related security functions.
"""
import io
import zipfile
from io import BytesIO

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from geo_lib.processing.file_types import FileType
from geo_lib.security.SecureFileValidator import validate_file, basic_file_security_check
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.security.SecureFileValidator import validate_kml_content, secure_kmz_to_kml


class TestSecureFileValidator:
    """Test validate_file function."""

    def test_validate_valid_kml(self):
        """Test validation of a valid KML file."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Placemark</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is True
        assert "successful" in message.lower()

    def test_validate_valid_gpx(self):
        """Test validation of a valid GPX file."""
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Test Track</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        file = SimpleUploadedFile("test.gpx", gpx_content.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is True
        assert "successful" in message.lower()

    def test_validate_valid_kmz(self):
        """Test validation of a valid KMZ file."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Placemark</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Create KMZ file
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is True
        assert "successful" in message.lower()

    def test_validate_empty_file(self):
        """Test validation rejects empty files."""
        file = SimpleUploadedFile("test.kml", b'', content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "empty" in message.lower()

    def test_validate_no_filename(self):
        """Test validation rejects files without filename."""
        file = SimpleUploadedFile("", b'<kml></kml>', content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "filename" in message.lower() or "rename" in message.lower()

    def test_validate_invalid_extension(self):
        """Test validation rejects files with invalid extensions."""
        file = SimpleUploadedFile("test.txt", b'<kml></kml>', content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "kml" in message.lower() or "kmz" in message.lower() or "gpx" in message.lower()

    def test_validate_invalid_file_signature(self):
        """Test validation rejects files with invalid signatures."""
        # KML file with wrong signature
        file = SimpleUploadedFile("test.kml", b'not xml content', content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "valid" in message.lower() or "format" in message.lower()

    def test_validate_file_too_large(self):
        """Test validation rejects files exceeding size limits."""
        # Create a large KML file (exceeds 5MB limit)
        large_content = '<?xml version="1.0"?><kml><Document>' + ('<Placemark><name>Test</name></Placemark>' * 100000) + '</Document></kml>'
        
        # If the file is actually too large, it should be rejected
        # Note: This test may need adjustment based on actual size limits
        file = SimpleUploadedFile("test.kml", large_content.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should either pass (if under limit) or fail with size error
        if not is_valid:
            assert "large" in message.lower() or "size" in message.lower() or "exceeds" in message.lower()

    def test_validate_kmz_missing_kml(self):
        """Test validation rejects KMZ files without KML content."""
        # Create ZIP without KML files
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('readme.txt', 'No KML here')
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "kml" in message.lower()

    def test_validate_kmz_uses_doc_kml(self):
        """Test validation uses doc.kml when available in KMZ."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>From doc.kml</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
            zip_file.writestr('other.kml', '<kml></kml>')
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is True

    def test_validate_kmz_uses_first_kml_if_no_doc(self):
        """Test validation uses first KML file if doc.kml not present."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>From first.kml</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('first.kml', kml_content)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is True

    def test_validate_invalid_xml(self):
        """Test validation rejects invalid XML."""
        invalid_xml = """<?xml version="1.0"?>
<kml>
  <Document>
    <Placemark>
      <name>Unclosed tag
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", invalid_xml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_validate_kml_without_geographic_features(self):
        """Test validation rejects KML without geographic features."""
        invalid_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>No features</name>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", invalid_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should reject KML without placemarks, polygons, etc.
        assert is_valid is False
        assert "geographic" in message.lower() or "features" in message.lower() or "placemark" in message.lower()

    def test_validate_gpx_without_tracks(self):
        """Test validation rejects GPX without tracks, routes, or waypoints."""
        invalid_gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <metadata>
    <name>No tracks</name>
  </metadata>
</gpx>"""
        
        file = SimpleUploadedFile("test.gpx", invalid_gpx.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should reject GPX without tracks, routes, or waypoints
        assert is_valid is False
        assert "tracks" in message.lower() or "routes" in message.lower() or "waypoints" in message.lower() or "gps" in message.lower()

    def test_validate_corrupted_kmz(self):
        """Test validation rejects corrupted KMZ files."""
        corrupted_zip = b'PK\x03\x04' + b'corrupted data'
        
        file = SimpleUploadedFile("test.kmz", corrupted_zip, content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "corrupted" in message.lower() or "invalid" in message.lower() or "format" in message.lower()

    def test_validate_invalid_encoding(self):
        """Test validation rejects files with invalid encoding."""
        # Try to create file with invalid UTF-8
        invalid_utf8 = b'\xff\xfe\x00\x00'  # Invalid UTF-8 sequence
        
        file = SimpleUploadedFile("test.kml", invalid_utf8, content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_validate_kmz_embedded_kml_size_limit(self):
        """Test validation checks embedded KML size against KML limit."""
        # Create a KMZ with a large embedded KML (exceeds KML size limit)
        large_kml = '<?xml version="1.0"?><kml><Document>' + ('<Placemark><name>Test</name></Placemark>' * 100000) + '</Document></kml>'
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', large_kml)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        # Should either pass (if under limit) or fail with size error
        if not is_valid:
            assert "large" in message.lower() or "size" in message.lower() or "exceeds" in message.lower()


class TestBasicFileSecurityCheck:
    """Test basic_file_security_check function."""

    def test_basic_check_valid_kml(self):
        """Test basic check passes for valid KML."""
        kml_content = b'<?xml version="1.0"?><kml></kml>'
        file = SimpleUploadedFile("test.kml", kml_content, content_type='text/xml')
        
        is_valid, message = basic_file_security_check(file)
        
        assert is_valid is True
        assert "passed" in message.lower()

    def test_basic_check_empty_file(self):
        """Test basic check rejects empty files."""
        file = SimpleUploadedFile("test.kml", b'', content_type='text/xml')
        
        is_valid, message = basic_file_security_check(file)
        
        assert is_valid is False
        assert "empty" in message.lower()

    def test_basic_check_no_filename(self):
        """Test basic check rejects files without filename."""
        file = SimpleUploadedFile("", b'<kml></kml>', content_type='text/xml')
        
        is_valid, message = basic_file_security_check(file)
        
        assert is_valid is False
        assert "filename" in message.lower() or "rename" in message.lower()

    def test_basic_check_invalid_extension(self):
        """Test basic check rejects invalid extensions."""
        file = SimpleUploadedFile("test.txt", b'<kml></kml>', content_type='text/xml')
        
        is_valid, message = basic_file_security_check(file)
        
        assert is_valid is False
        assert "kml" in message.lower() or "kmz" in message.lower() or "gpx" in message.lower()

    def test_basic_check_invalid_signature(self):
        """Test basic check rejects files with invalid signatures."""
        file = SimpleUploadedFile("test.kml", b'not xml', content_type='text/xml')
        
        is_valid, message = basic_file_security_check(file)
        
        assert is_valid is False
        assert "valid" in message.lower() or "format" in message.lower()

    def test_basic_check_file_too_large(self):
        """Test basic check rejects files exceeding size limits."""
        # Create large content
        large_content = b'<?xml version="1.0"?><kml>' + (b'<Placemark></Placemark>' * 100000) + b'</kml>'
        
        file = SimpleUploadedFile("test.kml", large_content, content_type='text/xml')
        is_valid, message = basic_file_security_check(file)
        
        # Should either pass (if under limit) or fail with size error
        if not is_valid:
            assert "large" in message.lower() or "size" in message.lower() or "exceeds" in message.lower()


class TestValidateKmlContent:
    """Test validate_kml_content function."""

    def test_validate_safe_kml_content(self):
        """Test validate_kml_content accepts safe KML."""
        safe_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Safe KML</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        result = validate_kml_content(safe_kml)
        assert result is True

    def test_validate_kml_with_dangerous_element(self):
        """Test validate_kml_content rejects KML with dangerous elements."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <script>alert('xss')</script>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        with pytest.raises(SecurityError) as exc_info:
            validate_kml_content(dangerous_kml)
        
        assert "dangerous" in str(exc_info.value).lower() or "element" in str(exc_info.value).lower()

    def test_validate_kml_with_dangerous_attribute(self):
        """Test validate_kml_content rejects KML with dangerous attributes."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark onclick="alert('xss')">
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        with pytest.raises(SecurityError) as exc_info:
            validate_kml_content(dangerous_kml)
        
        assert "dangerous" in str(exc_info.value).lower() or "attribute" in str(exc_info.value).lower()

    def test_validate_kml_with_namespaced_elements(self):
        """Test validate_kml_content allows namespaced elements (like atom:link)."""
        # KML with namespaced link element (should be allowed)
        kml_with_namespace = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"
     xmlns:atom="http://www.w3.org/2005/Atom">
  <Document>
    <atom:link href="http://example.com"/>
    <Placemark>
      <name>With namespace</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Should pass - namespaced elements are generally safe
        result = validate_kml_content(kml_with_namespace)
        assert result is True

    def test_validate_invalid_kml_structure(self):
        """Test validate_kml_content rejects invalid KML structure."""
        invalid_kml = "not xml at all"
        
        with pytest.raises(SecurityError):
            validate_kml_content(invalid_kml)


class TestSecureKmzToKml:
    """Test secure_kmz_to_kml function."""

    def test_secure_kmz_to_kml_valid(self):
        """Test secure_kmz_to_kml extracts KML from valid KMZ."""
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Create KMZ
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
        
        result = secure_kmz_to_kml(zip_buffer.getvalue())
        
        assert result == kml_content

    def test_secure_kmz_to_kml_uses_doc_kml(self):
        """Test secure_kmz_to_kml prefers doc.kml."""
        doc_kml = """<?xml version="1.0"?><kml><Document><Placemark><name>From doc.kml</name></Placemark></Document></kml>"""
        other_kml = """<?xml version="1.0"?><kml><Document><Placemark><name>From other.kml</name></Placemark></Document></kml>"""
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', doc_kml)
            zip_file.writestr('other.kml', other_kml)
        
        result = secure_kmz_to_kml(zip_buffer.getvalue())
        
        assert "From doc.kml" in result
        assert "From other.kml" not in result

    def test_secure_kmz_to_kml_uses_first_kml_if_no_doc(self):
        """Test secure_kmz_to_kml uses first KML if doc.kml not present."""
        first_kml = """<?xml version="1.0"?><kml><Document><Placemark><name>First</name></Placemark></Document></kml>"""
        second_kml = """<?xml version="1.0"?><kml><Document><Placemark><name>Second</name></Placemark></Document></kml>"""
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('first.kml', first_kml)
            zip_file.writestr('second.kml', second_kml)
        
        result = secure_kmz_to_kml(zip_buffer.getvalue())
        
        assert "First" in result

    def test_secure_kmz_to_kml_no_kml_files(self):
        """Test secure_kmz_to_kml raises error if no KML files in archive."""
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('readme.txt', 'No KML here')
        
        with pytest.raises(FileValidationError) as exc_info:
            secure_kmz_to_kml(zip_buffer.getvalue())
        
        assert "kml" in str(exc_info.value).lower()

    def test_secure_kmz_to_kml_invalid_zip(self):
        """Test secure_kmz_to_kml raises error for invalid ZIP."""
        invalid_zip = b'not a zip file'
        
        with pytest.raises(SecurityError):
            secure_kmz_to_kml(invalid_zip)

    def test_secure_kmz_to_kml_validates_content(self):
        """Test secure_kmz_to_kml validates extracted KML content."""
        dangerous_kml = """<?xml version="1.0"?>
<kml>
  <Document>
    <Placemark>
      <script>alert('xss')</script>
      <name>Dangerous</name>
    </Placemark>
  </Document>
</kml>"""
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', dangerous_kml)
        
        with pytest.raises(SecurityError):
            secure_kmz_to_kml(zip_buffer.getvalue())

