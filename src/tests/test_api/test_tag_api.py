import json

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.services.feature_service import FeatureService


def _point(name: str, tags=None, system_tags=None) -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
        'properties': {
            'name': name,
            'tags': tags or [],
            'system_tags': system_tags or [],
        },
    }


class TestTagApi(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='tagapi@example.com',
            password='testpass123',
            username='tagapi',
        )
        self.client.force_login(self.user)
        self.feature = FeatureService.create(self.user, _point('A', tags=['hiking', 'ridge']))

    def test_catalog_is_paginated(self):
        response = self.client.get('/api/tags/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn('items', data)
        self.assertIn('page', data)
        self.assertIn('total_items', data)
        names = {item['name'] for item in data['items']}
        self.assertIn('hiking', names)

    def test_names_endpoint(self):
        response = self.client.get('/api/tags/names/')
        self.assertEqual(response.status_code, 200)
        self.assertIn('hiking', response.json()['items'])

    def test_rename_and_remove(self):
        response = self.client.patch(
            '/api/tags/hiking/',
            data=json.dumps({'new_name': 'walks'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.feature.refresh_from_db()
        self.assertIn('walks', self.feature.geojson['properties']['tags'])
        self.assertNotIn('hiking', self.feature.geojson['properties']['tags'])
        self.assertIn('ridge', self.feature.geojson['properties']['tags'])

        response = self.client.delete('/api/tags/ridge/')
        self.assertEqual(response.status_code, 200)
        self.feature.refresh_from_db()
        self.assertNotIn('ridge', self.feature.geojson['properties']['tags'])
        self.assertIn('walks', self.feature.geojson['properties']['tags'])

    def test_delete_features_query_param(self):
        response = self.client.delete('/api/tags/hiking/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 1)

    def test_protected_tag_rejected_after_lowercase(self):
        response = self.client.put(
            f'/api/features/{self.feature.id}/tags/',
            data=json.dumps({'tags': ['TYPE:point']}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_filter_by_tags_removed(self):
        response = self.client.get('/api/features/filter-by-tags/', {'tags': 'hiking'})
        self.assertEqual(response.status_code, 404)

    def test_invalid_match_mode_is_400(self):
        response = self.client.get('/api/geojson/', {
            'bbox': '0,0,20,30',
            'zoom': '10',
            'tags': 'hiking',
            'match_mode': 'XOR',
        })
        self.assertEqual(response.status_code, 400)
