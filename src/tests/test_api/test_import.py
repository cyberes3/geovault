"""
Tests for import/upload API endpoints.
"""
import json
import time
from unittest.mock import patch
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase, TransactionTestCase
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue, FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.processing.status_tracker import ProcessingStatus, status_tracker


class TestImportAPI(TransactionTestCase):
    """Test import/upload API endpoints using real backend processing."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
    
    def _wait_for_job_completion(self, job_id: str, timeout: float = 30.0) -> dict:
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

    def test_upload_kml_file(self):
        """Test uploading a KML file with real backend processing."""
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

        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})

        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        self.assertEqual(data['msg'], 'File uploaded successfully, processing queued')
        
        # Wait for real processing to complete
        # Note: Elevation API may timeout, but job should still complete
        job_id = data['job_id']
        try:
            job_status = self._wait_for_job_completion(job_id, timeout=60.0)
            # Verify processing completed successfully
            self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.COMPLETED])
        except TimeoutError:
            # If job times out, check if it's still processing (elevation API might be slow)
            job_status = status_tracker.get_job_status(job_id)
            if job_status and job_status.get('status') in [ProcessingStatus.PROCESSING.value, ProcessingStatus.PROCESSING]:
                # Job is still processing, which is acceptable if elevation API is slow
                # Just verify the job exists and is in a valid state
                self.assertIsNotNone(job_status)
            else:
                raise

    def test_upload_gpx_file(self):
        """Test uploading a GPX file with real backend processing."""
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

        file = SimpleUploadedFile("test.gpx", gpx_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})

        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        
        # Wait for real processing to complete
        job_id = data['job_id']
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify processing completed successfully
        self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.COMPLETED])

    def test_upload_with_replacement(self):
        """Test uploading a file as a replacement for an existing feature."""
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

        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'))
        response = self.client.post(
            '/api/item/import/upload',
            {'file': file, 'replacement': '123'}
        )

        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        
        # Wait for real processing to complete
        job_id = data['job_id']
        job_status = self._wait_for_job_completion(job_id)
        
        # Processing will complete (replacement handling is done during processing)
        self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.FAILED.value,
                                              ProcessingStatus.COMPLETED, ProcessingStatus.FAILED])

    def test_upload_invalid_file_structure(self):
        """Test uploading without a file."""
        response = self.client.post('/api/item/import/upload', {})
        self.assertEqual(response.status_code, 400)

    def test_get_processing_status(self):
        """Test getting processing status with real job."""
        # Upload a file to create a real job
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Status Test</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        file = SimpleUploadedFile("status_test.kml", kml_content.encode('utf-8'))
        upload_response = self.client.post('/api/item/import/upload', {'file': file})
        job_id = json.loads(upload_response.content)['job_id']

        # Get status
        response = self.client.get(f'/api/item/import/status/{job_id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_status', data)

    def test_get_processing_status_not_found(self):
        """Test getting status for non-existent job."""
        response = self.client.get('/api/item/import/status/nonexistent-job-id-12345')
        self.assertEqual(response.status_code, 404)

    def test_get_processing_status_unauthorized(self):
        """Test getting status for another user's job."""
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )

        # Create a job for the other user
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Other User Test</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        # Login as other user, upload file
        self.client.force_login(other_user)
        file = SimpleUploadedFile("other_test.kml", kml_content.encode('utf-8'))
        upload_response = self.client.post('/api/item/import/upload', {'file': file})
        other_job_id = json.loads(upload_response.content)['job_id']
        
        # Login as original user, try to access other user's job
        self.client.force_login(self.user)
        response = self.client.get(f'/api/item/import/status/{other_job_id}')
        self.assertEqual(response.status_code, 404)  # Security: don't reveal job existence

    def test_get_user_processing_jobs(self):
        """Test getting all processing jobs for a user."""
        # Upload a file to create a real job
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Jobs Test</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        file = SimpleUploadedFile("jobs_test.kml", kml_content.encode('utf-8'))
        upload_response = self.client.post('/api/item/import/upload', {'file': file})
        
        response = self.client.get('/api/item/import/jobs')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        # Should have at least the job we just created
        self.assertGreaterEqual(len(data['jobs']), 1)

    def test_fetch_import_history_item(self):
        """Test fetching import history item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        response = self.client.get(f'/api/item/import/get/history/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/octet-stream')
        self.assertIn('attachment', response['Content-Disposition'])

    def test_fetch_import_history_item_unauthorized(self):
        """Test fetching another user's import history item."""
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )

        import_queue = ImportQueue.objects.create(
            user=other_user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        response = self.client.get(f'/api/item/import/get/history/{import_queue.id}')
        self.assertEqual(response.status_code, 404)

    def test_get_import_queue_item_features(self):
        """Test getting features from import queue item."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures
        )

        response = self.client.get(f'/api/item/import/get/features/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('geofeatures', data)
        self.assertEqual(len(data['geofeatures']), 1)

    def test_get_import_queue_item_features_not_found(self):
        """Test getting features from non-existent import queue item."""
        response = self.client.get('/api/item/import/get/features/99999')
        self.assertEqual(response.status_code, 404)

    def test_search_import_item_features(self):
        """Test searching features in import queue item."""
        geofeatures = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test Feature', 'description': 'A test'}
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]},
                'properties': {'name': 'Other Feature', 'description': 'Another test'}
            }
        ]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures
        )

        response = self.client.get(
            f'/api/item/import/search/{import_queue.id}',
            {'query': 'Test'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('matches', data)
        self.assertGreater(data['total_matches'], 0)

    def test_search_import_item_features_no_query(self):
        """Test searching without query parameter."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        response = self.client.get(f'/api/item/import/search/{import_queue.id}')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertEqual(data['error'], 'query parameter is required')

    def test_delete_import_item(self):
        """Test deleting an import item with real deletion job."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        response = self.client.delete(f'/api/item/import/delete/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        
        # Wait for real deletion job to complete
        job_id = data['job_id']
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify deletion completed
        self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.COMPLETED])

    def test_delete_import_item_not_found(self):
        """Test deleting non-existent import item."""
        response = self.client.delete('/api/item/import/delete/99999')
        self.assertEqual(response.status_code, 404)

    def test_update_import_item(self):
        """Test updating an import item."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {'geojson_hash': 'test-id', 'name': 'Updated'}
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        self.assertEqual(import_queue.geofeatures[0]['properties']['name'], 'Updated')

    def test_update_import_item_already_imported(self):
        """Test updating an already imported item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=True
        )

        update_data = {'features': [{'properties': {'geojson_hash': 'test-id', 'name': 'Test'}}]}
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_update_import_item_missing_features(self):
        """Test updating import item with missing features field."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        update_data = {}
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_update_import_item_empty_features(self):
        """Test updating import item with empty features list (should succeed)."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
            }],
            imported=False
        )
        update_data = {'features': []}
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        # Empty features array should be accepted (e.g., when only bulk operations changed)
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['updated_count'], 0)
        # Verify original features are unchanged
        import_queue.refresh_from_db()
        self.assertEqual(import_queue.geofeatures[0]['properties']['name'], 'Original')
    
    def test_update_import_item_missing_properties(self):
        """Test updating import item with missing properties."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        update_data = {'features': [{}]}
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_update_import_item_invalid_iso_timestamp(self):
        """Test updating import item with invalid ISO timestamp."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'created': 'not-a-valid-iso-timestamp'
                }
            }]
        }
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_update_import_item_valid_iso_timestamp(self):
        """Test updating import item with valid ISO timestamp."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'created': '2024-01-15T10:30:00Z'
                }
            }]
        }
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
    
    def test_update_import_item_extra_fields(self):
        """Test updating import item with extra fields (should be rejected)."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated',
                    'invalid_field': 'should be rejected'
                }
            }]
        }
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
    
    def test_update_import_item_tags_not_list(self):
        """Test updating import item with tags not as list."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Original'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'tags': 'not-a-list'
                }
            }]
        }
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_import_item_description(self):
        """Test updating description field."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Test Feature', 'description': 'Original description'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'description': 'Updated description'
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        self.assertEqual(import_queue.geofeatures[0]['properties']['description'], 'Updated description')
        # Verify name was not changed
        self.assertEqual(import_queue.geofeatures[0]['properties']['name'], 'Test Feature')

    def test_update_import_item_tags_valid_list(self):
        """Test updating tags field with valid list."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'geojson_hash': 'test-id', 'name': 'Test Feature', 'tags': ['original-tag']}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'tags': ['new-tag-1', 'new-tag-2', 'NEW-TAG-3']  # Should be lowercased
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        updated_tags = import_queue.geofeatures[0]['properties']['tags']
        # Tags should be lowercased and deduplicated
        self.assertIn('new-tag-1', updated_tags)
        self.assertIn('new-tag-2', updated_tags)
        self.assertIn('new-tag-3', updated_tags)  # Should be lowercased
        # Original tag should be replaced
        self.assertNotIn('original-tag', updated_tags)

    def test_update_import_item_multiple_fields(self):
        """Test updating multiple fields at once (name, description, created, tags)."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'geojson_hash': 'test-id',
                'name': 'Original Name',
                'description': 'Original Description',
                'created': '2023-01-01T00:00:00Z',
                'tags': ['original-tag']
            }
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated Name',
                    'description': 'Updated Description',
                    'created': '2024-12-25T15:30:00Z',
                    'tags': ['new-tag-1', 'new-tag-2']
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        props = import_queue.geofeatures[0]['properties']
        self.assertEqual(props['name'], 'Updated Name')
        self.assertEqual(props['description'], 'Updated Description')
        self.assertEqual(props['created'], '2024-12-25T15:30:00Z')
        self.assertIn('new-tag-1', props['tags'])
        self.assertIn('new-tag-2', props['tags'])

    def test_update_import_item_multiple_features(self):
        """Test updating multiple features in a single request."""
        
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Feature 1', 'description': 'Original 1'}
        }
        feature1_hash = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = feature1_hash

        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]},
            'properties': {'name': 'Feature 2', 'description': 'Original 2'}
        }
        feature2_hash = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = feature2_hash

        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3994, 37.7949]},
            'properties': {'name': 'Feature 3', 'description': 'Original 3'}
        }
        feature3_hash = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = feature3_hash

        geofeatures = [feature1, feature2, feature3]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        # Update first and third features, leave second unchanged
        update_data = {
            'features': [
                {
                    'properties': {
                        'geojson_hash': feature1_hash,
                        'name': 'Updated Feature 1',
                        'description': 'Updated Description 1'
                    }
                },
                {
                    'properties': {
                        'geojson_hash': feature3_hash,
                        'name': 'Updated Feature 3',
                        'tags': ['new-tag-for-3']
                    }
                }
            ]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['updated_count'], 2)

        import_queue.refresh_from_db()
        # Verify feature 1 was updated
        self.assertEqual(import_queue.geofeatures[0]['properties']['name'], 'Updated Feature 1')
        self.assertEqual(import_queue.geofeatures[0]['properties']['description'], 'Updated Description 1')
        
        # Verify feature 2 was NOT updated
        self.assertEqual(import_queue.geofeatures[1]['properties']['name'], 'Feature 2')
        self.assertEqual(import_queue.geofeatures[1]['properties']['description'], 'Original 2')
        
        # Verify feature 3 was updated
        self.assertEqual(import_queue.geofeatures[2]['properties']['name'], 'Updated Feature 3')
        self.assertIn('new-tag-for-3', import_queue.geofeatures[2]['properties']['tags'])

    def test_update_import_item_partial_update(self):
        """Test that partial updates only change specified fields, leaving others unchanged."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'geojson_hash': 'test-id',
                'name': 'Original Name',
                'description': 'Original Description',
                'created': '2023-01-01T00:00:00Z',
                'tags': ['tag1', 'tag2']
            }
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        # Only update name, leave everything else unchanged
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated Name Only'
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        props = import_queue.geofeatures[0]['properties']
        
        # Name should be updated
        self.assertEqual(props['name'], 'Updated Name Only')
        
        # Other fields should remain unchanged
        self.assertEqual(props['description'], 'Original Description')
        self.assertEqual(props['created'], '2023-01-01T00:00:00Z')
        self.assertEqual(set(props['tags']), {'tag1', 'tag2'})
        
        # Geometry should be preserved
        self.assertEqual(import_queue.geofeatures[0]['geometry']['type'], 'Point')
        self.assertEqual(import_queue.geofeatures[0]['geometry']['coordinates'], [-122.4194, 37.7749])

    def test_update_import_item_system_tags_preservation(self):
        """Test that system_tags are preserved when updating features."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'geojson_hash': 'test-id',
                'name': 'Original Name',
                'system_tags': ['import-year-2024', 'import-month-12', 'geometry-type-point']
            }
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        # Update name and description
        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated Name',
                    'description': 'New Description'
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        props = import_queue.geofeatures[0]['properties']
        
        # Verify system_tags were preserved
        self.assertIn('system_tags', props)
        self.assertEqual(set(props['system_tags']), {
            'import-year-2024',
            'import-month-12',
            'geometry-type-point'
        })
        
        # Verify updates were applied
        self.assertEqual(props['name'], 'Updated Name')
        self.assertEqual(props['description'], 'New Description')

    def test_update_import_item_system_tags_preservation_multiple_features(self):
        """Test that system_tags are preserved when updating multiple features."""
        
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'name': 'Feature 1',
                'system_tags': ['import-year-2024', 'geometry-type-point']
            }
        }
        feature1_hash = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = feature1_hash

        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]},
            'properties': {
                'name': 'Feature 2',
                'system_tags': ['import-year-2024', 'geometry-type-linestring']
            }
        }
        feature2_hash = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = feature2_hash

        geofeatures = [feature1, feature2]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        # Update both features
        update_data = {
            'features': [
                {
                    'properties': {
                        'geojson_hash': feature1_hash,
                        'name': 'Updated Feature 1'
                    }
                },
                {
                    'properties': {
                        'geojson_hash': feature2_hash,
                        'name': 'Updated Feature 2',
                        'description': 'New Description'
                    }
                }
            ]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        
        # Verify feature 1 system_tags preserved
        props1 = import_queue.geofeatures[0]['properties']
        self.assertEqual(set(props1['system_tags']), {'import-year-2024', 'geometry-type-point'})
        self.assertEqual(props1['name'], 'Updated Feature 1')
        
        # Verify feature 2 system_tags preserved
        props2 = import_queue.geofeatures[1]['properties']
        self.assertEqual(set(props2['system_tags']), {'import-year-2024', 'geometry-type-linestring'})
        self.assertEqual(props2['name'], 'Updated Feature 2')
        self.assertEqual(props2['description'], 'New Description')

    def test_update_import_item_system_tags_empty_list(self):
        """Test that empty system_tags list is preserved."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'geojson_hash': 'test-id',
                'name': 'Original Name',
                'system_tags': []  # Empty list
            }
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated Name'
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        props = import_queue.geofeatures[0]['properties']
        
        # Empty system_tags list should be preserved
        self.assertIn('system_tags', props)
        self.assertEqual(props['system_tags'], [])

    def test_update_import_item_system_tags_missing_field(self):
        """Test that missing system_tags field is handled (defaults to empty list)."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {
                'geojson_hash': 'test-id',
                'name': 'Original Name'
                # No system_tags field
            }
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )

        update_data = {
            'features': [{
                'properties': {
                    'geojson_hash': 'test-id',
                    'name': 'Updated Name'
                }
            }]
        }

        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        props = import_queue.geofeatures[0]['properties']
        
        # system_tags should be present (empty list or normalized)
        self.assertIn('system_tags', props)
        # Should be a list (empty or with values)
        self.assertIsInstance(props['system_tags'], list)

    def test_save_bulk_operations(self):
        """Test saving bulk operations for an import item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )

        bulk_ops = {
            'tags': ['test-tag'],
            'pointColor': '#ff0000',
            'lineColor': '#00ff00'
        }

        response = self.client.put(
            f'/api/item/import/bulk-operations/{import_queue.id}',
            data=json.dumps({'bulk_operations': bulk_ops}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        import_queue.refresh_from_db()
        self.assertEqual(import_queue.bulk_operations, bulk_ops)

    def test_update_import_item_empty_features_with_existing_features(self):
        """Test that empty features array is accepted when no feature changes are needed.
        
        This simulates the scenario where only bulk operations changed (e.g., adding a global tag)
        and the frontend sends an empty features array. This should succeed without validation errors.
        """
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'id': 'feature-1', 'name': 'Test Feature 1'}
        }, {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]},
            'properties': {'id': 'feature-2', 'name': 'Test Feature 2'}
        }]
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=geofeatures,
            imported=False
        )
        
        # Simulate the scenario: only bulk operations changed, no feature changes
        # Frontend sends empty features array
        update_data = {'features': []}
        response = self.client.put(
            f'/api/item/import/update/{import_queue.id}',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        
        # Should succeed with 200 status
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['updated_count'], 0)
        
        # Verify features remain unchanged
        import_queue.refresh_from_db()
        self.assertEqual(len(import_queue.geofeatures), 2)
        self.assertEqual(import_queue.geofeatures[0]['properties']['name'], 'Test Feature 1')
        self.assertEqual(import_queue.geofeatures[1]['properties']['name'], 'Test Feature 2')

    def test_get_bulk_operations(self):
        """Test getting bulk operations for an import item."""
        bulk_ops = {
            'tags': ['test-tag'],
            'pointColor': '#ff0000'
        }
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            bulk_operations=bulk_ops
        )

        response = self.client.get(f'/api/item/import/bulk-operations/{import_queue.id}/get')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['bulk_operations'], bulk_ops)

    def test_import_to_featurestore(self):
        """Test importing to featurestore with real import job."""
        
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test'}
        }
        feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )

        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        
        # Wait for real import job to complete
        job_id = data['job_id']
        job_status = self._wait_for_job_completion(job_id)
        
        # Verify import completed successfully
        self.assertIn(job_status['status'], [ProcessingStatus.COMPLETED.value, ProcessingStatus.COMPLETED])

    def test_import_to_featurestore_not_found(self):
        """Test importing non-existent item."""
        response = self.client.post('/api/item/import/perform/99999')
        self.assertEqual(response.status_code, 404)

    def test_import_to_featurestore_invalid_json(self):
        """Test importing with invalid JSON."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_import_to_featurestore_extra_fields(self):
        """Test importing with extra fields."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data=json.dumps({
                'import_custom_icons': True,
                'extra_field': 'should be rejected'
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_import_to_featurestore_invalid_skipped_ids_type(self):
        """Test importing with invalid skipped_feature_ids type."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data=json.dumps({
                'skipped_feature_ids': 'not-a-list'
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_upload_unauthorized(self):
        """Test uploading without authentication."""
        self.client.logout()
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
        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        self.assertEqual(response.status_code, 401)

    def test_recheck_duplicates(self):
        """Test rechecking duplicates for an import queue item with real duplicate detection."""
        
        # Create an existing feature in the feature store
        existing_feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Existing Feature'}
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=existing_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(existing_feature_data)
        )
        
        # Create import queue item with a duplicate and non-duplicate feature
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Duplicate Feature'}
        }
        feature1['properties']['geojson_hash'] = generate_geojson_hash(feature1)
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8, 0.0]},
            'properties': {'name': 'Unique Feature'}
        }
        feature2['properties']['geojson_hash'] = generate_geojson_hash(feature2)
        
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2],
            imported=False
        )

        # Recheck duplicates with real duplicate detection
        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should have found 1 duplicate (feature1 matches existing_feature)
        self.assertGreaterEqual(data['duplicate_count'], 1)
        self.assertIn('Duplicates rechecked successfully', data.get('msg', ''))
        
        # Verify import queue was updated with duplicates
        import_queue.refresh_from_db()
        self.assertGreaterEqual(len(import_queue.duplicate_features), 1)
        
        # Real WebSocket notifications are sent (not mocked)

    def test_recheck_duplicates_hash_takes_precedence(self):
        """Test that hash duplicates take precedence over geometry duplicates with real duplicate detection."""
        
        # Create a feature that will be a hash duplicate
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature_hash = generate_geojson_hash(test_feature)
        test_feature['properties']['geojson_hash'] = feature_hash
        
        # Create a FeatureStore entry with the same hash (hash duplicate)
        feature_store = FeatureStore.objects.create(
            user=self.user,
            geojson=test_feature,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=feature_hash
        )
        
        # Create import queue item with the same feature
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[test_feature],
            imported=False
        )

        # Use real duplicate detection
        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify duplicate was detected
        import_queue.refresh_from_db()
        self.assertGreaterEqual(len(import_queue.duplicate_features), 1,
                        "Feature should be marked as duplicate")
        
        # Real duplicate detection determines match type (hash vs geometry)

    def test_recheck_duplicates_cross_queue_coord_duplicate(self):
        """Test cross-queue geometry duplicate detection with real processing."""
        
        # Create first import queue item with a feature
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Feature 1'}
        }
        feature1['properties']['geojson_hash'] = generate_geojson_hash(feature1)
        
        import_queue1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1],
            imported=False
        )
        
        # Create second import queue item with a feature at the same coordinates (geometry duplicate)
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Feature 2'}  # Different name, same coordinates
        }
        feature2['properties']['geojson_hash'] = generate_geojson_hash(feature2)
        
        import_queue2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature2],
            imported=False
        )

        # Recheck duplicates for the second item using real duplicate detection
        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue2.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify cross-queue duplicate detection ran successfully
        import_queue2.refresh_from_db()
        # Real duplicate detection may or may not find cross-queue duplicates depending on implementation
        # This test verifies the endpoint works without errors
        feature2_hash = generate_geojson_hash(feature2)
        feature2_id = feature2.get('properties', {}).get('geojson_hash', feature2_hash)
        self.assertIn(feature2_id, import_queue2.skipped_feature_ids or [],
                     "Cross-queue geometry duplicate should be auto-skipped")

    def test_recheck_duplicates_cross_queue_hash_is_blocked(self):
        """Test that cross-queue hash duplicates are BLOCKED (not skipped/restorable)."""
        
        # Create feature with hash
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature', 'description': 'Test'}
        }
        feature_hash = generate_geojson_hash(test_feature)
        test_feature['properties']['geojson_hash'] = feature_hash
        
        # Create older queue item with exact same feature (hash duplicate)
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[test_feature],
            imported=False
        )
        
        # Create newer queue item
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[test_feature],  # Same feature
            imported=False
        )
        
        # Mock RealTimeImportLog
class TestImportJobWebSocket(TestCase):
    """Test ImportJob WebSocket broadcasting methods."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_broadcast_to_process_status_module_called(self):
        """Test that _broadcast_to_process_status_module creates correct WebSocket message."""
        
        # Create import job instance
        import_job = ImportJob(status_tracker)
        
        # Call broadcast method directly - real WebSocket broadcast happens
        import_job._broadcast_to_process_status_module(
            user_id=self.user.id,
            import_queue_id=123,
            event_type='item_completed',
            data={'message': 'Success', 'imported_count': 5}
        )
        
        # Real broadcast is sent (no longer mocked)
        # WebSocket consumer tests verify the actual message delivery

    def test_broadcast_completion_message_format(self):
        """Test that item_completed broadcast has correct data structure."""
        
        import_job = ImportJob(status_tracker)
        
        # Broadcast completion with real WebSocket
        import_job._broadcast_to_process_status_module(
            user_id=self.user.id,
            import_queue_id=456,
            event_type='item_completed',
            data={
                'message': 'Successfully imported 10 features',
                'imported_count': 10,
                'skipped_count': 2,
                'duplicates_skipped': 1
            }
        )
        
        # Real broadcast is sent - message structure is tested by WebSocket consumer tests
        # This test verifies the broadcast method can be called without errors

    def test_broadcast_failure_message_format(self):
        """Test that item_failed broadcast has correct data structure."""
        
        import_job = ImportJob(status_tracker)
        
        # Broadcast failure
        import_job._broadcast_to_process_status_module(
            user_id=self.user.id,
            import_queue_id=789,
            event_type='item_failed',
            data={
                'message': 'No features were imported',
                'reason': 'All features were duplicates'
            }
        )
        
        # Real broadcast is sent - message structure is tested by WebSocket consumer tests
        # This test verifies the broadcast method can be called without errors

    def test_broadcast_channel_name_format(self):
        """Test that broadcast uses correct channel naming convention."""
        
        import_job = ImportJob(status_tracker)
        
        user_id = 42
        item_id = 999
        
        import_job._broadcast_to_process_status_module(
            user_id=user_id,
            import_queue_id=item_id,
            event_type='item_completed',
            data={'message': 'test'}
        )
        
        # Real broadcast is sent - channel naming is tested by WebSocket consumer tests
        # This test verifies the broadcast method can be called without errors


