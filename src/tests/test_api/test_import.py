"""
Tests for import/upload API endpoints.
"""
import json
from unittest.mock import patch, MagicMock
import pytest
from django.test import TestCase
from django.core.files.uploadedfile import SimpleUploadedFile

from api.models import ImportQueue
from geo_lib.processing.status_tracker import ProcessingStatus, JobType


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
        self.assertEqual(response.status_code, 403)

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
        self.assertEqual(response.status_code, 400)

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
        self.assertEqual(response.status_code, 400)

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

        update_data = {'features': []}
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

        response = self.client.post(f'/api/item/import/perform/{import_queue.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('job_id', data)

    def test_import_to_featurestore_not_found(self):
        """Test importing non-existent item."""
        response = self.client.post('/api/item/import/perform/99999')
        self.assertEqual(response.status_code, 404)

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

