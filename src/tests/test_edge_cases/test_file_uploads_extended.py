"""
Extended tests for file upload edge cases and error handling.
"""
import zipfile
import time
from io import BytesIO
from django.test import TransactionTestCase
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import connections

from django.contrib.auth import get_user_model

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.jobs.helpers.status_tracker import ProcessingStatus, status_tracker


class TestLargeFileUploads(TransactionTestCase):
    """Test handling of large file uploads with real backend processing."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def _wait_for_job_completion(self, job_id: str, timeout: float = 60.0) -> dict:
        """Wait for job to complete with timeout."""
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value, 
                         ProcessingStatus.COMPLETED, ProcessingStatus.FAILED]:
                return job_status
            
            time.sleep(0.5)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")

    def test_upload_large_kml_file(self):
        """Test uploading a large KML file with real processing."""

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

    def test_upload_very_large_coordinates(self):
        """Test uploading file with very long coordinate lists."""

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


class TestCorruptedFiles(TransactionTestCase):
    """Test handling of corrupted or malformed files."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def test_upload_truncated_kml(self):
        """Test uploading truncated KML file."""

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

    def test_upload_invalid_xml(self):
        """Test uploading file with invalid XML."""

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

    def test_upload_binary_garbage(self):
        """Test uploading binary garbage as KML."""

        # Random binary data
        binary_data = bytes([i % 256 for i in range(1000)])
        
        file = SimpleUploadedFile("garbage.kml", binary_data)
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept or reject based on initial validation
        self.assertIn(response.status_code, [200, 400, 500])


class TestCorruptedKMZ(TransactionTestCase):
    """Test handling of corrupted KMZ files."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def test_upload_corrupted_zip(self):
        """Test uploading corrupted ZIP file as KMZ."""

        # Create corrupted ZIP data
        corrupted_zip = b'PK\x03\x04' + bytes([0xFF] * 100)
        
        file = SimpleUploadedFile("corrupted.kmz", corrupted_zip)
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle gracefully
        self.assertIn(response.status_code, [200, 400, 500])

    def test_upload_kmz_missing_doc_kml(self):
        """Test uploading KMZ without doc.kml file."""

        # Create ZIP without doc.kml
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('other.kml', '<kml></kml>')
            zip_file.writestr('readme.txt', 'This has no doc.kml')
        
        file = SimpleUploadedFile("no_doc.kmz", zip_buffer.getvalue())
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should handle missing doc.kml
        self.assertIn(response.status_code, [200, 400, 500])

    def test_upload_kmz_with_nested_folders(self):
        """Test uploading KMZ with nested folder structure."""

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


class TestInvalidCoordinates(TransactionTestCase):
    """Test handling of invalid coordinates in files."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def test_upload_kml_with_invalid_latitude(self):
        """Test KML with latitude > 90 or < -90."""

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

    def test_upload_kml_with_invalid_longitude(self):
        """Test KML with longitude > 180 or < -180."""

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

    def test_upload_kml_with_non_numeric_coordinates(self):
        """Test KML with non-numeric coordinates."""

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


class TestEmptyFiles(TransactionTestCase):
    """Test handling of empty or minimal files."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def test_upload_completely_empty_file(self):
        """Test uploading completely empty file."""

        file = SimpleUploadedFile("empty.kml", b'')
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should reject or handle gracefully
        self.assertIn(response.status_code, [200, 400])

    def test_upload_kml_with_no_features(self):
        """Test uploading valid KML with no features."""

        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
  </Document>
</kml>"""
        
        file = SimpleUploadedFile("no_features.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept - empty file is valid
        self.assertEqual(response.status_code, 200)

    def test_upload_gpx_with_no_tracks(self):
        """Test uploading GPX with no tracks or waypoints."""

        gpx_content = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Test">
</gpx>"""
        
        file = SimpleUploadedFile("no_tracks.gpx", gpx_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        # Should accept - empty GPX is valid
        self.assertEqual(response.status_code, 200)


class TestSpecialCharactersInFiles(TransactionTestCase):
    """Test handling of special characters in file content."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user, _ = User.objects.get_or_create(
            username='testuser',
            defaults={
                'email': 'test@example.com',
                'password': 'testpass123'
            }
        )
        # Set password separately since get_or_create doesn't hash it
        if not self.user.has_usable_password():
            self.user.set_password('testpass123')
            self.user.save()
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up after tests."""
        # Import processing now runs as a Celery task dispatched synchronously (eager mode) by
        # the upload view itself, so the response has already waited for it - no background
        # worker to stop or in-flight job to wait out here.

        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()
        # Close all database connections to prevent flush issues
        connections.close_all()

    def test_upload_kml_with_emoji(self):
        """Test KML with emoji in names."""

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

    def test_upload_kml_with_cdata(self):
        """Test KML with CDATA sections."""

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

    def test_upload_kml_with_html_entities(self):
        """Test KML with HTML entities."""

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


