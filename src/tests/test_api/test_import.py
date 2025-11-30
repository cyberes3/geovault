"""
Tests for import/upload API endpoints.
"""
import json
from unittest.mock import patch, MagicMock, call, ANY
import pytest
from django.test import TestCase
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.status_tracker import ProcessingStatus, JobType, status_tracker


class TestImportAPI(TestCase):
    """Test import/upload API endpoints."""

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
    def test_upload_kml_file(self, mock_status_tracker, mock_process_job):
        """Test uploading a KML file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

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
        self.assertEqual(data['msg'], 'File uploaded successfully, processing started')
        mock_status_tracker.create_job.assert_called_once()
        mock_process_job.start_process_job.assert_called_once()

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_gpx_file(self, mock_status_tracker, mock_process_job):
        """Test uploading a GPX file."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

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

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_with_replacement(self, mock_status_tracker, mock_process_job):
        """Test uploading a file as a replacement for an existing feature."""
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True

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
        # Verify replacement_feature_id was passed
        call_args = mock_process_job.start_process_job.call_args
        self.assertEqual(call_args[1]['replacement_feature_id'], 123)

    def test_upload_invalid_file_structure(self):
        """Test uploading without a file."""
        response = self.client.post('/api/item/import/upload', {})
        self.assertEqual(response.status_code, 400)

    @patch('api.views.import_item.status_tracker')
    def test_get_processing_status(self, mock_status_tracker):
        """Test getting processing status."""
        mock_job = MagicMock()
        mock_job.user_id = self.user.id
        mock_status_tracker.get_job.return_value = mock_job
        mock_status_tracker.get_job_status.return_value = {
            'status': 'processing',
            'progress': 50.0,
            'message': 'Processing file...'
        }

        response = self.client.get('/api/item/import/status/test-job-id')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_status', data)

    @patch('api.views.import_item.status_tracker')
    def test_get_processing_status_not_found(self, mock_status_tracker):
        """Test getting status for non-existent job."""
        mock_status_tracker.get_job_status.return_value = None

        response = self.client.get('/api/item/import/status/nonexistent')
        self.assertEqual(response.status_code, 404)

    @patch('api.views.import_item.status_tracker')
    def test_get_processing_status_unauthorized(self, mock_status_tracker):
        """Test getting status for another user's job."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )

        mock_job = MagicMock()
        mock_job.user_id = other_user.id
        mock_status_tracker.get_job.return_value = mock_job
        mock_status_tracker.get_job_status.return_value = {'status': 'processing'}

        response = self.client.get('/api/item/import/status/test-job-id')
        self.assertEqual(response.status_code, 404)  # Security: don't reveal job existence

    @patch('api.views.import_item.status_tracker')
    def test_get_user_processing_jobs(self, mock_status_tracker):
        """Test getting all processing jobs for a user."""
        mock_job = MagicMock()
        mock_job.job_id = 'job-1'
        mock_status_tracker.get_user_jobs.return_value = [mock_job]
        mock_status_tracker.get_job_status.return_value = {
            'status': 'completed',
            'job_id': 'job-1'
        }

        response = self.client.get('/api/item/import/jobs')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('jobs', data)
        self.assertEqual(len(data['jobs']), 1)

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
        from django.contrib.auth import get_user_model
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

    @patch('api.views.import_item.delete_job')
    def test_delete_import_item(self, mock_delete_job):
        """Test deleting an import item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        mock_delete_job.start_delete_job.return_value = 'delete-job-id'

        response = self.client.delete(f'/api/item/import/delete/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)

    def test_delete_import_item_not_found(self):
        """Test deleting non-existent import item."""
        response = self.client.delete('/api/item/import/delete/99999')
        self.assertEqual(response.status_code, 404)

    def test_update_import_item(self):
        """Test updating an import item."""
        geofeatures = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'id': 'test-id', 'name': 'Original'}
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
                'properties': {'id': 'test-id', 'name': 'Updated'}
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

        update_data = {'features': [{'properties': {'id': 'test-id', 'name': 'Test'}}]}
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
                'properties': {'id': 'test-id', 'name': 'Original'}
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
            'properties': {'id': 'test-id', 'name': 'Original'}
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
                    'id': 'test-id',
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
            'properties': {'id': 'test-id', 'name': 'Original'}
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
                    'id': 'test-id',
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
            'properties': {'id': 'test-id', 'name': 'Original'}
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
                    'id': 'test-id',
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
            'properties': {'id': 'test-id', 'name': 'Original'}
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
                    'id': 'test-id',
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

    @patch('api.views.import_item.import_job')
    def test_import_to_featurestore(self, mock_import_job):
        """Test importing to featurestore."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test'}
            }],
            imported=False
        )
        mock_import_job.start_import_job.return_value = 'import-job-id'

        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)

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

    @patch('geo_lib.processing.duplicate_detection.find_coordinate_duplicates')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates(self, mock_realtime_log_class, mock_find_duplicates):
        """Test rechecking duplicates for an import queue item."""
        # Create import queue item with features
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test Feature 1'}
            }, {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4195, 37.7750]},
                'properties': {'name': 'Test Feature 2'}
            }],
            imported=False
        )

        # Mock the RealTimeImportLog instance
        mock_import_log = MagicMock()
        mock_import_log.add = MagicMock()
        mock_import_log.extend = MagicMock()
        mock_import_log.add_timing = MagicMock()
        mock_realtime_log_class.return_value = mock_import_log
        
        # Mock ImportLog returned by find_coordinate_duplicates
        mock_duplicate_log = MagicMock()
        
        duplicate_feature = {
            'feature': import_queue.geofeatures[0],
            'existing_features': [{
                'id': 123,
                'name': 'Existing Feature',
                'type': 'Point',
                'timestamp': '2024-01-01T00:00:00Z'
            }]
        }
        mock_find_duplicates.return_value = (
            [import_queue.geofeatures[1]],  # unique_features
            [duplicate_feature],  # duplicate_features
            mock_duplicate_log  # import_log
        )

        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['duplicate_count'], 1)
        self.assertIn('Duplicates rechecked successfully', data.get('msg', ''))

        # Verify RealTimeImportLog was initialized with correct parameters
        mock_realtime_log_class.assert_called_once_with(user_id=self.user.id, log_id=import_queue.log_id)
        
        # Verify duplicate detection was called
        mock_find_duplicates.assert_called_once()
        
        # Verify logging methods were called
        self.assertGreater(mock_import_log.add.call_count, 0, "RealTimeImportLog.add should be called")
        self.assertEqual(mock_import_log.extend.call_count, 1, "RealTimeImportLog.extend should be called once")
        self.assertEqual(mock_import_log.add_timing.call_count, 1, "RealTimeImportLog.add_timing should be called once")
        
        # Verify import queue was updated
        import_queue.refresh_from_db()
        self.assertEqual(len(import_queue.duplicate_features), 1)

    def test_recheck_duplicates_not_found(self):
        """Test rechecking duplicates for non-existent item."""
        response = self.client.post('/api/item/import/recheck-duplicates/99999')
        self.assertEqual(response.status_code, 404)

    def test_recheck_duplicates_unauthorized(self):
        """Test rechecking duplicates for another user's item."""
        from django.contrib.auth import get_user_model
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
            geofeatures=[],
            imported=False
        )

        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 404)

    def test_recheck_duplicates_already_imported(self):
        """Test rechecking duplicates for already imported item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=True  # Already imported
        )

        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', ''))

    @patch('api.views.import_item.import_job')
    def test_import_returns_immediately_with_item_id(self, mock_import_job):
        """Test that import endpoint returns immediately with job_id and item_id."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test'}
            }],
            imported=False
        )
        mock_import_job.start_import_job.return_value = 'test-job-id'

        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['job_id'], 'test-job-id')
        self.assertEqual(data['item_id'], import_queue.id)
        self.assertIn('Import job started', data['msg'])

    @patch('api.views.import_item.import_job')
    def test_import_no_blocking_parameter_accepted(self, mock_import_job):
        """Test that import endpoint no longer accepts blocking parameter."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test'}
            }],
            imported=False
        )
        mock_import_job.start_import_job.return_value = 'test-job-id'

        # Test with blocking=true parameter (should be ignored)
        response = self.client.post(
            f'/api/item/import/perform/{import_queue.id}?blocking=true',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        # Should return immediately with job_id, not wait for completion
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)
        self.assertIn('item_id', data)
        # Should NOT have 'imported' or 'job_status' fields (those were from blocking mode)
        self.assertNotIn('imported', data)
        self.assertNotIn('job_status', data)


class TestImportJobWebSocket(TestCase):
    """Test ImportJob WebSocket broadcasting methods."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    @patch('asgiref.sync.async_to_sync')
    def test_broadcast_to_process_status_module_called(self, mock_async_to_sync):
        """Test that _broadcast_to_process_status_module creates correct WebSocket message."""
        from geo_lib.processing.jobs.import_job import ImportJob
        
        # Create import job instance
        import_job = ImportJob(status_tracker)
        
        # Mock async_to_sync and channel layer
        mock_group_send = MagicMock()
        mock_async_to_sync.return_value = mock_group_send
        
        # Call broadcast method directly
        import_job._broadcast_to_process_status_module(
            user_id=self.user.id,
            import_queue_id=123,
            event_type='item_completed',
            data={'message': 'Success', 'imported_count': 5}
        )
        
        # Verify async_to_sync was called
        self.assertTrue(mock_async_to_sync.called)
        
        # Verify group_send was called with correct arguments
        expected_channel = f"process_status_{self.user.id}_123"
        expected_message = {
            'type': 'item_completed',
            'data': {'message': 'Success', 'imported_count': 5}
        }
        mock_group_send.assert_called_once_with(expected_channel, expected_message)

    @patch('asgiref.sync.async_to_sync')
    def test_broadcast_completion_message_format(self, mock_async_to_sync):
        """Test that item_completed broadcast has correct data structure."""
        from geo_lib.processing.jobs.import_job import ImportJob
        
        import_job = ImportJob(status_tracker)
        mock_group_send = MagicMock()
        mock_async_to_sync.return_value = mock_group_send
        
        # Broadcast completion
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
        
        # Extract the message argument
        call_args = mock_group_send.call_args
        message = call_args[0][1]
        
        # Verify message structure
        self.assertEqual(message['type'], 'item_completed')
        self.assertIn('data', message)
        self.assertEqual(message['data']['imported_count'], 10)
        self.assertEqual(message['data']['skipped_count'], 2)
        self.assertEqual(message['data']['duplicates_skipped'], 1)

    @patch('asgiref.sync.async_to_sync')
    def test_broadcast_failure_message_format(self, mock_async_to_sync):
        """Test that item_failed broadcast has correct data structure."""
        from geo_lib.processing.jobs.import_job import ImportJob
        
        import_job = ImportJob(status_tracker)
        mock_group_send = MagicMock()
        mock_async_to_sync.return_value = mock_group_send
        
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
        
        # Extract the message argument
        call_args = mock_group_send.call_args
        message = call_args[0][1]
        
        # Verify message structure
        self.assertEqual(message['type'], 'item_failed')
        self.assertIn('data', message)
        self.assertIn('message', message['data'])
        self.assertIn('reason', message['data'])

    @patch('asgiref.sync.async_to_sync')
    def test_broadcast_channel_name_format(self, mock_async_to_sync):
        """Test that broadcast uses correct channel naming convention."""
        from geo_lib.processing.jobs.import_job import ImportJob
        
        import_job = ImportJob(status_tracker)
        mock_group_send = MagicMock()
        mock_async_to_sync.return_value = mock_group_send
        
        user_id = 42
        item_id = 999
        
        import_job._broadcast_to_process_status_module(
            user_id=user_id,
            import_queue_id=item_id,
            event_type='item_completed',
            data={'message': 'test'}
        )
        
        # Extract channel name
        call_args = mock_group_send.call_args
        channel_name = call_args[0][0]
        
        # Verify channel naming convention
        expected_channel = f"process_status_{user_id}_{item_id}"
        self.assertEqual(channel_name, expected_channel)

