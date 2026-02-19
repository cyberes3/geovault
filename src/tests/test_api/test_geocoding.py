"""
Tests for reverse_geocoding API endpoints.
"""
import json
import os
import re
import requests
from unittest.mock import MagicMock, patch
from django.test import TestCase
from django.core.cache import cache
from django.contrib.auth import get_user_model

from website.config_loader import get_config_loader

# Real config captured at load time (before any patches) for auth/site-related keys
_REAL_CONFIG = get_config_loader()

# Patch get_config_loader everywhere it is used (backends, common, maptiler, google each import it)
PATCH_CONFIG_BACKENDS = 'geo_lib.search_geocoding.backends.get_config_loader'
PATCH_CONFIG_COMMON = 'geo_lib.search_geocoding.common.get_config_loader'
PATCH_CONFIG_MAPTILER = 'geo_lib.search_geocoding.maptiler.get_config_loader'
PATCH_CONFIG_GOOGLE = 'geo_lib.search_geocoding.google.get_config_loader'
# Both backends use the same requests module, so one patch suffices
PATCH_REQUESTS_GET = 'requests.get'


def _set_config_mocks(mock_backends, mock_common, mock_maptiler, mock_google, mock_config):
    """Assign mock_config as return_value for all get_config_loader mocks."""
    mock_backends.return_value = mock_config
    mock_common.return_value = mock_config
    mock_maptiler.return_value = mock_config
    mock_google.return_value = mock_config
    # Pull real values for auth/site-related config (e.g. site.domain for Origin header)
    mock_config.get_str.side_effect = lambda key, default='': _REAL_CONFIG.get_str(key, default)


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

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_maptiler_live_no_cyrillic_in_search_results(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Live MapTiler API: query that can return Cyrillic; assert response has no Cyrillic in feature text/place_name."""
        from website.config_loader import get_config_loader

        config_loader = get_config_loader()
        api_key = config_loader.get_maptiler_api_key() or (os.environ.get('MAPTILER_API_KEY') or '').strip() or None
        if not api_key:
            self.skipTest("MapTiler API key not configured")
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = api_key
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=niggerhead rock')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_no_cyrillic_in_search_results(data)

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_google_live_no_cyrillic_in_search_results(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Live Google API: query that can return Cyrillic; assert response has no Cyrillic in feature text/place_name."""
        from website.config_loader import get_config_loader

        config_loader = get_config_loader()
        api_key = config_loader.get_google_api_key() or (os.environ.get('GOOGLE_API_KEY') or '').strip() or None
        if not api_key:
            self.skipTest("Google API key not configured")
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_google_api_key.return_value = api_key
        mock_config.get_maptiler_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=niggerhead rock')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_no_cyrillic_in_search_results(data)

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_rocky_mountain_national_park(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that searching for 'rocky mountain national park' returns RMNP-like feature (MapTiler)."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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

        response = self.client.get('/api/geocoding/search/?q=rocky mountain national park')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain national park')
        features = data['data']['features']
        rmnp = next((f for f in features if f.get('id') == 'poi.48602113'), None)
        self.assertIsNotNone(rmnp, msg="RMNP feature should be in results")
        self.assertEqual(rmnp['text'], 'Rocky Mountain National Park')
        self.assertEqual(rmnp['place_name'], 'Larimer, United States of America')

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_rocky_mountain_national_park(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that searching for 'rocky mountain national park' returns RMNP-like feature (Google)."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response_rocky_mountain()
        mock_get.side_effect = None
        mock_get.return_value = mock_resp

        response = self.client.get('/api/geocoding/search/?q=rocky mountain national park')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain national park')
        features = data['data']['features']
        rmnp = next((f for f in features if f.get('id') == 'ChIJRMNP'), None)
        self.assertIsNotNone(rmnp, msg="RMNP-like feature should be in results")
        self.assertIn('Rocky Mountain', rmnp['text'] or '')

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_caching(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that reverse_geocoding results are cached (MapTiler)."""
        cache.clear()
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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

        response1 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(mock_get.call_count, 3)

        mock_get.reset_mock()
        response2 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response2.status_code, 200)
        self.assertEqual(mock_get.call_count, 0, msg="cache hit on second request")

        self.assertEqual(json.loads(response1.content), json.loads(response2.content))

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_caching(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that reverse_geocoding results are cached (Google)."""
        cache.clear()
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.side_effect = None
        mock_get.return_value = mock_resp

        response1 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(mock_get.call_count, 1)

        mock_get.reset_mock()
        response2 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response2.status_code, 200)
        self.assertEqual(mock_get.call_count, 0, msg="cache hit on second request")

        self.assertEqual(json.loads(response1.content), json.loads(response2.content))

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_maptiler_no_api_key(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when MapTiler API key is not configured."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'x'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('MapTiler', data['error'])

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_google_no_api_key(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when Google API key is not configured."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = 'x'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('Google', data['error'])

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_maptiler_missing_query(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search without query parameter."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('required', data['error'].lower())

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_api_error(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when MapTiler API returns error (partial failure still returns 200)."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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
        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('features', data['data'])

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_api_error(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when Google API returns error."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {'status': 'OVER_QUERY_LIMIT', 'error_message': 'Quota exceeded'}
        mock_get.side_effect = None
        mock_get.return_value = mock_resp
        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_timeout(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when MapTiler request times out."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_get.side_effect = requests.exceptions.Timeout('Request timed out')
        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 504)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('timed out', data['error'].lower())

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_timeout(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test reverse_geocoding search when Google request times out."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_get.side_effect = requests.exceptions.Timeout('Request timed out')
        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 504)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('timed out', data['error'].lower())

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_feature_cleaning(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that MapTiler features are properly cleaned."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_feature_cleaning(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test that Google features are properly cleaned."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.side_effect = None
        mock_get.return_value = mock_resp

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

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_maptiler_prioritizes_geographic_features(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test MapTiler geographic prioritization (geographic features before addresses)."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_prioritizes_geographic_features(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """Test Google search returns valid contract (prioritization is backend-specific)."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = self._create_mock_google_response()
        mock_get.side_effect = None
        mock_get.return_value = mock_resp

        response = self.client.get('/api/geocoding/search/?q=rocky mountain')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self._assert_search_response_contract(data, 'rocky mountain')

    # --- geocoding_search_mode config and pluggable backends ---

    def test_get_geocoding_search_mode_default(self):
        """get_geocoding_search_mode returns None when key is missing or unset."""
        import tempfile
        from pathlib import Path
        from website.config_loader import ConfigLoader

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'config.yaml'
            path.write_text('other_key: true\n')
            loader = ConfigLoader(str(path))
            self.assertIsNone(loader.get_geocoding_search_mode())

    def test_get_geocoding_search_mode_maptiler(self):
        """get_geocoding_search_mode returns 'maptiler' when set to maptiler (normalized to lowercase)."""
        import tempfile
        from pathlib import Path
        from website.config_loader import ConfigLoader

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'config.yaml'
            path.write_text('geocoding_search_mode: maptiler\n')
            loader = ConfigLoader(str(path))
            self.assertEqual(loader.get_geocoding_search_mode(), 'maptiler')
            path.write_text('geocoding_search_mode: Maptiler\n')
            loader._load_config()
            self.assertEqual(loader.get_geocoding_search_mode(), 'maptiler')

    def test_get_geocoding_search_mode_google(self):
        """get_geocoding_search_mode returns 'google' when set to google."""
        import tempfile
        from pathlib import Path
        from website.config_loader import ConfigLoader

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'config.yaml'
            path.write_text('geocoding_search_mode: google\n')
            loader = ConfigLoader(str(path))
            self.assertEqual(loader.get_geocoding_search_mode(), 'google')

    def test_get_geocoding_search_mode_invalid_returns_none(self):
        """get_geocoding_search_mode returns None when set to invalid value."""
        import tempfile
        from pathlib import Path
        from website.config_loader import ConfigLoader

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'config.yaml'
            path.write_text('geocoding_search_mode: nominatim\n')
            loader = ConfigLoader(str(path))
            self.assertIsNone(loader.get_geocoding_search_mode())

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    @patch(PATCH_REQUESTS_GET)
    def test_geocoding_search_google_uses_backend(self, mock_get, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """With geocoding_search_mode google and Google key configured, view uses Google backend."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_google_api_key.return_value = 'test_google_key'
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

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

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_google_no_key_returns_503(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """With geocoding_search_mode google and Google API key not configured, view returns 503."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_google_api_key.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('Google', data['error'])

    @patch(PATCH_CONFIG_GOOGLE)
    @patch(PATCH_CONFIG_MAPTILER)
    @patch(PATCH_CONFIG_COMMON)
    @patch(PATCH_CONFIG_BACKENDS)
    def test_geocoding_search_unset_returns_503(self, mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google):
        """When geocoding_search_mode is None (missing or unset), view returns 503."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = None
        _set_config_mocks(mock_config_backends, mock_config_common, mock_config_maptiler, mock_config_google, mock_config)

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not configured', data['error'].lower())
        self.assertIn('geocoding_search_mode', data['error'])

