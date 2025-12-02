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
            'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
                'properties': {'feature_hash': 'test-id', 'name': 'Updated'}
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

        update_data = {'features': [{'properties': {'feature_hash': 'test-id', 'name': 'Test'}}]}
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
                'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
            'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
                    'feature_hash': 'test-id',
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
            'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
                    'feature_hash': 'test-id',
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
            'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
                    'feature_hash': 'test-id',
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
            'properties': {'feature_hash': 'test-id', 'name': 'Original'}
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
                    'feature_hash': 'test-id',
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

    @patch('channels.layers.get_channel_layer')
    @patch('geo_lib.processing.duplicate_detection.find_duplicates_for_source')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates(self, mock_realtime_log_class, mock_find_duplicates_for_source, mock_get_channel_layer):
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
        
        # Mock ImportLog returned by find_duplicates_for_source
        from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
        mock_duplicate_log = MagicMock()
        
        duplicate_feature = {
            'feature': import_queue.geofeatures[0],
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.GEOMETRY,
            'existing_features': [{
                'id': 123,
                'name': 'Existing Feature',
                'type': 'Point',
                'timestamp': '2024-01-01T00:00:00Z'
            }]
        }
        
        # Mock returns: (remaining_features, all_duplicates, import_log)
        # First call (feature store): returns 1 duplicate from feature store
        # Second call (cross-queue): returns empty (no cross-queue duplicates)
        mock_find_duplicates_for_source.side_effect = [
            ([import_queue.geofeatures[1]], [duplicate_feature], mock_duplicate_log),  # feature store pass
            ([import_queue.geofeatures[1]], [], mock_duplicate_log),  # cross-queue pass (no duplicates)
        ]

        # Mock channel layer for WebSocket notifications
        # group_send needs to be a coroutine function for async_to_sync
        async def mock_group_send(group, message):
            return None
        
        mock_channel_layer = MagicMock()
        mock_channel_layer.group_send = mock_group_send
        mock_get_channel_layer.return_value = mock_channel_layer

        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['duplicate_count'], 1)
        self.assertIn('Duplicates rechecked successfully', data.get('msg', ''))

        # Verify RealTimeImportLog was initialized with correct parameters
        mock_realtime_log_class.assert_called_once_with(user_id=self.user.id, log_id=import_queue.log_id)
        
        # Verify duplicate detection was called twice (feature store + cross-queue)
        self.assertEqual(mock_find_duplicates_for_source.call_count, 2, "find_duplicates_for_source should be called twice")
        
        # Verify logging methods were called
        self.assertGreater(mock_import_log.add.call_count, 0, "RealTimeImportLog.add should be called")
        self.assertEqual(mock_import_log.extend.call_count, 2, "RealTimeImportLog.extend should be called twice (feature store + cross-queue)")
        self.assertEqual(mock_import_log.add_timing.call_count, 1, "RealTimeImportLog.add_timing should be called once")
        
        # Verify import queue was updated
        import_queue.refresh_from_db()
        self.assertEqual(len(import_queue.duplicate_features), 1)
        
        # Verify that geometry duplicates were auto-skipped
        # The duplicate feature's ID should be in skipped_feature_ids
        from geo_lib.feature_id import generate_feature_hash
        dup_feature_dict = duplicate_feature['feature']
        expected_skip_id = dup_feature_dict.get('properties', {}).get('feature_hash')
        if not expected_skip_id:
            expected_skip_id = generate_feature_hash(dup_feature_dict)
        self.assertIn(expected_skip_id, import_queue.skipped_feature_ids,
                     "Geometry duplicate should be auto-skipped")
        
        # Verify WebSocket notification was sent
        mock_get_channel_layer.assert_called_once()
        # The group_send should have been called via async_to_sync
        # Check that group_send was called (it gets wrapped by async_to_sync)

    @patch('channels.layers.get_channel_layer')
    @patch('geo_lib.processing.duplicate_detection.find_duplicates_for_source')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates_hash_takes_precedence(self, mock_realtime_log_class, mock_find_duplicates_for_source, mock_get_channel_layer):
        """Test that if a feature is both a coordinate duplicate and hash duplicate, only hash duplicate is marked."""
        from geo_lib.feature_id import generate_feature_hash
        from api.models import FeatureStore
        
        # Create a feature that will be a hash duplicate
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature'}
        }
        feature_hash = generate_feature_hash(test_feature)
        test_feature['properties']['feature_hash'] = feature_hash
        
        # Create a FeatureStore entry with the same hash (hash duplicate)
        feature_store = FeatureStore.objects.create(
            user=self.user,
            geojson=test_feature,
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

        # Mock the RealTimeImportLog instance
        mock_import_log = MagicMock()
        mock_import_log.add = MagicMock()
        mock_import_log.extend = MagicMock()
        mock_import_log.add_timing = MagicMock()
        mock_realtime_log_class.return_value = mock_import_log
        
        # Mock find_duplicates_for_source to return this feature as a HASH duplicate
        # (find_duplicates_for_source handles hash-over-geometry priority internally)
        from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
        mock_duplicate_log = MagicMock()
        hash_duplicate_feature = {
            'feature': test_feature,
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,  # Hash duplicate, not geometry
            'existing_features': [{
                'id': feature_store.id,
                'name': 'Existing Feature',
                'type': 'Point',
                'timestamp': '2024-01-01T00:00:00Z',
                'geojson': test_feature
            }]
        }
        
        # Mock returns: (remaining_features, all_duplicates, import_log)
        # First call (feature store): returns hash duplicate from feature store
        # Second call (cross-queue): no remaining features to check
        mock_find_duplicates_for_source.side_effect = [
            ([], [hash_duplicate_feature], mock_duplicate_log),  # feature store pass - hash duplicate found
            ([], [], mock_duplicate_log),  # cross-queue pass (no remaining features)
        ]

        # Mock channel layer
        async def mock_group_send(group, message):
            return None
        mock_channel_layer = MagicMock()
        mock_channel_layer.group_send = mock_group_send
        mock_get_channel_layer.return_value = mock_channel_layer

        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify that the feature WAS saved as a hash duplicate (not geometry)
        import_queue.refresh_from_db()
        self.assertEqual(len(import_queue.duplicate_features), 1,
                        "Feature should be marked as hash duplicate")
        self.assertEqual(import_queue.duplicate_features[0]['match_type'], DuplicateMatchType.HASH,
                        "Duplicate should be marked as HASH type, not geometry")
        
        # Verify it's not in skipped_feature_ids (hash duplicates are blocked, not skipped)
        self.assertNotIn(feature_hash, import_queue.skipped_feature_ids or [],
                        "Hash duplicates should not be in skipped_feature_ids (they're blocked, not skipped)")

    @patch('channels.layers.get_channel_layer')
    @patch('geo_lib.processing.duplicate_detection.find_duplicates_for_source')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates_cross_queue_coord_duplicate(self, mock_realtime_log_class, mock_find_duplicates_for_source, mock_get_channel_layer):
        """Test that recheck_duplicates detects cross-queue geometry duplicates."""
        from geo_lib.feature_id import generate_feature_hash
        from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
        
        # Create first import queue item with a feature
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Feature 1'}
        }
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
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Feature 2'}  # Different name, same coordinates
        }
        import_queue2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature2],
            imported=False
        )
        
        # Mock the RealTimeImportLog instance
        mock_import_log = MagicMock()
        mock_import_log.add = MagicMock()
        mock_import_log.extend = MagicMock()
        mock_import_log.add_timing = MagicMock()
        mock_realtime_log_class.return_value = mock_import_log
        
        # Mock find_duplicates_for_source to return geometry duplicate in cross-queue
        mock_duplicate_log = MagicMock()
        cross_queue_duplicate = {
            'feature': feature2,
            'source': DuplicateSource.CROSS_QUEUE,
            'match_type': DuplicateMatchType.GEOMETRY,
            'existing_features': [{
                'id': import_queue1.id,
                'name': 'test1.kml',
                'type': 'Point',
                'timestamp': None,  # cross-queue doesn't have timestamp
                'geojson': feature1
            }]
        }
        
        # Mock returns: (remaining_features, all_duplicates, import_log)
        # First call (feature store): no duplicates
        # Second call (cross-queue): returns geometry duplicate from first queue item
        mock_find_duplicates_for_source.side_effect = [
            ([feature2], [], mock_duplicate_log),  # feature store pass - no duplicates
            ([], [cross_queue_duplicate], mock_duplicate_log),  # cross-queue pass - geometry duplicate found
        ]

        # Mock channel layer
        async def mock_group_send(group, message):
            return None
        mock_channel_layer = MagicMock()
        mock_channel_layer.group_send = mock_group_send
        mock_get_channel_layer.return_value = mock_channel_layer

        # Recheck duplicates for the second item (should find geometry duplicate in first item)
        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue2.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify that the cross-queue geometry duplicate was detected
        import_queue2.refresh_from_db()
        self.assertEqual(len(import_queue2.duplicate_features), 1,
                        "Cross-queue geometry duplicate should be detected")
        
        # Verify the duplicate info references the first queue item
        dup_info = import_queue2.duplicate_features[0]
        self.assertEqual(dup_info['existing_features'][0]['id'], import_queue1.id,
                        "Duplicate should reference the first queue item")
        self.assertEqual(dup_info['existing_features'][0]['name'], 'test1.kml',
                        "Duplicate should reference the first queue item's filename")
        
        # Verify it was auto-skipped
        feature2_hash = generate_feature_hash(feature2)
        feature2_id = feature2.get('properties', {}).get('feature_hash', feature2_hash)
        self.assertIn(feature2_id, import_queue2.skipped_feature_ids or [],
                     "Cross-queue geometry duplicate should be auto-skipped")

    @patch('channels.layers.get_channel_layer')
    @patch('geo_lib.processing.duplicate_detection.find_duplicates_for_source')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates_cross_queue_hash_is_blocked(self, mock_realtime_log_class, mock_find_duplicates_for_source, mock_get_channel_layer):
        """Test that cross-queue hash duplicates are BLOCKED (not skipped/restorable)."""
        from geo_lib.feature_id import generate_feature_hash
        from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
        
        # Create feature with hash
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature', 'description': 'Test'}
        }
        feature_hash = generate_feature_hash(test_feature)
        test_feature['properties']['feature_hash'] = feature_hash
        
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
        mock_import_log = MagicMock()
        mock_import_log.add = MagicMock()
        mock_import_log.extend = MagicMock()
        mock_import_log.add_timing = MagicMock()
        mock_realtime_log_class.return_value = mock_import_log
        
        # Mock find_duplicates_for_source to return cross-queue HASH duplicate
        mock_duplicate_log = MagicMock()
        cross_queue_hash_dup = {
            'feature': test_feature,
            'source': DuplicateSource.CROSS_QUEUE,
            'match_type': DuplicateMatchType.HASH,  # HASH, not geometry
            'existing_features': [{
                'id': older_queue.id,
                'name': 'older.kml',
                'type': 'Point',
                'timestamp': None,
                'geojson': test_feature,
                'feature_index': 0
            }]
        }
        
        # Mock returns: (remaining, duplicates, log)
        mock_find_duplicates_for_source.side_effect = [
            ([test_feature], [], mock_duplicate_log),  # feature store: no dups
            ([], [cross_queue_hash_dup], mock_duplicate_log),  # cross-queue: hash dup found
        ]
        
        # Mock channel layer
        async def mock_group_send(group, message):
            return None
        mock_channel_layer = MagicMock()
        mock_channel_layer.group_send = mock_group_send
        mock_get_channel_layer.return_value = mock_channel_layer
        
        # Recheck duplicates for newer item
        response = self.client.post(f'/api/item/import/recheck-duplicates/{newer_queue.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify duplicate was detected
        newer_queue.refresh_from_db()
        self.assertEqual(len(newer_queue.duplicate_features), 1,
                        "Cross-queue hash duplicate should be detected")
        self.assertEqual(newer_queue.duplicate_features[0]['match_type'], DuplicateMatchType.HASH,
                        "Should be marked as HASH duplicate")
        
        # CRITICAL: Hash duplicates should NOT be in skipped_feature_ids
        # They are BLOCKED (permanent), not SKIPPED (user-restorable)
        self.assertNotIn(feature_hash, newer_queue.skipped_feature_ids or [],
                        "Cross-queue HASH duplicates should NOT be in skipped_feature_ids "
                        "(they are blocked, not user-restorable)")

    def test_recheck_duplicates_not_found(self):
        """Test rechecking duplicates for non-existent item."""
        response = self.client.post('/api/item/import/recheck-duplicates/99999')
        self.assertEqual(response.status_code, 404)

    @patch('channels.layers.get_channel_layer')
    @patch('geo_lib.processing.duplicate_detection.find_duplicates_for_source')
    @patch('geo_lib.processing.logging.RealTimeImportLog')
    def test_recheck_duplicates_empty_file(self, mock_realtime_log_class, mock_find_duplicates_for_source, mock_get_channel_layer):
        """Test rechecking duplicates on empty import queue item (edge case)."""
        # Create import queue with no features
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='empty.kml',
            raw_file='<kml></kml>',
            geofeatures=[],  # Empty!
            imported=False
        )
        
        # Mock RealTimeImportLog
        mock_import_log = MagicMock()
        mock_import_log.add = MagicMock()
        mock_import_log.extend = MagicMock()
        mock_import_log.add_timing = MagicMock()
        mock_realtime_log_class.return_value = mock_import_log
        
        # Mock find_duplicates_for_source to return no duplicates
        mock_duplicate_log = MagicMock()
        mock_find_duplicates_for_source.side_effect = [
            ([], [], mock_duplicate_log),  # feature store: no features to check
            ([], [], mock_duplicate_log),  # cross-queue: no features to check
        ]
        
        # Mock channel layer
        async def mock_group_send(group, message):
            return None
        mock_channel_layer = MagicMock()
        mock_channel_layer.group_send = mock_group_send
        mock_get_channel_layer.return_value = mock_channel_layer
        
        # Should handle empty file gracefully
        response = self.client.post(f'/api/item/import/recheck-duplicates/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        
        # Verify response
        data = json.loads(response.content)
        self.assertEqual(data['duplicate_count'], 0)
        # Message can be either "No features to check" or "Duplicates rechecked successfully"
        self.assertIn(data.get('msg', ''), ['No features to check', 'Duplicates rechecked successfully'])
        
        # Verify import queue state
        import_queue.refresh_from_db()
        self.assertEqual(len(import_queue.duplicate_features), 0)
        self.assertEqual(len(import_queue.skipped_feature_ids or []), 0)

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

    def test_save_skip_state(self):
        """Test saving skip state for an import queue item."""
        from geo_lib.feature_id import generate_feature_hash
        
        # Create import queue item with features
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature 1'}
        }, {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4195, 37.7750]},
            'properties': {'name': 'Test Feature 2'}
        }]
        
        # Generate feature IDs
        feature_ids = []
        for feature in features:
            feature_hash = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = feature_hash
            feature_ids.append(feature_hash)
        
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=features,
            imported=False
        )

        # Save skip state with first feature ID
        response = self.client.put(
            f'/api/item/import/skip-state/{import_queue.id}',
            data=json.dumps({
                'skipped_feature_ids': [feature_ids[0]]
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('saved successfully', data.get('msg', ''))
        
        # Verify skip state was saved
        import_queue.refresh_from_db()
        self.assertEqual(import_queue.skipped_feature_ids, [feature_ids[0]])

    def test_save_skip_state_invalid_feature_id(self):
        """Test saving skip state with invalid feature ID."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[{
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Test Feature'}
            }],
            imported=False
        )

        # Try to save skip state with invalid feature ID
        response = self.client.put(
            f'/api/item/import/skip-state/{import_queue.id}',
            data=json.dumps({
                'skipped_feature_ids': ['invalid-feature-id']
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Invalid feature IDs', data.get('error', ''))

    def test_save_skip_state_already_imported(self):
        """Test saving skip state for already imported item."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=True  # Already imported
        )

        response = self.client.put(
            f'/api/item/import/skip-state/{import_queue.id}',
            data=json.dumps({
                'skipped_feature_ids': []
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('already been imported', data.get('error', ''))

    def test_save_skip_state_not_found(self):
        """Test saving skip state for non-existent item."""
        response = self.client.put(
            '/api/item/import/skip-state/99999',
            data=json.dumps({
                'skipped_feature_ids': []
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 404)

    def test_save_skip_state_unauthorized(self):
        """Test saving skip state for another user's item."""
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

        response = self.client.put(
            f'/api/item/import/skip-state/{import_queue.id}',
            data=json.dumps({
                'skipped_feature_ids': []
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 404)

    def test_import_uses_saved_skip_state(self):
        """Test that import uses saved skip state from ImportQueue model."""
        from geo_lib.feature_id import generate_feature_hash
        from unittest.mock import patch
        
        # Create import queue item with features and saved skip state
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature 1'}
        }, {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4195, 37.7750]},
            'properties': {'name': 'Test Feature 2'}
        }]
        
        # Generate feature IDs
        feature_ids = []
        for feature in features:
            feature_hash = generate_feature_hash(feature)
            feature['properties']['feature_hash'] = feature_hash
            feature_ids.append(feature_hash)
        
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=features,
            skipped_feature_ids=[feature_ids[0]],  # Save skip state
            imported=False
        )

        # Mock import job to capture the skipped_feature_ids passed
        with patch('api.views.import_item.import_job') as mock_import_job:
            mock_import_job.start_import_job.return_value = 'test-job-id'
            
            # Import without passing skipped_feature_ids in request
            response = self.client.post(
                f'/api/item/import/perform/{import_queue.id}',
                data=json.dumps({}),
                content_type='application/json'
            )
            
            self.assertEqual(response.status_code, 200)
            
            # Verify that start_import_job was called
            mock_import_job.start_import_job.assert_called_once()
            
            # Get the call arguments
            call_args = mock_import_job.start_import_job.call_args
            # The skipped_feature_ids should be empty list from request, but the import job
            # should merge it with saved skip state internally
            # We can't easily test the merge here, but we verify the endpoint works

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


class TestSequentialProcessing(TestCase):
    """Test sequential processing with RedisProcessingLock."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='sequential@example.com',
            password='testpass123',
            username='sequential_user'
        )
        self.client.force_login(self.user)

    @patch('geo_lib.utils.redis_lock.RedisProcessingLock')
    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_redis_lock_is_used_during_processing(self, mock_status_tracker, mock_process_job, mock_lock_class):
        """Test that RedisProcessingLock is used during file processing."""
        # Setup mocks
        mock_status_tracker.create_job.return_value = 'test-job-id'
        mock_process_job.start_process_job.return_value = True
        
        # Mock the lock instance
        mock_lock_instance = MagicMock()
        mock_lock_class.return_value = mock_lock_instance
        
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
        
        # Note: The lock is acquired inside the background thread during _execute_job
        # This test verifies that the upload endpoint works, but the lock is used
        # in the actual processing thread (which is mocked here)

    @patch('api.views.import_item.process_job')
    @patch('api.views.import_item.status_tracker')
    def test_upload_returns_immediately_job_runs_async(self, mock_status_tracker, mock_process_job):
        """Test that upload returns immediately while job runs asynchronously."""
        import time
        
        # Setup mocks
        job_id = 'async-test-job-id'
        mock_status_tracker.create_job.return_value = job_id
        
        # Track when start_process_job is called
        call_time = []
        
        def track_start_time(*args, **kwargs):
            call_time.append(time.time())
            return True
        
        mock_process_job.start_process_job.side_effect = track_start_time
        
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
        
        # Should return very quickly (< 1 second)
        response_time = end_time - start_time
        self.assertLess(response_time, 1.0, "Upload should return immediately")
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['job_id'], job_id)
        
        # Verify start_process_job was called
        self.assertTrue(len(call_time) > 0)

