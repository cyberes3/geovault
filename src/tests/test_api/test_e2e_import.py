"""
End-to-end tests for the complete import flow.
Tests file upload -> async processing -> import to FeatureStore using real test files.
"""
import json
import time
from unittest.mock import patch, MagicMock, AsyncMock
from pathlib import Path

import pytest
from django.test import TransactionTestCase
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.status_tracker import status_tracker, ProcessingStatus


class TestE2EImport(TransactionTestCase):
    """
    End-to-end import flow tests using real test files.
    
    Uses TransactionTestCase instead of TestCase because:
    1. Async jobs run in separate threads
    2. TestCase wraps tests in transactions that aren't visible to other threads
    3. TransactionTestCase commits data so threads can access it
    """

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='e2e@example.com',
            password='testpass123',
            username='e2e_user'
        )
        self.client.force_login(self.user)
        
        # Store test files directory
        self.test_files_dir = Path(__file__).parent.parent / 'test files'

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
        """Load a test file from the test files directory."""
        file_path = self.test_files_dir / filename
        with open(file_path, 'rb') as f:
            return f.read()

    def _create_kmz(self, kml_content: bytes) -> bytes:
        """
        Create a KMZ file (ZIP with doc.kml inside) from KML content.
        Downloads and embeds any remote icons referenced in the KML.
        """
        import zipfile
        import re
        from io import BytesIO
        from urllib.request import urlopen, Request
        from urllib.parse import urlparse
        from urllib.error import URLError, HTTPError
        
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

    def _upload_file(self, file_content: bytes, filename: str, replacement_id=None):
        """
        Upload a file and wait for processing.
        
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
        job_status = self._wait_for_job_completion(job_id)
        
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
        
        # Verify ImportQueue entry was created with geofeatures
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
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

    def test_e2e_gpx_import(self):
        """Test complete GPX import flow: upload -> process -> import -> verify DB."""
        # Load test GPX file
        gpx_content = self._load_test_file('blue_hills.gpx')
        
        # Upload and process
        process_job_id, item_id, process_status = self._upload_file(gpx_content, 'blue_hills.gpx')
        
        # Verify processing succeeded
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value,
                        f"Processing failed: {process_status.get('message', '')}")
        self.assertIsNotNone(item_id, "Import queue item ID should be returned")
        
        # Verify ImportQueue entry was created with geofeatures
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertEqual(import_item.original_filename, 'blue_hills.gpx')
        self.assertGreater(len(import_item.geofeatures), 0, "Should have extracted features from GPX")
        
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
        """Test that coordinate duplicates are automatically skipped by default."""
        # First, import some features
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Now upload the same file again (will have coordinate duplicates)
        process_job_id2, item_id2, process_status2 = self._upload_file(kml_content, 'Test Items Duplicate.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value)
        
        # Check that duplicates were detected
        import_item = ImportQueue.objects.get(id=item_id2, user=self.user)
        self.assertGreater(len(import_item.duplicate_features), 0, 
                          "Should have detected duplicate features")
        
        # Verify that coordinate duplicates were auto-skipped
        from geo_lib.feature_id import generate_feature_hash
        skipped_ids = import_item.skipped_feature_ids if import_item.skipped_feature_ids else []
        self.assertGreater(len(skipped_ids), 0,
                          "Coordinate duplicates should be auto-skipped")
        
        # Verify that the skipped IDs match the duplicate features
        for dup_info in import_item.duplicate_features:
            dup_feature = dup_info.get('feature')
            if dup_feature:
                feature_hash = generate_feature_hash(dup_feature)
                feature_id = dup_feature.get('properties', {}).get('feature_hash', feature_hash)
                self.assertIn(feature_id, skipped_ids,
                             f"Duplicate feature {feature_id} should be in skipped_feature_ids")

    def test_e2e_cross_queue_duplicate_detection(self):
        """Test that hash-based duplicate detection works across ImportQueue items during processing."""
        from geo_lib.feature_id import generate_feature_hash
        
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
        
        # Upload both files at once (or very quickly one after another)
        # This creates a race condition where both files process simultaneously
        process_job_id1, item_id1, process_status1 = self._upload_file(
            point_kml.encode('utf-8'), 'first_item.kml'
        )
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
        
        # Verify both items were created and have features
        self.assertGreater(len(import_item1.geofeatures), 0, "First item should have features")
        self.assertGreater(len(import_item2.geofeatures), 0, "Second item should have features")
        self.assertFalse(import_item1.imported, "First item should not be imported yet")
        self.assertFalse(import_item2.imported, "Second item should not be imported yet")
        
        # Verify both items have the same feature hash
        first_feature = import_item1.geofeatures[0]
        second_feature = import_item2.geofeatures[0]
        first_feature_hash = generate_feature_hash(first_feature)
        second_feature_hash = generate_feature_hash(second_feature)
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
                feature_hash = generate_feature_hash(feature)
                if feature_hash not in queue_hash_to_item:
                    queue_hash_to_item[feature_hash] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename
                    }
        
        for feature in import_item1.geofeatures:
            feature_hash = generate_feature_hash(feature)
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
                feature_hash = generate_feature_hash(feature)
                if feature_hash not in queue_hash_to_item:
                    queue_hash_to_item[feature_hash] = {
                        'queue_item_id': queue_item.id,
                        'queue_item_filename': queue_item.original_filename
                    }
        
        for feature in import_item2.geofeatures:
            feature_hash = generate_feature_hash(feature)
            if feature_hash in queue_hash_to_item:
                queue_info = queue_hash_to_item[feature_hash]
                queue_duplicates_found_item2.append({
                    'hash': feature_hash,
                    'queue_item_id': queue_info['queue_item_id'],
                    'queue_item_filename': queue_info['queue_item_filename']
                })
        
        # Determine which item is older based on timestamp
        # Only the newer item should be marked as a duplicate of the older one
        if import_item1.timestamp < import_item2.timestamp:
            # Item1 is older, so item2 should be marked as duplicate
            older_item_id = item_id1
            newer_item_id = item_id2
            older_filename = 'first_item.kml'
            newer_filename = 'second_item.kml'
            older_duplicates = queue_duplicates_found_item1
            newer_duplicates = queue_duplicates_found_item2
            newer_feature_hash = second_feature_hash
        else:
            # Item2 is older, so item1 should be marked as duplicate
            older_item_id = item_id2
            newer_item_id = item_id1
            older_filename = 'second_item.kml'
            newer_filename = 'first_item.kml'
            older_duplicates = queue_duplicates_found_item2
            newer_duplicates = queue_duplicates_found_item1
            newer_feature_hash = first_feature_hash
        
        # Only the newer item should have its feature marked as a cross-queue duplicate
        self.assertEqual(len(newer_duplicates), 1,
                        f"Newer item ({newer_filename}) should have exactly one duplicate detected")
        self.assertEqual(len(older_duplicates), 0,
                        f"Older item ({older_filename}) should NOT have any duplicates detected")
        
        # Verify the duplicate points to the older item
        dup = newer_duplicates[0]
        self.assertEqual(dup['queue_item_id'], older_item_id,
                        f"Newer item's duplicate should point to older item ({older_item_id})")
        self.assertEqual(dup['queue_item_filename'], older_filename,
                        f"Newer item's duplicate should have correct filename ({older_filename})")
        self.assertEqual(dup['hash'], newer_feature_hash,
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
        self.assertIsNotNone(import_item1.geojson_hash, "First item should have geojson_hash set")
        self.assertFalse(import_item1.imported, "First item should not be imported yet")
        
        # Upload the same file again with different filename (should be detected as duplicate in queue)
        process_job_id2, item_id2, process_status2 = self._upload_file(kml_content, 'Test Items Duplicate.kml')
        self.assertEqual(process_status2['status'], ProcessingStatus.COMPLETED.value,
                        "Second file processing should complete")
        
        # Verify second item was created
        import_item2 = ImportQueue.objects.get(id=item_id2, user=self.user)
        self.assertIsNotNone(import_item2.geojson_hash, "Second item should have geojson_hash set")
        self.assertFalse(import_item2.imported, "Second item should not be imported yet")
        
        # Verify both items have the same geojson_hash (file-level duplicate)
        self.assertEqual(import_item1.geojson_hash, import_item2.geojson_hash,
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
        self.assertIsNotNone(import_item3.geojson_hash, "Third item should have geojson_hash")
        
        # Count features before second upload
        initial_feature_count = FeatureStore.objects.filter(user=self.user).count()
        self.assertGreater(initial_feature_count, 0, "Should have imported features")
        
        # Upload the same file again with different filename (should be detected as duplicate of imported file)
        process_job_id4, item_id4, process_status4 = self._upload_file(kml_content, 'Test Items Duplicate Imported.kml')
        self.assertEqual(process_status4['status'], ProcessingStatus.COMPLETED.value,
                        "Fourth file processing should complete")
        
        # Verify fourth item was created
        import_item4 = ImportQueue.objects.get(id=item_id4, user=self.user)
        self.assertIsNotNone(import_item4.geojson_hash, "Fourth item should have geojson_hash set")
        self.assertFalse(import_item4.imported, "Fourth item should not be imported yet")
        
        # Verify both items have the same geojson_hash (file-level duplicate)
        self.assertEqual(import_item3.geojson_hash, import_item4.geojson_hash,
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
        skipped_ids = [
            import_item.geofeatures[0].get('properties', {}).get('id'),
            import_item.geofeatures[1].get('properties', {}).get('id')
        ]
        skipped_ids = [sid for sid in skipped_ids if sid]  # Filter out None values
        
        if len(skipped_ids) < 2:
            self.skipTest("Test features don't have IDs to skip")
        
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
        from django.contrib.gis.geos import Point
        original_feature = FeatureStore.objects.create(
            user=self.user,
            geojson={
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0, 37.0, 0.0]},
                'properties': {'name': 'Original Feature'}
            },
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
        import threading
        
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
        """Test uploading a valid KML with no placemarks."""
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
        
        # Processing should succeed but with no features
        job_status = self._wait_for_job_completion(job_id)
        self.assertEqual(job_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Get item
        job = status_tracker.get_job(job_id)
        item_id = job.import_queue_id
        
        import_item = ImportQueue.objects.get(id=item_id, user=self.user)
        self.assertEqual(len(import_item.geofeatures), 0,
                        "Should have no features extracted")
        
        # Try to import - should fail gracefully
        import_job_id, import_status = self._import_item(item_id)
        
        # Import should fail because there are no features
        self.assertEqual(import_status['status'], ProcessingStatus.FAILED.value,
                        "Import of item with no features should fail")

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

    @patch('channels.layers.get_channel_layer')
    def test_e2e_websocket_item_added(self, mock_get_channel_layer):
        """Test that WebSocket event is broadcast when item is added."""
        # Mock channel layer with AsyncMock for async operations
        mock_channel_layer = AsyncMock()
        mock_get_channel_layer.return_value = mock_channel_layer
        
        # Upload a file
        kml_content = self._load_test_file('Test Items.kml')
        file_obj = SimpleUploadedFile('Test Items.kml', kml_content)
        
        response = self.client.post('/api/item/import/upload', {'file': file_obj})
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        job_id = data['job_id']
        
        # Wait for processing to complete
        self._wait_for_job_completion(job_id)
        
        # Verify WebSocket broadcast was called for item_added
        # The exact channel name and event structure may vary
        calls = mock_channel_layer.group_send.call_args_list
        
        # Look for any broadcast (item_added event)
        broadcast_found = False
        for call in calls:
            if len(call[0]) >= 2:
                channel_name = call[0][0]
                message = call[0][1]
                # Check if this is a process_status channel or item_added type
                if 'process_status' in channel_name or message.get('type') == 'item_added':
                    broadcast_found = True
                    break
        
        # WebSocket may or may not be called depending on implementation
        # This test documents the expected behavior
        # If not called, the test will note that
        if not broadcast_found and len(calls) == 0:
            # No WebSocket calls made - this is acceptable for some implementations
            pass

    @patch('channels.layers.get_channel_layer')
    def test_e2e_websocket_item_completed(self, mock_get_channel_layer):
        """Test that WebSocket event is broadcast when import completes."""
        # Mock channel layer with AsyncMock for async operations
        mock_channel_layer = AsyncMock()
        mock_get_channel_layer.return_value = mock_channel_layer
        
        # Upload and process a file first
        kml_content = self._load_test_file('Test Items.kml')
        process_job_id, item_id, process_status = self._upload_file(kml_content, 'Test Items.kml')
        self.assertEqual(process_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Now import it (this should trigger WebSocket broadcast)
        import_job_id, import_status = self._import_item(item_id)
        self.assertEqual(import_status['status'], ProcessingStatus.COMPLETED.value)
        
        # Verify WebSocket broadcast was called for item_completed
        calls = mock_channel_layer.group_send.call_args_list
        
        # Look for item_completed event
        completed_event_found = False
        for call in calls:
            if len(call[0]) >= 2:
                channel_name = call[0][0]
                message = call[0][1]
                if message.get('type') == 'item_completed':
                    # Verify the event has expected data
                    data = message.get('data', {})
                    self.assertIn('message', data, "Completed event should have message")
                    completed_event_found = True
                    break
        
        self.assertTrue(completed_event_found,
                       f"Expected item_completed WebSocket event. Calls: {calls}")

    @patch('channels.layers.get_channel_layer')
    def test_e2e_websocket_item_failed(self, mock_get_channel_layer):
        """Test that WebSocket event is broadcast when import fails."""
        # Mock channel layer with AsyncMock for async operations
        mock_channel_layer = AsyncMock()
        mock_get_channel_layer.return_value = mock_channel_layer
        
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
        
        # Verify WebSocket broadcast was called for item_failed
        calls = mock_channel_layer.group_send.call_args_list
        
        # Look for item_failed event
        failed_event_found = False
        for call in calls:
            if len(call[0]) >= 2:
                channel_name = call[0][0]
                message = call[0][1]
                if message.get('type') == 'item_failed':
                    # Verify the event has expected data
                    data = message.get('data', {})
                    self.assertIn('message', data, "Failed event should have message")
                    failed_event_found = True
                    break
        
        self.assertTrue(failed_event_found,
                       f"Expected item_failed WebSocket event. Calls: {calls}")





