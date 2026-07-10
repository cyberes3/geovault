"""
Tests for the Places extension API.
"""
import json
from datetime import timedelta
from unittest.mock import patch, MagicMock

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase
from django.utils import timezone

from api.models import FeatureStore
from extensions.places.src.backend.models import PlaceMetadata
from geo_lib.feature_id import generate_geojson_hash


def _patch_places_enabled():
    """Return a context manager that mocks config so the places extension is considered enabled."""
    def mock_get_bool(key, default=False):
        if key == 'extensions.places.enabled':
            return True
        return default

    mock_config = MagicMock()
    mock_config.get_bool.side_effect = mock_get_bool
    return patch('website.extensions.extension_loader.get_config_loader', return_value=mock_config)

class TestPlacesAPI(TestCase):
    """Test Places extension API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create a standard feature (scope=None)
        self.standard_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Standard Point'}
        }
        self.standard_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.standard_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.standard_feature_data),
            scope=None
        )

        # Create a place feature (scope='places')
        self.place_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {'name': 'My Place'}
        }
        self.place_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.place_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(self.place_feature_data),
            scope='places'
        )
        
    def test_list_places(self):
        """Test listing places."""
        with _patch_places_enabled():
            response = self.client.get('/api/extensions/places/features/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['type'], 'FeatureCollection')
        features = data['features']
        self.assertEqual(len(features), 1)
        self.assertEqual(features[0]['properties']['name'], 'My Place')

    def test_create_place(self):
        """Test creating a new place."""
        new_place_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.5, 37.8, 0.0]
            },
            'properties': {
                'name': 'New Place',
                'description': 'A new place'
            }
        }
        with _patch_places_enabled():
            response = self.client.post(
                '/api/extensions/places/features/',
                data=json.dumps(new_place_data),
                content_type='application/json'
            )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.content)
        place_id = data['properties']['database_id']
        place = FeatureStore.objects.get(id=place_id)
        self.assertEqual(place.scope, 'places')

    def test_scope_isolation(self):
        """
        Verify that standard API endpoints DO NOT return scoped features.
        This is the most critical security/functional requirement.
        """
        # 1. Main geojson endpoint
        response = self.client.get(
            '/api/geojson/',
            {'bbox': '-123,37,-122,38', 'zoom': '10'}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should ONLY return the standard feature, NOT the places feature
        # Standard feature (-122.4194, 37.7749) is in bbox
        # Place feature (-122.4094, 37.7849) is in bbox
        
        # If filtering works, count should be 1
        found_ids = [f['properties']['database_id'] for f in data['data']['features']]
        self.assertIn(self.standard_feature.id, found_ids)
        self.assertNotIn(self.place_feature.id, found_ids)
        
    def test_search_scope_isolation(self):
        """Verify search endpoint respects scope."""
        # Search for "Place" - matches "My Place" (scoped) but should excluded it
        # Note: "Standard Point" doesn't match "Place"
        
        response = self.client.get('/api/features/search/', {'query': 'Place'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should be 0 because "My Place" is scoped to 'places'
        self.assertEqual(data['feature_count'], 0)

    def test_list_places_sort_param(self):
        """Verify sort query param changes order (created vs modified)."""
        now = timezone.now()
        # Place A: created earlier, modified later
        place_a_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.7, 0.0]},
            'properties': {'name': 'Place A'}
        }
        place_a = FeatureStore.objects.create(
            user=self.user,
            scope='places',
            geojson=place_a_data,
            geometry=Point(-122.5, 37.7, 0.0),
            geojson_hash=generate_geojson_hash(place_a_data),
            timestamp=now - timedelta(days=5),
        )
        PlaceMetadata.objects.create(
            feature=place_a,
            updated_at=now - timedelta(days=1),
            last_navigated_at=None,
        )
        # Place B: created later, modified earlier
        place_b_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4, 37.7, 0.0]},
            'properties': {'name': 'Place B'}
        }
        place_b = FeatureStore.objects.create(
            user=self.user,
            scope='places',
            geojson=place_b_data,
            geometry=Point(-122.4, 37.7, 0.0),
            geojson_hash=generate_geojson_hash(place_b_data),
            timestamp=now - timedelta(days=2),
        )
        PlaceMetadata.objects.create(
            feature=place_b,
            updated_at=now - timedelta(days=4),
            last_navigated_at=None,
        )
        # Delete the default place from setUp so we only have A and B
        self.place_feature.delete()

        with _patch_places_enabled():
            # sort=created: newest timestamp first -> B (2 days ago) then A (5 days ago)
            response_created = self.client.get('/api/extensions/places/features/', {'sort': 'created'})
        self.assertEqual(response_created.status_code, 200)
        data_created = json.loads(response_created.content)
        ids_created = [f['properties']['database_id'] for f in data_created['features']]
        self.assertEqual(ids_created[0], place_b.id)
        self.assertEqual(ids_created[1], place_a.id)

        with _patch_places_enabled():
            response_modified = self.client.get('/api/extensions/places/features/', {'sort': 'modified'})
        self.assertEqual(response_modified.status_code, 200)
        data_modified = json.loads(response_modified.content)
        ids_modified = [f['properties']['database_id'] for f in data_modified['features']]
        self.assertEqual(ids_modified[0], place_a.id)
        self.assertEqual(ids_modified[1], place_b.id)

    def test_get_place_detail(self):
        """Test retrieving a single place."""
        with _patch_places_enabled():
            response = self.client.get(
                f'/api/extensions/places/features/{self.place_feature.id}/'
            )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['type'], 'Feature')
        self.assertEqual(data['properties']['name'], 'My Place')
        self.assertEqual(data['properties']['database_id'], self.place_feature.id)

    def test_get_place_detail_wrong_scope_404(self):
        """Requesting a non-place feature via places endpoint returns 404."""
        with _patch_places_enabled():
            response = self.client.get(
                f'/api/extensions/places/features/{self.standard_feature.id}/'
            )
        self.assertEqual(response.status_code, 404)

    def test_update_place_detail(self):
        """Test updating a place (PUT)."""
        updated_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.41, 37.78, 0.0]
            },
            'properties': {
                'name': 'Updated Place',
                'description': 'Updated description'
            }
        }
        with _patch_places_enabled():
            response = self.client.put(
                f'/api/extensions/places/features/{self.place_feature.id}/',
                data=json.dumps(updated_data),
                content_type='application/json'
            )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['properties']['name'], 'Updated Place')
        self.assertEqual(data['properties']['description'], 'Updated description')
        self.place_feature.refresh_from_db()
        self.assertEqual(self.place_feature.geojson['properties']['name'], 'Updated Place')
        meta = PlaceMetadata.objects.get(feature=self.place_feature)
        self.assertIsNotNone(meta.updated_at)

    def test_delete_place_detail(self):
        """Test deleting a place."""
        place_id = self.place_feature.id
        with _patch_places_enabled():
            response = self.client.delete(
                f'/api/extensions/places/features/{place_id}/'
            )
        self.assertEqual(response.status_code, 200)
        self.assertFalse(FeatureStore.objects.filter(id=place_id).exists())
        with _patch_places_enabled():
            get_response = self.client.get(
                f'/api/extensions/places/features/{place_id}/'
            )
        self.assertEqual(get_response.status_code, 404)

    def test_place_navigate(self):
        """POST navigate updates last_navigated_at."""
        meta, _ = PlaceMetadata.objects.get_or_create(
            feature=self.place_feature,
            defaults={'updated_at': None, 'last_navigated_at': None}
        )
        self.assertIsNone(meta.last_navigated_at)
        with _patch_places_enabled():
            response = self.client.post(
                f'/api/extensions/places/features/{self.place_feature.id}/navigate/'
            )
        self.assertEqual(response.status_code, 204)
        meta.refresh_from_db()
        self.assertIsNotNone(meta.last_navigated_at)

    def test_list_places_invalid_sort_fallback(self):
        """Invalid sort param falls back to composite; returns 200."""
        with _patch_places_enabled():
            response = self.client.get(
                '/api/extensions/places/features/',
                {'sort': 'invalid'}
            )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['type'], 'FeatureCollection')
        self.assertIn('features', data)
        self.assertEqual(len(data['features']), 1)

    def test_create_place_invalid_payload(self):
        """POST with invalid payload returns 400."""
        invalid_payloads = [
            ('invalid json', None),
            ('missing geometry', {'type': 'Feature', 'properties': {'name': 'X'}}),
            ('non-Point geometry', {
                'type': 'Feature',
                'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
                'properties': {'name': 'X'}
            }),
        ]
        with _patch_places_enabled():
            for label, payload in invalid_payloads:
                body = json.dumps(payload) if payload is not None else 'not json'
                response = self.client.post(
                    '/api/extensions/places/features/',
                    data=body,
                    content_type='application/json'
                )
                self.assertEqual(response.status_code, 400, msg=label)

    def test_create_place_swapped_coordinates_rejected(self):
        """POST with swapped coordinates returns 400."""
        payload = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-104.26, 120.0]},
            'properties': {'name': 'Swapped Place'},
        }
        with _patch_places_enabled():
            response = self.client.post(
                '/api/extensions/places/features/',
                data=json.dumps(payload),
                content_type='application/json',
            )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        error_text = data['error'].lower()
        self.assertTrue('latitude' in error_text or 'swapped' in error_text)

    def test_update_place_swapped_coordinates_rejected(self):
        """PUT with swapped coordinates returns 400."""
        payload = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-104.26, 120.0]},
            'properties': {'name': 'Swapped Update'},
        }
        with _patch_places_enabled():
            response = self.client.put(
                f'/api/extensions/places/features/{self.place_feature.id}/',
                data=json.dumps(payload),
                content_type='application/json',
            )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        error_text = data['error'].lower()
        self.assertTrue('latitude' in error_text or 'swapped' in error_text)
