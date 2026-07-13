"""
Tests for reverse_geocoding API endpoints.
"""
import json
import os
import re
import requests
from unittest.mock import MagicMock, patch
from django.test import TestCase, override_settings
from django.core.cache import cache
from django.contrib.auth import get_user_model

# geo_lib.search_geocoding.{backends,common,maptiler,google} read config exclusively from
# django.conf.settings, so tests drive backend selection/config via override_settings.


class TestGeocodingAPI(TestCase):
    """Test reverse_geocoding API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        # Clear cache before each test
        cache.clear()

        # Create and authenticate test user
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def _create_mock_admin_response(self):
        """Create mock response for administrative divisions request."""
        return {
            'type': 'FeatureCollection',
            'features': []
        }

    def _create_mock_geographic_response(self):
        """Create mock response for geographic features request."""
        return {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'id': 'poi.48602113',
                    'text': 'Rocky Mountain National Park',
                    'place_name': 'Rocky Mountain National Park, Larimer, United States of America',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [-105.70889554917812, 40.33331796070216]
                    },
                    'bbox': [-105.70889554917812, 40.33331796070216, -105.70889554917812, 40.33331796070216],
                    'properties': {
                        'country_code': 'us',
                        'kind': 'place',
                        'place_designation': 'park'
                    },
                    'relevance': 1.0
                },
                {
                    'type': 'Feature',
                    'id': 'poi.214222',
                    'text': 'Rocky Mountain',
                    'place_name': 'Rocky Mountain, New Zealand / Aotearoa',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [167.94685006141663, -46.861660293551495]
                    },
                    'bbox': [167.94685006141663, -46.861660293551495, 167.94685006141663, -46.861660293551495],
                    'properties': {
                        'country_code': 'nz',
                        'kind': 'place'
                    },
                    'relevance': 0.8
                }
            ]
        }

    def _create_mock_all_types_response(self):
        """Create mock response for all types request."""
        return {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'id': 'address.21312537',
                    'text': 'Rocky Mountain',
                    'place_name': 'Rocky Mountain, Orange, California 92679, United States of America',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [-117.59722433913015, 33.56643766813242]
                    },
                    'bbox': [-117.59850148111583, 33.565166016675256, -117.59511653333904, 33.567816930795615],
                    'properties': {
                        'country_code': 'us',
                        'kind': 'street'
                    },
                    'relevance': 0.75
                }
            ]
        }

    def _create_mock_google_response(self, results=None):
        """Create mock response for Google Geocoding API (status OK + results list)."""
        if results is None:
            results = [
                {
                    'place_id': 'ChIJtest',
                    'formatted_address': '123 Main St, Denver, CO, USA',
                    'geometry': {
                        'location': {'lat': 39.7, 'lng': -104.9},
                        'viewport': {
                            'southwest': {'lat': 39.6, 'lng': -105.0},
                            'northeast': {'lat': 39.8, 'lng': -104.8},
                        },
                    },
                    'address_components': [
                        {'types': ['street_number'], 'long_name': '123'},
                        {'types': ['route'], 'long_name': 'Main St'},
                    ],
                },
            ]
        return {'status': 'OK', 'results': results}

    def _create_mock_google_response_rocky_mountain(self):
        """Google-style response with Rocky Mountain National Park–like result."""
        return self._create_mock_google_response(results=[
            {
                'place_id': 'ChIJRMNP',
                'formatted_address': 'Rocky Mountain National Park, Larimer, CO, USA',
                'geometry': {
                    'location': {'lat': 40.33331796070216, 'lng': -105.70889554917812},
                    'viewport': {
                        'southwest': {'lat': 40.2, 'lng': -105.9},
                        'northeast': {'lat': 40.5, 'lng': -105.5},
                    },
                },
                'address_components': [
                    {'types': ['establishment'], 'long_name': 'Rocky Mountain National Park'},
                ],
            },
        ])

    def _assert_search_response_contract(self, data, query=None):
        """Assert common response contract: data.data has query and features with cleaned shape."""
        self.assertIn('data', data)
        self.assertIn('features', data['data'])
        if query is not None:
            self.assertEqual(data['data']['query'], query)
        for feature in data['data']['features']:
            self.assertIn('coordinates', feature)
            self.assertIn('id', feature)
            self.assertIn('text', feature)
            self.assertIn('place_name', feature)
            self.assertIn('bbox', feature)
            self.assertNotIn('geometry', feature)
            self.assertNotIn('type', feature)
            self.assertNotIn('relevance', feature)

    def _assert_no_cyrillic_in_search_results(self, data):
        """Assert no Cyrillic characters in feature text/place_name (live edge-case check)."""
        features = data['data']['features']
        self.assertTrue(len(features) > 0, "Should return at least one feature")
        cyrillic_pattern = re.compile(r'[\u0400-\u04FF]')
        for feature in features:
            for field in ('text', 'place_name'):
                val = feature.get(field, '')
                if val:
                    self.assertFalse(
                        bool(cyrillic_pattern.search(val)),
                        f"Feature {field} '{val}' contains Cyrillic characters",
                    )

    def test_geocoding_search_maptiler_live_no_cyrillic_in_search_results(self):
        """Live MapTiler API: query that can return Cyrillic; assert response has no Cyrillic in feature text/place_name."""
        api_key = (os.environ.get('MAPTILER_API_KEY') or '').strip() or None
        if not api_key:
            self.skipTest("MapTiler API key not configured")
        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY=api_key, GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=niggerhead rock')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_no_cyrillic_in_search_results(data)

    @patch('requests.get')
    def test_geocoding_search_maptiler_rocky_mountain_national_park(self, mock_get):
        """Test that searching for 'rocky mountain national park' returns RMNP-like feature (MapTiler)."""
        mock_admin = MagicMock()
        mock_admin.status_code = 200
        mock_admin.json.return_value = self._create_mock_admin_response()
        mock_geo = MagicMock()
        mock_geo.status_code = 200
        mock_geo.json.return_value = self._create_mock_geographic_response()
        mock_all = MagicMock()
        mock_all.status_code = 200
        mock_all.json.return_value = self._create_mock_all_types_response()
        mock_get.side_effect = [mock_admin, mock_geo, mock_all]

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=rocky mountain national park')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain national park')
        features = data['data']['features']
        rmnp = next((f for f in features if f.get('id') == 'poi.48602113'), None)
        self.assertIsNotNone(rmnp, msg="RMNP feature should be in results")
        self.assertEqual(rmnp['text'], 'Rocky Mountain National Park')
        self.assertEqual(rmnp['place_name'], 'Larimer, United States of America')

    @patch('requests.get')
    def test_geocoding_search_google_rocky_mountain_national_park(self, mock_get):
        """Test that searching for 'rocky mountain national park' returns RMNP-like feature (Google)."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response_rocky_mountain()
        mock_get.return_value = mock_resp

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=rocky mountain national park')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain national park')
        features = data['data']['features']
        rmnp = next((f for f in features if f.get('id') == 'ChIJRMNP'), None)
        self.assertIsNotNone(rmnp, msg="RMNP-like feature should be in results")
        self.assertIn('Rocky Mountain', rmnp['text'] or '')

    @patch('requests.get')
    def test_geocoding_search_maptiler_caching(self, mock_get):
        """Test that reverse_geocoding results are cached (MapTiler)."""
        cache.clear()
        mock_admin = MagicMock()
        mock_admin.status_code = 200
        mock_admin.json.return_value = self._create_mock_admin_response()
        mock_geo = MagicMock()
        mock_geo.status_code = 200
        mock_geo.json.return_value = self._create_mock_geographic_response()
        mock_all = MagicMock()
        mock_all.status_code = 200
        mock_all.json.return_value = self._create_mock_all_types_response()
        mock_get.side_effect = [mock_admin, mock_geo, mock_all]

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response1 = self.client.get('/api/geocoding/search/?q=denver')
            self.assertEqual(response1.status_code, 200)
            self.assertEqual(mock_get.call_count, 3)

            mock_get.reset_mock()
            response2 = self.client.get('/api/geocoding/search/?q=denver')
            self.assertEqual(response2.status_code, 200)
            self.assertEqual(mock_get.call_count, 0, msg="cache hit on second request")

        self.assertEqual(json.loads(response1.content), json.loads(response2.content))

    @patch('requests.get')
    def test_geocoding_search_google_caching(self, mock_get):
        """Test that reverse_geocoding results are cached (Google)."""
        cache.clear()
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.return_value = mock_resp

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response1 = self.client.get('/api/geocoding/search/?q=denver')
            self.assertEqual(response1.status_code, 200)
            self.assertEqual(mock_get.call_count, 1)

            mock_get.reset_mock()
            response2 = self.client.get('/api/geocoding/search/?q=denver')
            self.assertEqual(response2.status_code, 200)
            self.assertEqual(mock_get.call_count, 0, msg="cache hit on second request")

        self.assertEqual(json.loads(response1.content), json.loads(response2.content))

    def test_geocoding_search_maptiler_no_api_key(self):
        """Test reverse_geocoding search when MapTiler API key is not configured."""
        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='x'):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('MapTiler', data['error'])

    def test_geocoding_search_google_no_api_key(self):
        """Test reverse_geocoding search when Google API key is not configured."""
        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY='x', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('Google', data['error'])

    def test_geocoding_search_maptiler_missing_query(self):
        """Test reverse_geocoding search without query parameter."""
        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key'):
            response = self.client.get('/api/geocoding/search/')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('required', data['error'].lower())

    @patch('requests.get')
    def test_geocoding_search_maptiler_api_error(self, mock_get):
        """Test reverse_geocoding search when MapTiler API returns error (partial failure still returns 200)."""
        mock_admin = MagicMock()
        mock_admin.status_code = 200
        mock_admin.json.return_value = self._create_mock_admin_response()
        mock_geo = MagicMock()
        mock_geo.status_code = 200
        mock_geo.json.return_value = self._create_mock_geographic_response()
        mock_all = MagicMock()
        mock_all.status_code = 400
        mock_all.text = 'ERR_VALIDATION: Invalid parameter'
        mock_get.side_effect = [mock_admin, mock_geo, mock_all]

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('features', data['data'])

    @patch('requests.get')
    def test_geocoding_search_google_api_error(self, mock_get):
        """Test reverse_geocoding search when Google API returns error."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {'status': 'OVER_QUERY_LIMIT', 'error_message': 'Quota exceeded'}
        mock_get.return_value = mock_resp

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    @patch('requests.get')
    def test_geocoding_search_maptiler_timeout(self, mock_get):
        """Test reverse_geocoding search when MapTiler request times out."""
        mock_get.side_effect = requests.exceptions.Timeout('Request timed out')

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 504)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('timed out', data['error'].lower())

    @patch('requests.get')
    def test_geocoding_search_google_timeout(self, mock_get):
        """Test reverse_geocoding search when Google request times out."""
        mock_get.side_effect = requests.exceptions.Timeout('Request timed out')

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 504)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('timed out', data['error'].lower())

    @patch('requests.get')
    def test_geocoding_search_maptiler_feature_cleaning(self, mock_get):
        """Test that MapTiler features are properly cleaned."""
        mock_admin = MagicMock()
        mock_admin.status_code = 200
        mock_admin.json.return_value = self._create_mock_admin_response()
        mock_geo = MagicMock()
        mock_geo.status_code = 200
        mock_geo.json.return_value = self._create_mock_geographic_response()
        mock_all = MagicMock()
        mock_all.status_code = 200
        mock_all.json.return_value = self._create_mock_all_types_response()
        mock_get.side_effect = [mock_admin, mock_geo, mock_all]

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=test')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'test')
        features = data['data']['features']
        for feature in features:
            self.assertNotIn('context', feature)
            self.assertNotIn('place_type', feature)
            self.assertNotIn('center', feature)
            if 'properties' in feature:
                props = feature['properties']
                self.assertNotIn('ref', props)
                self.assertNotIn('wikidata', props)
                self.assertNotIn('feature_tags', props)
                self.assertNotIn('categories', props)

    @patch('requests.get')
    def test_geocoding_search_google_feature_cleaning(self, mock_get):
        """Test that Google features are properly cleaned."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.return_value = mock_resp

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=test')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'test')
        features = data['data']['features']
        for feature in features:
            self.assertNotIn('context', feature)
            self.assertNotIn('place_type', feature)
            self.assertNotIn('center', feature)
            if 'properties' in feature:
                props = feature['properties']
                self.assertNotIn('ref', props)
                self.assertNotIn('wikidata', props)
                self.assertNotIn('feature_tags', props)
                self.assertNotIn('categories', props)

    @patch('requests.get')
    def test_geocoding_search_maptiler_prioritizes_geographic_features(self, mock_get):
        """Test MapTiler geographic prioritization (geographic features before addresses)."""
        mock_admin = MagicMock()
        mock_admin.status_code = 200
        mock_admin.json.return_value = self._create_mock_admin_response()
        mock_geo = MagicMock()
        mock_geo.status_code = 200
        mock_geo.json.return_value = self._create_mock_geographic_response()
        mock_all = MagicMock()
        mock_all.status_code = 200
        mock_all.json.return_value = self._create_mock_all_types_response()
        mock_get.side_effect = [mock_admin, mock_geo, mock_all]

        with override_settings(GEOCODING_SEARCH_MODE='maptiler', MAPTILER_API_KEY='test_api_key', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=rocky mountain')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain')
        features = data['data']['features']
        feature_ids = [f.get('id') for f in features]
        rmnp_index = feature_ids.index('poi.48602113') if 'poi.48602113' in feature_ids else -1
        address_index = feature_ids.index('address.21312537') if 'address.21312537' in feature_ids else -1
        if rmnp_index >= 0 and address_index >= 0:
            self.assertLess(rmnp_index, address_index,
                            "Geographic features should appear before addresses")

    @patch('requests.get')
    def test_geocoding_search_google_prioritizes_geographic_features(self, mock_get):
        """Test Google search returns valid contract (prioritization is backend-specific)."""
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.return_value = mock_resp

        with override_settings(GEOCODING_SEARCH_MODE='google', MAPTILER_API_KEY=None, GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=rocky mountain')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain')

    @patch('requests.get')
    def test_geocoding_search_google_uses_backend(self, mock_get):
        """With geocoding_search_mode google and Google key configured, view uses Google backend."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'status': 'OK',
            'results': [
                {
                    'place_id': 'ChIJtest',
                    'formatted_address': '123 Main St, Denver, CO, USA',
                    'geometry': {
                        'location': {'lat': 39.7, 'lng': -104.9},
                        'viewport': {
                            'southwest': {'lat': 39.6, 'lng': -105.0},
                            'northeast': {'lat': 39.8, 'lng': -104.8},
                        },
                    },
                    'address_components': [
                        {'types': ['street_number'], 'long_name': '123'},
                        {'types': ['route'], 'long_name': 'Main St'},
                    ],
                },
            ],
        }
        mock_get.return_value = mock_response

        with override_settings(GEOCODING_SEARCH_MODE='google', GOOGLE_GEOCODING_API_KEY='test_google_key'):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertIn('features', data['data'])
        self.assertEqual(len(data['data']['features']), 1)
        feature = data['data']['features'][0]
        self.assertEqual(feature['coordinates'], [-104.9, 39.7])
        self.assertIn('place_name', feature)
        self.assertIn('bbox', feature)
        mock_get.assert_called_once()
        call_args = mock_get.call_args
        self.assertIn('maps.googleapis.com', str(call_args))

    def test_geocoding_search_google_no_key_returns_503(self):
        """With geocoding_search_mode google and Google API key not configured, view returns 503."""
        with override_settings(GEOCODING_SEARCH_MODE='google', GOOGLE_GEOCODING_API_KEY=None):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('Google', data['error'])

    def test_geocoding_search_unset_returns_503(self):
        """When geocoding_search_mode is None (missing or unset), view returns 503."""
        with override_settings(GEOCODING_SEARCH_MODE=None):
            response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not configured', data['error'].lower())
        self.assertIn('geocoding_search_mode', data['error'])
