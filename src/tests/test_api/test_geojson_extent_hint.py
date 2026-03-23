"""Tests for GET /api/geojson/extent-hint/ (main-map aggregate bbox)."""

import json

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestGeojsonExtentHint(TestCase):
    """Extent hint returns aggregate bbox for scope-NULL features only."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='hint@example.com',
            password='testpass123',
            username='hintuser',
        )
        self.client.force_login(self.user)

    def test_extent_hint_unauthenticated(self):
        self.client.logout()
        response = self.client.get('/api/geojson/extent-hint/')
        self.assertEqual(response.status_code, 401)

    def test_extent_hint_no_features(self):
        response = self.client.get('/api/geojson/extent-hint/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('bbox', data)
        self.assertIsNone(data['bbox'])

    def test_extent_hint_with_main_map_feature(self):
        geojson = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-100.5, 40.25, 0.0]},
            'properties': {'name': 'Hint Point', 'tags': []},
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=geojson,
            geometry=Point(-100.5, 40.25, 0.0),
            geojson_hash=generate_geojson_hash(geojson),
            scope=None,
        )
        response = self.client.get('/api/geojson/extent-hint/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsNotNone(data['bbox'])
        self.assertEqual(len(data['bbox']), 4)
        min_lon, min_lat, max_lon, max_lat = data['bbox']
        self.assertAlmostEqual(min_lon, -100.52, places=2)
        self.assertAlmostEqual(max_lon, -100.48, places=2)
        self.assertAlmostEqual(min_lat, 40.23, places=2)
        self.assertAlmostEqual(max_lat, 40.27, places=2)

    def test_extent_hint_ignores_scoped_features(self):
        geojson = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-50.0, 10.0, 0.0]},
            'properties': {'name': 'Scoped', 'tags': []},
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=geojson,
            geometry=Point(-50.0, 10.0, 0.0),
            geojson_hash=generate_geojson_hash(geojson),
            scope='extension_scope',
        )
        response = self.client.get('/api/geojson/extent-hint/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsNone(data['bbox'])