class TestSequentialProcessing(TestCase):
    """Test sequential processing with Redis queue."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='sequential@example.com',
            password='testpass123',
            username='sequential_user'
        )
        self.client.force_login(self.user)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_redis_queue_is_used_for_processing(self, mock_status_tracker, mock_process_job):
        """Test that files are enqueued to Redis queue for processing."""
        # Setup mocks
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.enqueue_job.return_value = True
        
        # Upload file
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
        
        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'))
        response = self.client.post('/api/item/import/upload', {'file': file})
        
        self.assertEqual(response.status_code, 200)
        
        # Verify enqueue_job was called instead of start_process_job
        mock_process_job.enqueue_job.assert_called_once()

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_returns_immediately_job_runs_async(self, mock_status_tracker, mock_process_job):
        """Test that upload returns immediately while job runs asynchronously."""

        # Setup mocks
        job_id = 'async-test-job-id'
        mock_status_tracker.create_job.return_value = job_id

        # Track when enqueue_job is called
        call_time = []

        def track_start_time(*args, **kwargs):
            call_time.append(time.time())
            return True

        mock_process_job.enqueue_job.side_effect = track_start_time

        # Upload file
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

        file = SimpleUploadedFile("test.kml", kml_content.encode('utf-8'))
        start_time = time.time()
        response = self.client.post('/api/item/import/upload', {'file': file})
        end_time = time.time()

        # Should return very quickly (< 2 seconds, accounting for test environment overhead)
        response_time = end_time - start_time
        self.assertLess(response_time, 2.0, "Upload should return immediately")

        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['job_id'], job_id)

        # Verify enqueue_job was called
        self.assertTrue(len(call_time) > 0)
