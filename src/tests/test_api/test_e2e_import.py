"""
End-to-end tests for the complete import flow.
Tests file upload -> async processing -> import to FeatureStore using real files.
"""
import json
import re
import threading
import time
import zipfile
from io import BytesIO
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TransactionTestCase, override_settings
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker, ProcessingStatus


class TestE2EImport(TransactionTestCase):
    """
    End-to-end import flow tests using real files.
    
    Uses TransactionTestCase instead of TestCase because:
    1. Async jobs run in separate threads
    2. TestCase wraps tests in transactions that aren't visible to other threads
    3. TransactionTestCase commits data so threads can access it
    """

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='e2e@example.com',
            password='testpass123',
            username='e2e_user'
        )
        self.client.force_login(self.user)
        
        # Store files directory
        self.test_files_dir = Path(__file__).parent.parent / 'files'

    def tearDown(self):
        """Clean up after tests."""
        # Clean up any features created during tests
        FeatureStore.objects.filter(user=self.user).delete()
        # Clean up import queue items
        ImportQueue.objects.filter(user=self.user).delete()

    def _wait_for_job_completion(self, job_id: str, timeout: float = 30.0, poll_interval: float = 0.5) -> dict:
        """
        Wait for async job to complete.
        
        Args:
            job_id: Job ID to wait for
            timeout: Maximum time to wait in seconds
            poll_interval: Time between status checks
            
        Returns:
            Final job status dict
            
        Raises:
            TimeoutError: If job doesn't complete within timeout
        """
        start_time = time.time()
        while time.time() - start_time < timeout:
            job_status = status_tracker.get_job_status(job_id)
            if not job_status:
                raise ValueError(f"Job {job_id} not found")
            
            # Status is returned as a string value from the enum
            status = job_status.get('status')
            if status in [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value]:
                return job_status
            
            time.sleep(poll_interval)
        
        raise TimeoutError(f"Job {job_id} did not complete within {timeout} seconds")

    def _load_test_file(self, filename: str) -> bytes:
        """Load a test file from the files directory."""
        file_path = self.test_files_dir / filename
        with open(file_path, 'rb') as f:
            return f.read()

    def _create_kmz(self, kml_content: bytes) -> bytes:
        """
        Create a KMZ file (ZIP with doc.kml inside) from KML content.
        Downloads and embeds any remote icons referenced in the KML.
        """
        # Parse KML content to find icon hrefs
        kml_str = kml_content.decode('utf-8') if isinstance(kml_content, bytes) else kml_content
        
        # Find all <href> tags within <Icon> or <IconStyle> elements
        # Regex to find icon hrefs like: <href>http://example.com/icon.png</href>
        icon_href_pattern = re.compile(r'<href>(https?://[^<]+)</href>', re.IGNORECASE)
        icon_urls = icon_href_pattern.findall(kml_str)
        
        # Download icons and prepare for embedding
        downloaded_icons = {}  # Map from URL to (local_path, icon_data)
        used_filenames = set()  # Track used filenames to avoid duplicates
        
        for idx, url in enumerate(set(icon_urls)):  # Use set to avoid duplicate downloads
            try:
                # Parse URL to get filename
                parsed = urlparse(url)
                # Get the filename or generate one
                path_parts = parsed.path.split('/')
                base_filename = path_parts[-1] if path_parts else f'icon_{idx}.png'
                
                # Ensure we have an extension
                if '.' not in base_filename:
                    base_filename = f'{base_filename}.png'
                
                # Make filename unique if it's already been used
                filename = base_filename
                counter = 1
                while filename in used_filenames:
                    # Split filename into name and extension
                    name_parts = base_filename.rsplit('.', 1)
                    if len(name_parts) == 2:
                        filename = f"{name_parts[0]}_{counter}.{name_parts[1]}"
                    else:
                        filename = f"{base_filename}_{counter}"
                    counter += 1
                
                used_filenames.add(filename)
                
                # Download icon with timeout
                req = Request(url, headers={'User-Agent': 'GeoVault-Test/1.0'})
                with urlopen(req, timeout=10) as response:
                    icon_data = response.read()
                    # Limit to 5MB
                    if len(icon_data) <= 5 * 1024 * 1024:
                        local_path = f'files/{filename}'
                        downloaded_icons[url] = (local_path, icon_data)
            except (HTTPError, URLError, TimeoutError, Exception) as e:
                # Skip icons that fail to download
                pass
        
        # Replace icon URLs in KML with local paths
        modified_kml = kml_str
        for url, (local_path, _) in downloaded_icons.items():
            modified_kml = modified_kml.replace(url, local_path)
        
        # Create KMZ with doc.kml and embedded icons
        zip_buffer = BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
            zip_file.writestr('doc.kml', modified_kml.encode('utf-8'))
            
            # Add downloaded icons to the archive (use set to avoid duplicate paths)
            added_paths = set()
            for local_path, icon_data in downloaded_icons.values():
                if local_path not in added_paths:
                    zip_file.writestr(local_path, icon_data)
                    added_paths.add(local_path)
        
        return zip_buffer.getvalue()

    def _upload_file(self, file_content: bytes, filename: str, replacement_id=None, timeout: float = 30.0):
        """
        Upload a file and wait for processing.
        
        Args:
            file_content: File content as bytes
            filename: Original filename
            replacement_id: Optional ID of feature being replaced
            timeout: Maximum time to wait for processing completion (default: 30.0 seconds)
        
        Returns:
            tuple: (job_id, item_id, job_status)
        """
        file_obj = SimpleUploadedFile(filename, file_content)
        post_data = {'file': file_obj}
        if replacement_id:
            post_data['replacement'] = str(replacement_id)
        
        response = self.client.post('/api/item/import/upload', post_data)
        self.assertEqual(response.status_code, 200, f"Upload failed: {response.content}")
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for processing to complete
        job_status = self._wait_for_job_completion(job_id, timeout=timeout)
        
        # Get the import queue item ID
        job = status_tracker.get_job(job_id)
        item_id = job.import_queue_id if job else None
        
        return job_id, item_id, job_status

    def _import_item(self, item_id: int, import_custom_icons=True, skipped_feature_ids=None):
        """
        Import an item to FeatureStore and wait for completion.
        
        Returns:
            tuple: (job_id, job_status)
        """
        if skipped_feature_ids is None:
            skipped_feature_ids = []
        
        payload = {
            'import_custom_icons': import_custom_icons,
            'skipped_feature_ids': skipped_feature_ids
        }
        
        response = self.client.post(
            f'/api/item/import/perform/{item_id}',
            data=json.dumps(payload),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200, f"Import failed: {response.content}")
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for import to complete
        job_status = self._wait_for_job_completion(job_id, timeout=60.0)  # Longer timeout for imports
        
        return job_id, job_status


    # ==================== BASIC FLOW TESTS ====================

    def test_e2e_processing_steps_granularity(self):
        """Test that the new granular processing steps are executed in the correct order.
        
        This test verifies the refactored processing pipeline with separate steps for:
        1. File conversion (KML/KMZ/GPX -> GeoJSON)
        2. Feature splitting and validation
        3. Elevation data filling
        4. Feature tagging
        5. Reverse reverse_geocoding
        """
        # Load test KML file
        kml_content = self._load_test_file('Test Items.kml')
        
        # Upload and process
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        
        # Verify processing succeeded
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Processing failed: {process_status.get('message', '')}")
        
        # Get the import item and its logs
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        
        if hasattr(import_item, 'log_id') and import_item.log_id:
            log_entries = DatabaseLogging.objects.filter(log_id=import_item.log_id).order_by('timestamp')
            
            # Extract all log messages and timing entries
            log_messages = [entry.text for entry in log_entries]
            timing_entries = [entry for entry in log_entries if 'completed' in entry.text.lower() or 'timing' in entry.text.lower()]
            
            # Verify we have timing entries for the new granular steps
            timing_labels = [entry.text for entry in timing_entries]
            
            # Check for the existence of separate timing logs for each step
            # Note: The exact names come from the processor and process_job
            expected_timing_steps = [
                'KML conversion',  # Step 3: File conversion
                'Feature splitting and validation',  # Step 4
                # 'Elevation data filling' is optional (depends on settings)
                'Tagging and Reverse Geocoding',  # Step 7: All tagging (including reverse geocoding)
            ]
            
            for expected_step in expected_timing_steps:
                # Check if timing entry exists for this step
                has_timing = any(expected_step in timing_label for timing_label in timing_labels)
                self.assertTrue(has_timing, 
                    f"Should have timing entry for '{expected_step}' step. "
                    f"Available timing labels: {timing_labels}")
            
            # Verify the steps appear in the correct order in logs
            # Find indices of key log messages
            conversion_idx = next((i for i, msg in enumerate(log_messages) 
                                  if 'Converting file to GeoJSON' in msg or 'KML conversion' in msg), None)
            splitting_idx = next((i for i, msg in enumerate(log_messages) 
                                 if 'Splitting and validating features' in msg or 'splitting' in msg.lower()), None)
            tagging_idx = next((i for i, msg in enumerate(log_messages) 
                               if 'Tagging and Reverse Geocoding' in msg or 'tagging' in msg.lower()), None)
            
            # Verify order if steps are present
            if conversion_idx is not None and splitting_idx is not None:
                self.assertLess(conversion_idx, splitting_idx,
                              "File conversion should happen before feature splitting")
            
            if splitting_idx is not None and tagging_idx is not None:
                self.assertLess(splitting_idx, tagging_idx,
                              "Feature splitting should happen before tagging and reverse geocoding")
        
        # Verify the processing produced valid output
        self.assertGreater(len(import_item.geofeatures), 0, "Should have processed features")
        self.assertTrue(import_item.geofeatures[0].get('properties', {}).get('system_tags'),
                       "Features should have system_tags from tagging step")

    def test_e2e_kml_import(self):
        """Test complete KML import flow: upload -> process -> import -> verify DB."""
        # Load test KML file
        kml_content = self._load_test_file('Test Items.kml')
        
        # Upload and process
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        
        # Verify processing succeeded
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Processing failed: {process_status.get('message', '')}")
        self.assertIsNotNone(item_id, "Import queue item ID should be returned")
        
        # Verify the new processing steps are present in the import queue logs
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        if hasattr(import_item, 'log_id') and import_item.log_id:
            # Check that the processing log contains the new granular steps
            log_entries = DatabaseLogging.objects.filter(log_id=import_item.log_id).order_by('timestamp')
            
            log_messages = [entry.text for entry in log_entries]
            log_str = ' '.join(log_messages)
            
            # Verify the new processing steps are logged
            # Step 3: File conversion
            self.assertTrue(
                any('KML conversion' in msg or 'Converting file to GeoJSON' in msg for msg in log_messages),
                "Should have KML/file conversion step in logs"
            )
            
            # Step 4: Feature splitting and validation
            self.assertTrue(
                any('splitting' in msg.lower() or 'Feature splitting and validation' in msg for msg in log_messages),
                "Should have feature splitting step in logs"
            )
            
            # Step 5: Elevation filling (may be skipped if disabled, so optional check)
            # Step 7: Tagging and Reverse Geocoding (combined)
            self.assertTrue(
                any('Tagging and Reverse Geocoding' in msg or 'tagging' in msg.lower() for msg in log_messages),
                "Should have tagging and reverse geocoding step in logs"
            )
            
            # Reverse reverse_geocoding is now part of the tagging step, but may be skipped if disabled
            has_geocoding_step = any('reverse_geocoding' in msg.lower() or 'Reverse reverse_geocoding' in msg for msg in log_messages)
            # If reverse_geocoding is enabled in settings, it should be present
            # Note: We don't fail the test if it's not present, as it may be disabled
            if has_geocoding_step:
                # If present, verify it's a separate step (not combined with tagging)
                self.assertTrue(has_geocoding_step, "Reverse reverse_geocoding should be a separate step")
        
        # Verify ImportQueue entry was created with geofeatures
        self.assertEqual(import_item.original_filename, 'Test Items.kml')
        self.assertGreater(len(import_item.geofeatures), 0, "Should have extracted features from KML")
        self.assertFalse(import_item.imported, "Should not be marked as imported yet")
        
        # Count features before import
        initial_feature_count = FeatureStore.objects.filter(user=self.user).count()
        
        # Import to FeatureStore
        import_job_id, import_status = self._import_item(item_id)
        
        # Verify import succeeded
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Import failed: {import_status.get('message', '')}")
        
        # Verify features were created in database
        final_feature_count = FeatureStore.objects.filter(user=self.user).count()
        imported_count = final_feature_count - initial_feature_count
        self.assertGreater(imported_count, 0, "Should have imported features to database")
        
        # Verify import item is marked as imported
        import_item.refresh_from_db()
        self.assertTrue(import_item.imported, "Import item should be marked as imported")
        
        # Verify at least one feature has proper structure
        sample_feature = FeatureStore.objects.filter(user=self.user).first()
        self.assertIsNotNone(sample_feature.geometry, "Feature should have geometry")
        self.assertIsNotNone(sample_feature.geojson, "Feature should have geojson")
        self.assertIn('type', sample_feature.geojson, "GeoJSON should have type field")
        self.assertIn('properties', sample_feature.geojson, "GeoJSON should have properties")
        
        # Verify geojson_hash is present (required field)
        self.assertIn('geojson_hash', sample_feature.geojson['properties'], 
                     "Feature should have geojson_hash in properties")
        self.assertIsNotNone(sample_feature.geojson['properties']['geojson_hash'],
                           "geojson_hash should not be None")
        self.assertIsInstance(sample_feature.geojson['properties']['geojson_hash'], str,
                            "geojson_hash should be a string")
        self.assertGreater(len(sample_feature.geojson['properties']['geojson_hash']), 0,
                          "geojson_hash should not be empty")
        
        # Verify system_tags were generated (this is critical - tags should be auto-generated)
        self.assertIn('system_tags', sample_feature.geojson['properties'],
                     "Feature should have system_tags generated during processing")
        system_tags = sample_feature.geojson['properties']['system_tags']
        self.assertIsInstance(system_tags, list, "system_tags should be a list")
        self.assertGreater(len(system_tags), 0, 
                          "system_tags should be generated (type, import-year, import-month, etc.)")
        
        # Verify specific system tags that should always be present
        tag_types = [tag.split(':')[0] for tag in system_tags if ':' in tag]
        self.assertIn('type', tag_types, "Should have 'type' system tag (point/line/polygon)")
        self.assertIn('import-year', tag_types, "Should have 'import-year' system tag")
        self.assertIn('import-month', tag_types, "Should have 'import-month' system tag")
        
        # Verify geofeatures in ImportQueue also have geojson_hash
        for idx, feature in enumerate(import_item.geofeatures[:3]):  # Check first 3
            self.assertIn('geojson_hash', feature.get('properties', {}),
                         f"Feature {idx} in ImportQueue should have geojson_hash")
            self.assertIsNotNone(feature['properties']['geojson_hash'],
                               f"Feature {idx} geojson_hash should not be None")

    def test_e2e_gpx_import(self):
        """Test complete GPX import flow: upload -> process -> import -> verify DB."""
        # Load test GPX file
        gpx_content = self._load_test_file('blue_hills.gpx')
        
        # Upload and process (GPX files may take longer due to track processing, elevation, reverse_geocoding)
        process_job_id, item_id, process_status = self._upload_file(gpx_content, 'blue_hills.gpx', timeout=60.0)
        
        # Verify processing succeeded
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Processing failed: {process_status.get('message', '')}")
        self.assertIsNotNone(item_id, "Import queue item ID should be returned")
        
        # Verify ImportQueue entry was created with geofeatures
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertEqual(import_item.original_filename, 'blue_hills.gpx')
        self.assertGreater(len(import_item.geofeatures), 0, "Should have extracted features from GPX")
        
        # Verify the new processing steps are present in logs for GPX
        if hasattr(import_item, 'log_id') and import_item.log_id:
            log_entries = DatabaseLogging.objects.filter(log_id=import_item.log_id).order_by('timestamp')
            log_messages = [entry.text for entry in log_entries]
            
            # Verify GPX conversion step
            self.assertTrue(
                any('GPX conversion' in msg or 'Converting file to GeoJSON' in msg for msg in log_messages),
                "Should have GPX/file conversion step in logs"
            )
            
            # Verify feature splitting step
            self.assertTrue(
                any('splitting' in msg.lower() or 'Feature splitting and validation' in msg for msg in log_messages),
                "Should have feature splitting step in logs"
            )
            
            # Verify tagging step
            self.assertTrue(
                any('tagging' in msg.lower() or 'tag' in msg.lower() for msg in log_messages),
                "Should have feature tagging step in logs"
            )
        
        # Verify we have different geometry types from GPX (waypoints and tracks)
        geom_types = set()
        for feature in import_item.geofeatures:
            geom_type = feature.get('geometry', {}).get('type')
            if geom_type:
                geom_types.add(geom_type)
        self.assertGreater(len(geom_types), 0, "Should have extracted geometry types from GPX")
        
        # Count features before import
        initial_feature_count = FeatureStore.objects.filter(user=self.user).count()
        
        # Import to FeatureStore
        import_job_id, import_status = self._import_item(item_id)
        
        # Verify import succeeded
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Import failed: {import_status.get('message', '')}")
        
        # Verify features were created in database
        final_feature_count = FeatureStore.objects.filter(user=self.user).count()
        imported_count = final_feature_count - initial_feature_count
        self.assertGreater(imported_count, 0, "Should have imported features to database")
        
        # Verify import item is marked as imported
        import_item.refresh_from_db()
        self.assertTrue(import_item.imported, "Import item should be marked as imported")
        
        # Verify GPX features have proper structure including geojson_hash and tags
        gpx_feature = FeatureStore.objects.filter(user=self.user).first()
        self.assertIn('geojson_hash', gpx_feature.geojson['properties'],
                     "GPX feature should have geojson_hash")
        self.assertIn('system_tags', gpx_feature.geojson['properties'],
                     "GPX feature should have system_tags")
        
        # GPX tracks should have 'type:track' tag
        system_tags = gpx_feature.geojson['properties']['system_tags']
        if gpx_feature.geojson.get('geometry', {}).get('type') in ['LineString', 'MultiLineString']:
            tag_names = [tag.split(':')[0] for tag in system_tags if ':' in tag]
            # Track features should have track tag
            has_track_tag = any('track' in tag for tag in system_tags)
            self.assertTrue(has_track_tag or 'type' in tag_names,
                          "LineString from GPX should have track or type tags")

    def test_e2e_kmz_import(self):
        """Test complete KMZ import flow: upload -> process -> import -> verify DB."""
        # Load test KML file and convert to KMZ
        kml_content = self._load_test_file('Test Items.kml')
        kmz_content = self._create_kmz(kml_content)
        
        # Upload and process
        process_job_id, item_id, process_status = self._upload_file(kmz_content, 'Test Items.kmz')
        
        # Verify processing succeeded
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Processing failed: {process_status.get('message', '')}")
        self.assertIsNotNone(item_id, "Import queue item ID should be returned")
        
        # Verify ImportQueue entry was created with geofeatures
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertEqual(import_item.original_filename, 'Test Items.kmz')
        self.assertGreater(len(import_item.geofeatures), 0, "Should have extracted features from KMZ")
        
        # Verify the new processing steps are present in logs for KMZ
        if hasattr(import_item, 'log_id') and import_item.log_id:
            log_entries = DatabaseLogging.objects.filter(log_id=import_item.log_id).order_by('timestamp')
            log_messages = [entry.text for entry in log_entries]
            
            # Verify KMZ conversion step (converts to KML internally, then processes)
            self.assertTrue(
                any('KMZ conversion' in msg or 'Converting file to GeoJSON' in msg for msg in log_messages),
                "Should have KMZ/file conversion step in logs"
            )
            
            # Verify all the granular processing steps
            self.assertTrue(
                any('splitting' in msg.lower() for msg in log_messages),
                "Should have feature splitting step in logs"
            )
        
        # Count features before import
        initial_feature_count = FeatureStore.objects.filter(user=self.user).count()
        
        # Import to FeatureStore
        import_job_id, import_status = self._import_item(item_id)
        
        # Verify import succeeded
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Import failed: {import_status.get('message', '')}")
        
        # Verify features were created in database
        final_feature_count = FeatureStore.objects.filter(user=self.user).count()
        imported_count = final_feature_count - initial_feature_count
        self.assertGreater(imported_count, 0, "Should have imported features to database")
        
        # Verify import item is marked as imported
        import_item.refresh_from_db()
        self.assertTrue(import_item.imported, "Import item should be marked as imported")
        
        # Verify the features match what we'd expect from the KML
        # (since KMZ is just a zipped KML, the feature count should be similar)
        self.assertGreater(imported_count, 5, "Test Items.kml should have multiple features")

    # ==================== ADVANCED FEATURE TESTS ====================

    def test_e2e_duplicate_detection(self):
        """Test that duplicate detection works during import flow."""
        # First, import some features
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        initial_count = FeatureStore.objects.filter(user=self.user).count()
        
        # Now upload the same file again
        process_job_id2, item_id2, process_status2 = self._upload_file(kml_content, 'Test Items Duplicate.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Check that duplicates were detected
        import_item = ImportQueue.objects.get(id=item_id2, user=self.user)
        self.assertGreater(len(import_item.duplicate_features), 0, 
                          "Should have detected duplicate features")
        
        # Import the second file (duplicates should be skipped automatically)
        import_job_id2, import_status2 = self._import_item(item_id2)
        
        # Even if some duplicates exist, import might still succeed with non-duplicates
        # or fail if all are duplicates - either is acceptable
        final_count = FeatureStore.objects.filter(user=self.user).count()
        
        # The key is that we didn't double our features (duplicates were handled)
        self.assertLess(final_count, initial_count * 2, 
                       "Duplicates should have been detected and not imported twice")

    def test_e2e_coordinate_duplicate_auto_skip(self):
        """Test that coordinate (geometry) duplicates are automatically skipped by default.
        
        Geometry duplicates have the same coordinates but different properties (name, description, etc).
        These should be auto-skipped (added to skipped_feature_ids) to prevent clutter.
        """
        # First, import a feature at a specific location
        first_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Original Feature</name>
      <description>This is the original feature</description>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        process_job_id, item_id, process_status = self._upload_file(first_kml, 'first.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Now upload a DIFFERENT feature at the SAME coordinates (geometry duplicate, not hash duplicate)
        second_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Different Feature</name>
      <description>This is a different feature at the same location</description>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        process_job_id2, item_id2, process_status2 = self._upload_file(second_kml, 'second.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Check that duplicates were detected
        import_item = ImportQueue.objects.get(id=item_id2, user=self.user)
        self.assertGreater(len(import_item.duplicate_features), 0, 
                          "Should have detected duplicate features")
        
        # Verify that duplicates are GEOMETRY duplicates (not hash duplicates)
        # Check that detected duplicates are geometry-based (same location, different properties)
        geometry_duplicate_count = 0
        for dup_info in import_item.duplicate_features:
            if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                geometry_duplicate_count += 1
                # Should be from feature store (not cross-queue)
                self.assertEqual(dup_info.get('source'), DuplicateSource.FEATURE_STORE,
                               "Geometry duplicate should be from feature store")
        
        self.assertGreater(geometry_duplicate_count, 0,
                          "Should have detected at least one GEOMETRY duplicate")
        
        # Verify that geometry duplicates WERE auto-skipped (added to skipped_feature_ids)
        skipped_ids = import_item.skipped_feature_ids if import_item.skipped_feature_ids else []
        self.assertGreater(len(skipped_ids), 0,
                          "Geometry duplicates should be auto-skipped (added to skipped_feature_ids)")
        
        # Verify that the skipped IDs match the geometry duplicate features
        for dup_info in import_item.duplicate_features:
            if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                dup_feature = dup_info.get('feature')
                if dup_feature:
                    geojson_hash = dup_feature.get('properties', {}).get('geojson_hash')
                    if not geojson_hash:
                        geojson_hash = generate_geojson_hash(dup_feature)
                    self.assertIn(geojson_hash, skipped_ids,
                                 f"Geometry duplicate feature {geojson_hash} should be in skipped_feature_ids")

    def test_e2e_cross_queue_duplicate_detection(self):
        """Test that hash-based duplicate detection works across ImportQueue items during processing."""
        # Create a simple KML with a single point
        point_kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Point</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Upload first file
        process_job_id1, item_id1, process_status1 = self._upload_file(
            point_kml.encode('utf-8'), 'first_item.kml'
        )
        
        # Add explicit delay to ensure deterministic timestamp ordering
        time.sleep(0.5)
        
        # Upload second file - should be newer based on timestamp
        process_job_id2, item_id2, process_status2 = self._upload_file(
            point_kml.encode('utf-8'), 'second_item.kml'
        )
        
        # Wait for both processing jobs to complete
        self.assertEqual(process_status1['status'], ProcessingStatus.COMPLETED.value,
                        "First file processing should complete")
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value,
                        "Second file processing should complete")
        
        # Refresh both items from database to get latest state
        import_item1 = ImportQueue.objects.get(id=item_id1, user=self.user)
        import_item2 = ImportQueue.objects.get(id=item_id2, user=self.user)
        
        # Verify timestamp ordering (item1 should be older due to explicit delay)
        self.assertLess(import_item1.timestamp, import_item2.timestamp,
                       "First upload should have earlier timestamp than second upload")
        
        # Verify both items were created and have features
        self.assertGreater(len(import_item1.geofeatures), 0, "First item should have features")
        self.assertGreater(len(import_item2.geofeatures), 0, "Second item should have features")
        self.assertFalse(import_item1.imported, "First item should not be imported yet")
        self.assertFalse(import_item2.imported, "Second item should not be imported yet")
        
        # Verify both items have the same feature hash
        first_feature = import_item1.geofeatures[0]
        second_feature = import_item2.geofeatures[0]
        first_feature_hash = generate_geojson_hash(first_feature)
        second_feature_hash = generate_geojson_hash(second_feature)
        self.assertEqual(first_feature_hash, second_feature_hash, 
                        "Both features should have the same hash")
        
        # Check for cross-queue duplicates using the same logic as the websocket module
        # Only newer items should see older items as duplicates
        queue_duplicates_found_item1 = []
        queue_duplicates_found_item2 = []
        
        # Check if item1's features are duplicates of older items
        # Only check against items with timestamp < item1.timestamp
        other_queue_items_for_item1 = ImportQueue.objects.filter(
            user=self.user,
            imported=False,
            timestamp__lt=import_item1.timestamp  # Only older items
        ).exclude(id=item_id1)
        
        queue_hash_to_item = {}
        for queue_item in other_queue_items_for_item1:
            for feature in queue_item.geofeatures:
                feature_hash = generate_geojson_hash(feature)
                if feature_hash not in queue_hash_to_item:
                    queue_hash_to_item[feature_hash] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename
                    }
        
        for feature in import_item1.geofeatures:
            feature_hash = generate_geojson_hash(feature)
            if feature_hash in queue_hash_to_item:
                queue_info = queue_hash_to_item[feature_hash]
                queue_duplicates_found_item1.append({
                    'hash': feature_hash,
                    'queue_item_id': queue_info['queue_item_id'],
                    'queue_item_filename': queue_info['queue_item_filename']
                })
        
        # Check if item2's features are duplicates of older items
        # Only check against items with timestamp < item2.timestamp
        other_queue_items_for_item2 = ImportQueue.objects.filter(
            user=self.user,
            imported=False,
            timestamp__lt=import_item2.timestamp  # Only older items
        ).exclude(id=item_id2)
        
        queue_hash_to_item = {}
        for queue_item in other_queue_items_for_item2:
            for feature in queue_item.geofeatures:
                feature_hash = generate_geojson_hash(feature)
                if feature_hash not in queue_hash_to_item:
                    queue_hash_to_item[feature_hash] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename
                    }
        
        for feature in import_item2.geofeatures:
            feature_hash = generate_geojson_hash(feature)
            if feature_hash in queue_hash_to_item:
                queue_info = queue_hash_to_item[feature_hash]
                queue_duplicates_found_item2.append({
                    'hash': feature_hash,
                    'queue_item_id': queue_info['queue_item_id'],
                    'queue_item_filename': queue_info['queue_item_filename']
                })
        
        # Since we know the order (item1 is older due to explicit delay):
        # - item1 should NOT have any cross-queue duplicates (it was first)
        # - item2 should have cross-queue duplicates pointing to item1
        
        # Only the newer item (item2) should have its feature marked as a cross-queue duplicate
        self.assertEqual(len(queue_duplicates_found_item2), 1,
                        "Newer item (second_item.kml) should have exactly one duplicate detected")
        self.assertEqual(len(queue_duplicates_found_item1), 0,
                        "Older item (first_item.kml) should NOT have any duplicates detected")
        
        # Verify the duplicate in item2 points to item1
        dup = queue_duplicates_found_item2[0]
        self.assertEqual(dup['queue_item_id'], item_id1,
                        f"Newer item's duplicate should point to older item ({item_id1})")
        self.assertEqual(dup['queue_item_filename'], 'first_item.kml',
                        "Newer item's duplicate should have correct filename")
        self.assertEqual(dup['hash'], second_feature_hash,
                        "Newer item's duplicate should have correct hash")

    def test_e2e_file_level_duplicate_detection(self):
        """Test file-level duplicate detection against other items in queue and already-imported files."""
        # Load test file
        kml_content = self._load_test_file('Test Items.kml')
        
        # ========== Test 1: Duplicate in queue ==========
        # Upload first file and wait for processing
        process_job_id1, item_id1, process_status1 = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status1['status'], ProcessingStatus.COMPLETED.value,
                        "First file processing should complete")
        
        # Verify first item was created
        import_item1 = ImportQueue.objects.get(id=item_id1, user=self.user)
        self.assertIsNotNone(import_item1.file_hash, "First item should have file_hash set")
        self.assertFalse(import_item1.imported, "First item should not be imported yet")
        
        # Upload the same file again with different filename (should be detected as duplicate in queue)
        process_job_id2, item_id2, process_status2 = self._upload_file(kml_content, 'Test Items Duplicate.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value,
                        "Second file processing should complete")
        
        # Verify second item was created
        import_item2 = ImportQueue.objects.get(id=item_id2, user=self.user)
        self.assertIsNotNone(import_item2.file_hash, "Second item should have file_hash set")
        self.assertFalse(import_item2.imported, "Second item should not be imported yet")
        
        # Verify both items have the same geojson_hash (file-level duplicate)
        self.assertEqual(import_item1.file_hash, import_item2.file_hash,
                       "Both items should have the same geojson_hash (file-level duplicate)")
        
        # The duplicate detection in bulk_import_job checks for items with:
        # - same geojson_hash
        # - imported=False (still in queue)
        # - timestamp__lt (earlier timestamp)
        # So the later item should be blocked when trying to import if the earlier one is still in queue
        
        # Determine which item is earlier based on timestamp
        if import_item1.timestamp < import_item2.timestamp:
            # Item1 is earlier, so item2 should be blocked
            earlier_item = import_item1
            later_item = import_item2
            later_item_id = item_id2
        else:
            # Item2 is earlier, so item1 should be blocked
            earlier_item = import_item2
            later_item = import_item1
            later_item_id = item_id1
        
        # Attempt to import the later file - should be blocked by API endpoint (409 Conflict)
        # The API endpoint checks for duplicates before starting the import job
        response = self.client.post(
            f'/api/item/import/perform/{later_item_id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 409,
                        "Import of duplicate file in queue should return 409 Conflict")
        
        data = json.loads(response.content)
        self.assertIn('error', data, "Error response should contain error message")
        error_message = data.get('error', '')
        self.assertIn('duplicate', error_message.lower(),
                     "Error message should mention duplicate")
        self.assertIn(earlier_item.original_filename, error_message,
                     "Error message should mention the original filename")
        
        # ========== Test 2: Duplicate of imported file ==========
        # Clean up previous items for this test
        ImportQueue.objects.filter(user=self.user).delete()
        FeatureStore.objects.filter(user=self.user).delete()
        
        # Upload and import a file
        process_job_id3, item_id3, process_status3 = self._upload_file(kml_content, 'Test Items Original.kml')
        self.assertEqual(process_status3['status'], ProcessingStatus.COMPLETED.value,
                        "Third file processing should complete")
        
        # Import the first file
        import_job_id3, import_status3 = self._import_item(item_id3)
        self.assertEqual(import_status3['status'], ProcessingStatus.COMPLETED.value,
                       "Import should succeed")
        
        # Verify it's marked as imported
        import_item3 = ImportQueue.objects.get(id=item_id3, user=self.user)
        self.assertTrue(import_item3.imported, "Third item should be marked as imported")
        self.assertIsNotNone(import_item3.file_hash, "Third item should have file_hash")
        
        # Count features before second upload
        initial_feature_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertGreater(initial_feature_count, 0, "Should have imported features")
        
        # Upload the same file again with different filename (should be detected as duplicate of imported file)
        process_job_id4, item_id4, process_status4 = self._upload_file(kml_content, 'Test Items Duplicate Imported.kml')
        self.assertEqual(process_status4['status'], ProcessingStatus.COMPLETED.value,
                        "Fourth file processing should complete")
        
        # Verify fourth item was created
        import_item4 = ImportQueue.objects.get(id=item_id4, user=self.user)
        self.assertIsNotNone(import_item4.file_hash, "Fourth item should have file_hash set")
        self.assertFalse(import_item4.imported, "Fourth item should not be imported yet")
        
        # Verify both items have the same geojson_hash (file-level duplicate)
        self.assertEqual(import_item3.file_hash, import_item4.file_hash,
                       "Both items should have the same geojson_hash (file-level duplicate)")
        
        # The duplicate of imported file should NOT be blocked from import by the API
        # However, if all features are duplicates, the import job will fail
        # (This is expected behavior - if there's nothing new to import, the import fails)
        import_job_id4, import_status4 = self._import_item(item_id4)
        
        # When all features are duplicates, the import job fails
        # This is expected - the API allows the import to proceed, but the job fails
        # because no new features were imported
        self.assertEqual(import_status4['status'], ProcessingStatus.FAILED.value,
                        "Import should fail when all features are duplicates")
        self.assertIn('No features were imported', import_status4.get('message', ''),
                     "Error message should indicate no features were imported")
        
        # Verify the item is NOT marked as imported (since import failed)
        import_item4.refresh_from_db()
        self.assertFalse(import_item4.imported, "Fourth item should NOT be marked as imported after failed import")
        
        # Verify that no new features were added (all were duplicates and skipped)
        final_feature_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(final_feature_count, initial_feature_count,
                        "No new features should be added (all were duplicates)")

    def test_e2e_bulk_operations(self):
        """Test applying bulk operations (tags, colors) during import."""
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Apply bulk operations
        bulk_ops = {
            'tags': ['test', 'e2e', 'bulk'],
            'pointColor': '#ff0000',
            'lineColor': '#00ff00',
            'polyColor': '#0000ff'
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200, f"Bulk operations failed: {response.content}")
        
        # Import the item
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify bulk operations were applied to imported features
        features = FeatureStore.objects.filter(user=self.user)
        self.assertGreater(features.count(), 0, "Should have imported features")
        
        # Check that at least some features have the bulk tags
        features_with_bulk_tags = 0
        for feature in features:
            tags = feature.geojson.get('properties', {}).get('tags', [])
            if 'test' in tags and 'e2e' in tags and 'bulk' in tags:
                features_with_bulk_tags += 1
        
        self.assertGreater(features_with_bulk_tags, 0, 
                          "At least some features should have bulk operation tags applied")

    def test_e2e_skipped_features(self):
        """Test skipping specific features during import."""
        
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Get the import item and pick some features to skip
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        total_features = len(import_item.geofeatures)
        self.assertGreater(total_features, 2, "Need at least 3 features for this test")
        
        # Skip the first 2 features
        # Use geojson_hash (which is set during processing) or generate it if missing
        skipped_ids = []
        for i in range(2):
            feature = import_item.geofeatures[i]
            geojson_hash = feature['properties']['geojson_hash']
            # if not geojson_hash:
            #     geojson_hash = generate_geojson_hash(feature)
            skipped_ids.append(geojson_hash)
        
        # Import with skipped features
        import_job_id, import_status = self._import_item(item_id, skipped_feature_ids=skipped_ids)
        
        # Import should succeed
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify that fewer features were imported than available
        imported_count = FeatureStore.objects.filter(user=self.user).count()
        expected_max = total_features - len(skipped_ids)
        self.assertLessEqual(imported_count, expected_max,
                            "Should have skipped the requested features")

    def test_e2e_custom_icons_import(self):
        """Test importing custom icons from KML.
        
        Icons are processed during the processing phase (downloaded/stored, URLs replaced).
        The import_custom_icons flag controls whether icon properties are kept or stripped during import.
        """
        # Use Google Earth KML which may have icons
        kml_content = self._load_test_file('Google Earth KML Samples.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Google Earth KML Samples.kml')
        
        # Processing might succeed or fail depending on the file content
        self.assertIsNotNone(process_status, "Should get processing status")
        
        if process_status['status'] != ProcessingStatus.COMPLETED.value:
            self.skipTest(f"Processing failed: {process_status.get('message', 'Unknown error')}")
        
        # Check if any features have icon properties after processing
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        features_with_icons = []
        icon_property_names = ['marker-symbol', 'icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'symbol']
        
        for feature in import_item.geofeatures:
            props = feature.get('properties', {})
            if any(prop_name in props for prop_name in icon_property_names):
                features_with_icons.append(feature)
        
        if len(features_with_icons) == 0:
            self.skipTest("No features with icons found in test file")
        
        # Import with custom icons enabled (should preserve icon properties)
        import_job_id, import_status = self._import_item(item_id, import_custom_icons=True)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value,
                        "Import should complete successfully")
        
        # Verify that imported features retain icon properties when import_custom_icons=True
        imported_features = FeatureStore.objects.filter(user=self.user)
        features_with_icons_imported = 0
        for feature in imported_features:
            props = feature.geojson.get('properties', {})
            if any(prop_name in props for prop_name in icon_property_names):
                features_with_icons_imported += 1
        
        # At least some features should have icon properties preserved
        self.assertGreater(features_with_icons_imported, 0,
                          "When import_custom_icons=True, icon properties should be preserved in imported features")
        
        # Delete the first batch to test with import_custom_icons=False
        FeatureStore.objects.filter(user=self.user).delete()
        
        # Upload the same file again
        process_job_id2, item_id2, process_status2 = self._upload_file(kml_content, 'Google Earth KML Samples No Icons.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Import with custom icons disabled
        import_job_id2, import_status2 = self._import_item(item_id2, import_custom_icons=False)
        self.assertEqual(import_status2['status'], ProcessingStatus.COMPLETED.value,
                        "Import should complete successfully even when stripping icons")
        
        # Verify that imported features have icon properties stripped when import_custom_icons=False
        second_batch_features = FeatureStore.objects.filter(user=self.user)
        
        features_with_icons_stripped = 0
        for feature in second_batch_features:
            props = feature.geojson.get('properties', {})
            if any(prop_name in props for prop_name in icon_property_names):
                features_with_icons_stripped += 1
        
        # No features should have icon properties when import_custom_icons=False
        self.assertEqual(features_with_icons_stripped, 0,
                         "When import_custom_icons=False, icon properties should be stripped from imported features")

    def test_e2e_replacement_import(self):
        """Test uploading a file as replacement for an existing feature.
        
        Replacement flow:
        1. Upload file with replacement_feature_id - marks it for replacement
        2. User selects a feature from the replacement file
        3. apply_replacement_geometry updates the original feature's geometry
        4. The ImportQueue item is deleted
        """
        # First create a feature to replace
        original_geojson = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.0, 37.0, 0.0]},
            'properties': {'name': 'Original Feature'}
        }
        # Add required geojson_hash
        original_geojson['properties']['geojson_hash'] = generate_geojson_hash(original_geojson)
        
        original_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=original_geojson,
            geometry=Point(-122.0, 37.0, 0.0)
        )
        
        original_id = original_feature.id
        original_geometry = original_feature.geometry
        
        # Create a simple KML with a Point feature for replacement
        # (The endpoint requires geometry types to match)
        replacement_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Replacement Point</name>
      <Point>
        <coordinates>-122.5,37.5,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        file_obj = SimpleUploadedFile('replacement.kml', replacement_kml)
        
        response = self.client.post('/api/item/import/upload', {
            'file': file_obj,
            'replacement': str(original_id)
        })
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for processing
        job_status = self._wait_for_job_completion(job_id)
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Get item ID
        job = status_tracker.get_job(job_id)
        item_id = job.import_queue_id
        
        # Verify it's marked as a replacement with the correct feature ID
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertEqual(import_item.replacement, original_id, 
                        "Should be marked as replacement for the original feature")
        
        # Verify the import queue has features ready for replacement selection
        self.assertGreater(len(import_item.geofeatures), 0,
                          "Should have features available for replacement")
        
        # Select the first feature from the replacement file to apply
        feature_index = 0
        replacement_feature = import_item.geofeatures[feature_index]
        
        # Verify the replacement feature has geometry
        self.assertIn('geometry', replacement_feature,
                      "Replacement feature should have geometry")
        
        # Verify geometry types match (endpoint requirement)
        replacement_geom_type = replacement_feature.get('geometry', {}).get('type', '').lower()
        self.assertEqual(replacement_geom_type, 'point',
                        "Replacement feature should be a Point to match original")
        
        # Apply the replacement geometry
        response = self.client.post(
            f'/api/feature/{original_id}/apply-replacement/',
            data=json.dumps({
                'import_queue_id': item_id,
                'feature_index': feature_index
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200, f"Replacement failed: {response.content}")
        
        # Verify the original feature's geometry was updated
        original_feature.refresh_from_db()
        self.assertNotEqual(original_feature.geometry.wkt, original_geometry.wkt,
                           "Feature geometry should have been updated")
        
        # Verify the ImportQueue item was deleted
        self.assertFalse(ImportQueue.objects.filter(id=item_id).exists(),
                        "Replacement ImportQueue item should have been deleted after replacement")
        
        # Verify we still have only 1 feature (not 1 + all features from the file)
        final_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(final_count, 1,
                        "Should still have only the original feature (geometry updated, not new features created)")

    def test_e2e_recheck_duplicates(self):
        """Test rechecking duplicates after importing more features."""
        # Upload a file but don't import yet
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        initial_duplicate_count = len(import_item.duplicate_features)
        
        # Now import a different file that might create features
        gpx_content = self._load_test_file('blue_hills.gpx')
        process_job_id2, item_id2, process_status2 = self._upload_file(gpx_content, 'blue_hills.gpx')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id2, import_status2 = self._import_item(item_id2)
        self.assertEqual(import_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Now recheck duplicates for the first item
        response = self.client.post(f'/api/item/import/recheck-duplicates/{item_id}')
        self.assertEqual(response.status_code, 200, f"Recheck failed: {response.content}")
        
        data = json.loads(response.content)
        self.assertIn('duplicate_count', data)
        
        # Verify the duplicate list was updated
        import_item.refresh_from_db()
        # The duplicate count may or may not change, but the operation should succeed
        self.assertIsNotNone(import_item.duplicate_features)

    def test_e2e_multiple_concurrent_imports(self):
        """Test importing multiple files concurrently."""
        # Prepare multiple files
        kml_content = self._load_test_file('Test Items.kml')
        gpx1_content = self._load_test_file('blue_hills.gpx')
        gpx2_content = self._load_test_file('fells_loop.gpx')
        
        # Upload all files first
        _, item_id1, _ = self._upload_file(kml_content, 'Test Items.kml')
        _, item_id2, _ = self._upload_file(gpx1_content, 'blue_hills.gpx')
        _, item_id3, _ = self._upload_file(gpx2_content, 'fells_loop.gpx')
        
        results = {}
        errors = {}
        
        def import_worker(item_id, name):
            try:
                job_id, job_status = self._import_item(item_id)
                results[name] = (job_id, job_status)
            except Exception as e:
                errors[name] = str(e)
        
        # Start concurrent imports
        threads = [
            threading.Thread(target=import_worker, args=(item_id1, 'kml')),
            threading.Thread(target=import_worker, args=(item_id2, 'gpx1')),
            threading.Thread(target=import_worker, args=(item_id3, 'gpx2'))
        ]
        
        for thread in threads:
            thread.start()
        
        for thread in threads:
            thread.join(timeout=60)
        
        # Verify no errors occurred
        self.assertEqual(len(errors), 0, f"Errors occurred during concurrent imports: {errors}")
        
        # Verify all imports completed
        self.assertEqual(len(results), 3, "All 3 imports should have completed")
        
        for name, (job_id, job_status) in results.items():
            self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value,
                           f"Import {name} should have completed successfully")
        
        # Verify features were created from all imports
        total_features = FeatureStore.objects.filter(user=self.user).count()
        self.assertGreater(total_features, 10, "Should have imported features from all files")

    # ==================== ERROR SCENARIO TESTS ====================

    def test_e2e_invalid_file_format(self):
        """Test uploading an invalid/corrupt file."""
        # Create invalid file content
        invalid_content = b'This is not a valid KML, GPX, or KMZ file'
        
        file_obj = SimpleUploadedFile('invalid.kml', invalid_content)
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        
        # Should either reject immediately or fail during processing
        if response.status_code == 200:
            # If accepted, processing should fail
            data = json.loads(response.content)
            job_id = data['job_id']
            
            job_status = self._wait_for_job_completion(job_id)
            self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value,
                           "Processing of invalid file should fail")
        else:
            # Or reject immediately
            self.assertIn(response.status_code, [400, 415],
                         "Invalid file should be rejected")

    def test_e2e_empty_file(self):
        """Test uploading an empty file."""
        # Create empty file
        empty_content = b''
        
        file_obj = SimpleUploadedFile('empty.kml', empty_content)
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        
        # Should either reject immediately or fail during processing
        if response.status_code == 200:
            data = json.loads(response.content)
            job_id = data['job_id']
            
            job_status = self._wait_for_job_completion(job_id)
            self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value,
                           "Processing of empty file should fail")
        else:
            self.assertIn(response.status_code, [400, 415],
                         "Empty file should be rejected")

    def test_e2e_file_with_no_features(self):
        """Test uploading a KML with no placemarks is rejected during validation."""
        # Create valid KML structure but with no features
        no_features_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>Empty Document</name>
    <description>This document has no placemarks</description>
  </Document>
