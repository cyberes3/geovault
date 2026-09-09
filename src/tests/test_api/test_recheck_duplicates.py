"""Recheck is a Celery job, not an inline count."""

from unittest.mock import patch

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import ImportDraftFeature, ImportQueue
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


class TestRecheckDuplicatesAsync(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(username='recheck-user', email='recheck@example.com', password='x')
        self.client.force_login(self.user)
        self.queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='recheck.kml',
            raw_file='<kml></kml>',
            queue_status=ImportQueue.STATUS_READY,
        )
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [0.0, 0.0, 0.0]},
            'properties': {'name': 'A'},
        }
        feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        ImportDraftFeature.objects.create(
            queue=self.queue,
            user=self.user,
            geojson=feature,
            geojson_hash=feature['properties']['geojson_hash'],
            name='A',
            geometry_type='Point',
            spatial_index=0,
        )

    @patch('api.views.imports.duplicates.dispatch_named_job')
    def test_recheck_returns_job_id(self, mock_dispatch):
        response = self.client.post(
            f'/api/item/import/recheck-duplicates/{self.queue.id}',
            data='{"page": 2}',
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertIn('job_id', body)
        self.assertEqual(body['status'], 'queued')
        self.assertNotIn('duplicate_count', body)
        mock_dispatch.assert_called_once()

    def test_recheck_conflicts_when_lock_held(self):
        with patch('api.views.imports.duplicates.is_user_import_lock_held', return_value=True):
            response = self.client.post(
                f'/api/item/import/recheck-duplicates/{self.queue.id}',
                data='{}',
                content_type='application/json',
            )
        self.assertEqual(response.status_code, 409)
