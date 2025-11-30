"""
Extended tests for file upload edge cases and error handling.
"""
import zipfile
from io import BytesIO
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue


class TestLargeFileUploads(TestCase):
    """Test handling of large file uploads."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_large_kml_file(self, mock_status_tracker, mock_process_job):
        """Test uploading a large KML file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Create a large KML file (with many placemarks)
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>"""
        
        # Add many placemarks
        for i in range(1000):
            kml_content += f"""
    <Placemark>
      <name>Point {i}</name>
      <Point>
        <coordinates>{-122.4194 + i * 0.001},{37.7749 + i * 0.001},0</coordinates>
      </Point>
    </Placemark>"""
        
        kml_content += """
  </Document>
</kml>"""

        # Should be several MB
        self.assertGreater(len(kml_content), 100000)
        
        file = SimpleUploadedFile("large.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle large file
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_very_large_coordinates(self, mock_status_tracker, mock_process_job):
        """Test uploading file with very long coordinate lists."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Create GPX with many track points
        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
  <trk>
    <name>Long Track</name>
    <trkseg>"""
        
        for i in range(10000):
            gpx_content += f"""
      <trkpt lat="{37.7749 + i * 0.0001}" lon="{-122.4194 + i * 0.0001}">
        <ele>{100 + i}</ele>
      </trkpt>"""
        
        gpx_content += """
    </trkseg>
  </trk>
</gpx>"""

        file = SimpleUploadedFile("long_track.gpx", gpx_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle long coordinate lists
        self.assertEqual(response.status_code, 200)


class TestCorruptedFiles(TestCase):
    """Test handling of corrupted or malformed files."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_truncated_kml(self, mock_status_tracker, mock_process_job):
        """Test uploading truncated KML file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Truncated KML (missing closing tags)
        truncated_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Truncated"""
        
        file = SimpleUploadedFile("truncated.kml", truncated_kml.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept upload, error will be caught during processing
        self.assertIn(response.status_code, [200, 400])

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_invalid_xml(self, mock_status_tracker, mock_process_job):
        """Test uploading file with invalid XML."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        invalid_xml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Invalid & unescaped & characters</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("invalid.kml", invalid_xml.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept upload, validation happens during processing
        self.assertIn(response.status_code, [200, 400])

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_binary_garbage(self, mock_status_tracker, mock_process_job):
        """Test uploading binary garbage as KML."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Random binary data
        binary_data = bytes([i % 256 for i in range(1000)])
        
        file = SimpleUploadedFile("garbage.kml", binary_data)
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept or reject based on initial validation
        self.assertIn(response.status_code, [200, 400, 500])


class TestCorruptedKMZ(TestCase):
    """Test handling of corrupted KMZ files."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_corrupted_zip(self, mock_status_tracker, mock_process_job):
        """Test uploading corrupted ZIP file as KMZ."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Create corrupted ZIP data
        corrupted_zip = b'PK\x03\x04' + bytes([0xFF] * 100)
        
        file = SimpleUploadedFile("corrupted.kmz", corrupted_zip)
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle gracefully
        self.assertIn(response.status_code, [200, 400, 500])

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kmz_missing_doc_kml(self, mock_status_tracker, mock_process_job):
        """Test uploading KMZ without doc.kml file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        # Create ZIP without doc.kml
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('other.kml', '<kml></kml>')
            zip_file.writestr('readme.txt', 'This has no doc.kml')
        
        file = SimpleUploadedFile("no_doc.kmz", zip_buffer.getvalue())
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle missing doc.kml
        self.assertIn(response.status_code, [200, 400, 500])

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kmz_with_nested_folders(self, mock_status_tracker, mock_process_job):
        """Test uploading KMZ with nested folder structure."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Nested KML</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Create ZIP with nested folders
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', kml_content)
            zip_file.writestr('subfolder/image.png', b'fake image data')
            zip_file.writestr('subfolder/nested/data.txt', 'nested data')
        
        file = SimpleUploadedFile("nested.kmz", zip_buffer.getvalue())
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle nested structure
        self.assertEqual(response.status_code, 200)


class TestInvalidCoordinates(TestCase):
    """Test handling of invalid coordinates in files."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_invalid_latitude(self, mock_status_tracker, mock_process_job):
        """Test KML with latitude > 90 or < -90."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Invalid Latitude</name>
      <Point>
        <coordinates>0,100,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("invalid_lat.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept, validation happens during processing
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_invalid_longitude(self, mock_status_tracker, mock_process_job):
        """Test KML with longitude > 180 or < -180."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Invalid Longitude</name>
      <Point>
        <coordinates>200,0,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("invalid_lon.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_non_numeric_coordinates(self, mock_status_tracker, mock_process_job):
        """Test KML with non-numeric coordinates."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Non-numeric</name>
      <Point>
        <coordinates>abc,def,ghi</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("non_numeric.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        self.assertEqual(response.status_code, 200)


class TestEmptyFiles(TestCase):
    """Test handling of empty or minimal files."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_completely_empty_file(self, mock_status_tracker, mock_process_job):
        """Test uploading completely empty file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        file = SimpleUploadedFile("empty.kml", b'')
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should reject or handle gracefully
        self.assertIn(response.status_code, [200, 400])

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_no_features(self, mock_status_tracker, mock_process_job):
        """Test uploading valid KML with no features."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("no_features.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept - empty file is valid
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_gpx_with_no_tracks(self, mock_status_tracker, mock_process_job):
        """Test uploading GPX with no tracks or waypoints."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
</gpx>"""
        
        file = SimpleUploadedFile("no_tracks.gpx", gpx_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept - empty GPX is valid
        self.assertEqual(response.status_code, 200)


class TestSpecialCharactersInFiles(TestCase):
    """Test handling of special characters in file content."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_emoji(self, mock_status_tracker, mock_process_job):
        """Test KML with emoji in names."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>🏔️ Mountain Peak 🎉</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("emoji.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle emoji
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_cdata(self, mock_status_tracker, mock_process_job):
        """Test KML with CDATA sections."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>CDATA Test</name>
      <description><![CDATA[This has <b>HTML</b> & special chars]]></description>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("cdata.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle CDATA properly
        self.assertEqual(response.status_code, 200)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_kml_with_html_entities(self, mock_status_tracker, mock_process_job):
        """Test KML with HTML entities."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Entities &amp; &lt; &gt; &quot; &apos;</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("entities.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle HTML entities
        self.assertEqual(response.status_code, 200)