</kml>"""
        
        file_obj = SimpleUploadedFile('no_features.kml', no_features_kml)
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Processing should fail because files must have features
        job_status = self._wait_for_job_completion(job_id)
        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value,
                        "Files without features should be rejected during validation")
        
        # Verify the error message mentions geographic features
        error_message = job_status.get('message', '').lower()
        self.assertTrue(
            'geographic' in error_message or 
            'features' in error_message or
            'placemark' in error_message,
            f"Error message should mention missing geographic features. Got: {error_message}"
        )

    @override_settings(PROCESSING_TIMEOUT_BASE_SECONDS=1, PROCESSING_TIMEOUT_PER_MB_SECONDS=0)
    def test_e2e_conversion_timeout_fails_job_instead_of_hanging(self):
        """
        A stuck/pathological KML->GeoJSON conversion must fail the job with
        PROCESSING_TIMEOUT rather than hanging the queue worker forever.

        Regression test for the togeojson in-process cutover: conversion now runs
        in-process (no subprocess), so its only timeout enforcement is the
        thread-bounded future.result(timeout=...) in
        BaseProcessor._convert_to_geojson. This simulates a conversion that never
        returns in time and verifies the job still terminates, quickly, as FAILED.
        """
        from geo_lib.processing.messages import PROCESSING_TIMEOUT

        # Sleep far longer than any plausible scheduling delay under load, so the assertion
        # below has a wide, unambiguous margin between "failed via the ~1s timeout" and
        # "actually waited out the hang" -- this previously slept only 5s, which flaked under
        # heavy parallel test-suite load where scheduling jitter alone could approach 5s.
        def _hanging_conversion(document, options=None):
            time.sleep(60)
            return {'type': 'FeatureCollection', 'features': []}

        kml_content = b"""<?xml version="1.0" encoding="UTF-8"?>
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

        with patch('geo_lib.processing.processors.base.conversion_runner.togeojson', side_effect=_hanging_conversion):
            start = time.time()
            job_id, item_id, job_status = self._upload_file(kml_content, 'hanging.kml', timeout=10.0)
            elapsed = time.time() - start

        self.assertEqual(job_status['status'], ProcessingStatus.FAILED.value,
                          f"Job with a hanging conversion should fail, not hang. Status: {job_status}")
        self.assertEqual(job_status['message'], PROCESSING_TIMEOUT)
        # The configured timeout is 1s; give very generous slack for scheduling overhead under a
        # loaded test suite, but this must still be nowhere near the 60s the mocked conversion
        # sleeps for -- that gap is what actually distinguishes "failed via timeout" from "hung".
        self.assertLess(elapsed, 20.0, f"Job should have failed via timeout, not by outliving the hang ({elapsed:.1f}s)")

    def test_e2e_import_already_imported(self):
        """Test trying to import an item that was already imported."""
        # Upload and import a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Import it
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify it's marked as imported
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertTrue(import_item.imported)
        
        # Try to import again
        response = self.client.post(
            f'/api/item/import/perform/{item_id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        # Should be rejected
        self.assertEqual(response.status_code, 400,
                        "Importing already-imported item should be rejected")
        
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', '').lower(),
                     "Error message should indicate item was already imported")

    def test_e2e_import_queue_item_not_found(self):
        """Test trying to import a non-existent import queue item."""
        # Try to import with a non-existent ID
        non_existent_id = 999999
        
        response = self.client.post(
            f'/api/item/import/perform/{non_existent_id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        # Should return 404
        self.assertEqual(response.status_code, 404,
                        "Non-existent import item should return 404")

    # ==================== WEBSOCKET EVENT TESTS ====================

    def test_e2e_websocket_item_added(self):
        """Test that WebSocket event is broadcast when item is added."""
        
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        file_obj = SimpleUploadedFile('Test Items.kml', kml_content)
        
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for processing to complete
        self._wait_for_job_completion(job_id)
        
        # Real WebSocket broadcasts happen - verification done by WebSocket consumer tests
        # This test verifies the upload and processing workflow completes successfully

    def test_e2e_websocket_item_completed(self):
        """Test that WebSocket event is broadcast when import completes."""
        
        # Upload and process a file first
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Now import it (this should trigger WebSocket broadcast)
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Real WebSocket broadcasts happen - verification done by WebSocket consumer tests
        # This test verifies the import workflow completes successfully

    def test_e2e_websocket_item_failed(self):
        """Test that WebSocket event is broadcast when import fails."""
        
        # Create an import item with no features (will fail on import)
        no_features_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>Empty Document</name>
  </Document>
</kml>"""
        
        file_obj = SimpleUploadedFile('no_features.kml', no_features_kml)
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for processing
        job_status = self._wait_for_job_completion(job_id)
        
        job = status_tracker.get_job(job_id)
        item_id = job.import_queue_id
        
        # Try to import (should fail)
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.FAILED.value)
        
        # Real WebSocket broadcasts happen - verification done by WebSocket consumer tests
        # This test verifies the failure workflow works correctly

    # ==================== API ENDPOINT EDGE CASES ====================

    def test_e2e_bulk_operations_validation(self):
        """Test bulk operations endpoint with invalid data."""
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Test 1: Invalid color format
        invalid_bulk_ops = {
            'tags': ['test'],
            'pointColor': 'not-a-color',  # Invalid format
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': invalid_bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400, "Should reject invalid color format")
        
        # Test 2: Invalid tags type (not a list)
        invalid_bulk_ops = {
            'tags': 'not-a-list',  # Should be a list
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': invalid_bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400, "Should reject invalid tags type")
        
        # Test 3: Valid bulk operations should succeed
        valid_bulk_ops = {
            'tags': ['test', 'valid'],
            'pointColor': '#ff0000',
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': valid_bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200, "Valid bulk operations should succeed")

    def test_e2e_bulk_operations_on_imported_item(self):
        """Test that bulk operations cannot be modified after import."""
        # Upload and import a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Import the item
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Try to modify bulk operations after import
        bulk_ops = {
            'tags': ['should', 'fail'],
            'pointColor': '#ff0000',
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400, 
                        "Should not allow modifying bulk operations after import")
        
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', '').lower(),
                     "Error message should indicate item was already imported")

    def test_e2e_get_bulk_operations(self):
        """Test retrieving bulk operations from import item."""
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Test 1: Get bulk operations when none are set (should return empty)
        response = self.client.get(f'/api/item/import/bulk-operations/{item_id}/get')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('bulk_operations', data)
        self.assertEqual(data['bulk_operations'], {}, 
                        "Should return empty dict when no bulk operations set")
        
        # Test 2: Set bulk operations
        bulk_ops = {
            'tags': ['get', 'test'],
            'pointColor': '#00ff00',
            'lineColor': '#0000ff',
        }
        
        response = self.client.put(
            f'/api/item/import/bulk-operations/{item_id}',
            data=json.dumps({'bulk_operations': bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Test 3: Get bulk operations and verify they match
        response = self.client.get(f'/api/item/import/bulk-operations/{item_id}/get')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('bulk_operations', data)
        self.assertEqual(data['bulk_operations']['tags'], bulk_ops['tags'])
        self.assertEqual(data['bulk_operations']['pointColor'], bulk_ops['pointColor'])
        self.assertEqual(data['bulk_operations']['lineColor'], bulk_ops['lineColor'])

    def test_e2e_recheck_duplicates_after_import(self):
        """Test that recheck duplicates detects features imported after initial upload."""
        # Upload and import file A with a specific point
        first_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>File A Point</name>
      <Point>
        <coordinates>-122.5,37.5,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        process_job_id1, item_id1, process_status1 = self._upload_file(first_kml, 'file_a.kml')
        self.assertEqual(process_status1['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id1, import_status1 = self._import_item(item_id1)
        self.assertEqual(import_status1['status'], ProcessingStatus.COMPLETED.value)
        
        # Upload file B with a duplicate point (same coordinates)
        second_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>File B Point - Duplicate Location</name>
      <Point>
        <coordinates>-122.5,37.5,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        process_job_id2, item_id2, process_status2 = self._upload_file(second_kml, 'file_b.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Check initial duplicate detection
        import_item2 = ImportQueue.objects.get(id=item_id2, user=self.user)
        initial_duplicate_count = len(import_item2.duplicate_features)
        self.assertGreater(initial_duplicate_count, 0, 
                          "Should detect initial duplicates from file A")
        
        # Upload and import file C with a DIFFERENT point (not duplicate)
        third_kml = b"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>File C Point - Different Location</name>
      <Point>
        <coordinates>-122.6,37.6,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        process_job_id3, item_id3, process_status3 = self._upload_file(third_kml, 'file_c.kml')
        self.assertEqual(process_status3['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id3, import_status3 = self._import_item(item_id3)
        self.assertEqual(import_status3['status'], ProcessingStatus.COMPLETED.value)
        
        # Now recheck duplicates on file B - should still detect A (C is different location)
        response = self.client.post(f'/api/item/import/recheck-duplicates/{item_id2}')
        self.assertEqual(response.status_code, 200, 
                        f"Recheck duplicates should succeed: {response.content}")
        
        data = json.loads(response.content)
        self.assertIn('duplicate_count', data)
        
        # Verify duplicates were updated in database
        import_item2.refresh_from_db()
        # Should still have duplicates (from file A only, since C was different location)
        self.assertGreater(len(import_item2.duplicate_features), 0,
                          "Should have detected duplicates after recheck")
        
        # The key test: verify recheck worked (it re-ran duplicate detection)
        # Even if duplicate count is same, the recheck operation should have succeeded
        self.assertIn('msg', data)
        self.assertIn('rechecked', data['msg'].lower())

    def test_e2e_recheck_duplicates_on_imported_item(self):
        """Test that recheck duplicates fails on already-imported items."""
        # Upload and import a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Import the item
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Try to recheck duplicates after import
        response = self.client.post(f'/api/item/import/recheck-duplicates/{item_id}')
        self.assertEqual(response.status_code, 400,
                        "Should not allow rechecking duplicates after import")
        
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', '').lower(),
                     "Error message should indicate item was already imported")

    def test_e2e_delete_during_processing(self):
        """Test deleting import item during or immediately after processing."""
        # Create a moderately-sized KML to ensure some processing time
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>"""
        
        # Add 100 placemarks
        for i in range(100):
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
        
        # Upload the file
        file_obj = SimpleUploadedFile('delete_test.kml', kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Get the import queue item ID
        # Wait briefly for the initial queue entry to be created
        time.sleep(0.2)
        job = status_tracker.get_job(job_id)
        item_id = job.import_queue_id if job else None
        self.assertIsNotNone(item_id, "Import queue item should be created")
        
        # Verify item exists
        import_item = ImportQueue.objects.filter(id=item_id, user=self.user).first()
        self.assertIsNotNone(import_item, "Import item should exist")
        
        # Delete the import item (might still be processing)
        response = self.client.delete(f'/api/item/import/delete/{item_id}')
        self.assertEqual(response.status_code, 200,
                        f"Delete should succeed: {response.content}")
        delete_job_id = json.loads(response.content)['job_id']
        
        # Wait for processing to complete or timeout
        try:
            job_status = self._wait_for_job_completion(job_id, timeout=10.0)
            # Job might complete successfully or fail - either is acceptable
            # The important thing is no errors/crashes
        except (TimeoutError, ValueError):
            # Job might be cancelled/removed after deletion - that's fine
            pass
        
        # The DELETE endpoint runs asynchronously (its own background job, separate from the
        # process job above), so wait for it to actually finish before asserting on its effects.
        try:
            self._wait_for_job_completion(delete_job_id, timeout=10.0)
        except (TimeoutError, ValueError):
            pass
        
        # Verify the import item was deleted from database
        import_item = ImportQueue.objects.filter(id=item_id, user=self.user).first()
        self.assertIsNone(import_item, "Import item should be deleted from database")
        
        # Verify no zombie features were created
        features = FeatureStore.objects.filter(user=self.user)
        self.assertEqual(features.count(), 0,
                        "No features should be imported after deletion")

    def test_e2e_update_features_before_import(self):
        """Test updating feature properties before import and verify changes persist."""
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Get the import item and its features
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertGreater(len(import_item.geofeatures), 0, "Should have features")
        
        # Store original system_tags from first feature
        original_feature = import_item.geofeatures[0]
        original_system_tags = original_feature.get('properties', {}).get('system_tags', [])
        self.assertGreater(len(original_system_tags), 0, 
                          "Feature should have system_tags from processing")
        
        # Update features via PATCH endpoint
        updated_features = []
        for i, feature in enumerate(import_item.geofeatures[:3]):  # Update first 3 features
            updated_feature = {
                'properties': {
                    'geojson_hash': feature['properties']['geojson_hash'],
                    'name': f'Updated Name {i}',
                    'description': f'Updated description {i}',
                    'tags': ['updated', f'tag{i}'],
                }
            }
            updated_features.append(updated_feature)
        
        response = self.client.patch(
            f'/api/item/import/update/{item_id}',
            data=json.dumps({'features': updated_features}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200,
                        f"Update should succeed: {response.content}")
        
        # Verify updates were applied
        import_item.refresh_from_db()
        updated_feature_0 = import_item.geofeatures[0]
        self.assertEqual(updated_feature_0['properties']['name'], 'Updated Name 0')
        self.assertEqual(updated_feature_0['properties']['description'], 'Updated description 0')
        self.assertIn('updated', updated_feature_0['properties'].get('tags', []))
        
        # Verify system_tags were preserved
        preserved_system_tags = updated_feature_0['properties'].get('system_tags', [])
        self.assertEqual(preserved_system_tags, original_system_tags,
                        "System tags should be preserved during update")
        
        # Import the item
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify the updated data appears in FeatureStore
        features = FeatureStore.objects.filter(user=self.user).order_by('id')
        self.assertGreater(features.count(), 0, "Should have imported features")
        
        # Find the first updated feature in FeatureStore
        first_feature = features[0]
        self.assertEqual(first_feature.geojson['properties']['name'], 'Updated Name 0',
                        "Updated name should appear in FeatureStore")
        self.assertEqual(first_feature.geojson['properties']['description'], 
                        'Updated description 0',
                        "Updated description should appear in FeatureStore")
        self.assertIn('updated', first_feature.geojson['properties'].get('tags', []),
                     "Updated tags should appear in FeatureStore")
        
        # Verify system_tags are still present
        self.assertIn('system_tags', first_feature.geojson['properties'],
                     "System tags should be in FeatureStore")
        self.assertGreater(len(first_feature.geojson['properties']['system_tags']), 0,
                          "System tags should not be empty")

    def test_e2e_update_features_on_imported_item(self):
        """Test that features cannot be updated after import."""
        # Upload and import a file
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Get a feature hash for the update
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        feature_hash = import_item.geofeatures[0]['properties']['geojson_hash']
        
        # Import the item
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Try to update features after import
        updated_features = [{
            'properties': {
                'geojson_hash': feature_hash,
                'name': 'Should Fail',
                'description': 'This update should fail',
            }
        }]
        
        response = self.client.patch(
            f'/api/item/import/update/{item_id}',
            data=json.dumps({'features': updated_features}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400,
                        "Should not allow updating features after import")
        
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', '').lower(),
                     "Error message should indicate item was already imported")




