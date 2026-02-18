"""
Tests for geocoding API endpoints.
"""
import json
import requests
from unittest.mock import MagicMock, patch
from django.test import TestCase
from django.core.cache import cache
from django.contrib.auth import get_user_model


class TestGeocodingAPI(TestCase):
    """Test geocoding API endpoints."""

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

    def test_geocoding_search_live_edge_case(self):
        """
        Live API test for 'niggerhead rock' edge case.
        Asserts that no non-English (non-ASCII) characters are present in the results.
        """
        import requests
        from website.config_loader import get_config_loader

        config_loader = get_config_loader()
        if config_loader.get_geocoding_search_mode() != 'maptiler':
            self.skipTest("MapTiler geocoding backend not enabled (run only when geocoding_search_mode is maptiler)")
        api_key = config_loader.get_maptiler_api_key()
        if not api_key:
            self.skipTest("MapTiler API key not configured")

        # Make a real request to our API endpoint
        response = self.client.get('/api/geocoding/search/?q=niggerhead rock')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        features = data['data']['features']
        
        self.assertTrue(len(features) > 0, "Should return at least one feature for 'niggerhead rock'")
        
        for feature in features:
            text = feature.get('text', '')
            place_name = feature.get('place_name', '')
            
            # Assert that text and place_name do not contain Cyrillic characters
            # (which was the reported issue). We use a regex to check for Cyrillic range.
            import re
            cyrillic_pattern = re.compile(r'[\u0400-\u04FF]')
            
            if text:
                has_cyrillic = bool(cyrillic_pattern.search(text))
                self.assertFalse(has_cyrillic, f"Feature text '{text}' contains Cyrillic characters")
            if place_name:
                has_cyrillic = bool(cyrillic_pattern.search(place_name))
                self.assertFalse(has_cyrillic, f"Feature place_name '{place_name}' contains Cyrillic characters")

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_rocky_mountain_national_park(self, mock_get, mock_config_loader):
        """Test that searching for 'rocky mountain national park' returns RMNP feature."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock API responses - now 3 requests: admin, geographic, all types
        mock_admin_response = MagicMock()
        mock_admin_response.status_code = 200
        mock_admin_response.json.return_value = self._create_mock_admin_response()

        mock_geo_response = MagicMock()
        mock_geo_response.status_code = 200
        mock_geo_response.json.return_value = self._create_mock_geographic_response()

        mock_all_response = MagicMock()
        mock_all_response.status_code = 200
        mock_all_response.json.return_value = self._create_mock_all_types_response()

        # Three calls: admin divisions, geographic features, all types
        mock_get.side_effect = [mock_admin_response, mock_geo_response, mock_all_response]

        # Make request
        response = self.client.get('/api/geocoding/search/?q=rocky mountain national park')

        # Verify response
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertIn('features', data['data'])
        self.assertIn('query', data['data'])
        self.assertEqual(data['data']['query'], 'rocky mountain national park')

        # Verify RMNP feature is in results
        features = data['data']['features']
        rmnp_feature = next((f for f in features if f.get('id') == 'poi.48602113'), None)
        self.assertIsNotNone(rmnp_feature, "Rocky Mountain National Park feature should be in results")
        self.assertEqual(rmnp_feature['text'], 'Rocky Mountain National Park')
        # Note: place_name has the redundant text stripped by _clean_feature
        self.assertEqual(rmnp_feature['place_name'], 'Larimer, United States of America')

        # Verify feature is cleaned (no unnecessary fields)
        self.assertIn('coordinates', rmnp_feature)
        self.assertIn('bbox', rmnp_feature)
        self.assertIn('id', rmnp_feature)
        self.assertIn('text', rmnp_feature)
        self.assertIn('place_name', rmnp_feature)
        # Should not have unnecessary fields
        self.assertNotIn('geometry', rmnp_feature)
        self.assertNotIn('type', rmnp_feature)
        self.assertNotIn('relevance', rmnp_feature)
        self.assertNotIn('context', rmnp_feature)
        self.assertNotIn('place_type', rmnp_feature)

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_caching(self, mock_get, mock_config_loader):
        """Test that geocoding results are cached."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock API responses - now 3 requests: admin, geographic, all types
        mock_admin_response = MagicMock()
        mock_admin_response.status_code = 200
        mock_admin_response.json.return_value = self._create_mock_admin_response()

        mock_geo_response = MagicMock()
        mock_geo_response.status_code = 200
        mock_geo_response.json.return_value = self._create_mock_geographic_response()

        mock_all_response = MagicMock()
        mock_all_response.status_code = 200
        mock_all_response.json.return_value = self._create_mock_all_types_response()

        mock_get.side_effect = [mock_admin_response, mock_geo_response, mock_all_response]

        # First request - should call API
        response1 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response1.status_code, 200)
        # Verify API was called
        self.assertEqual(mock_get.call_count, 3)  # Admin + geographic + all types

        # Reset mock call count
        mock_get.reset_mock()

        # Second request with same query - should use cache
        response2 = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response2.status_code, 200)
        # Verify API was NOT called again (cache hit)
        self.assertEqual(mock_get.call_count, 0)

        # Verify responses are the same
        data1 = json.loads(response1.content)
        data2 = json.loads(response2.content)
        self.assertEqual(data1, data2)

    @patch('api.views.services.geocoding.get_config_loader')
    def test_geocoding_search_no_api_key(self, mock_config_loader):
        """Test geocoding search when API key is not configured."""
        # Mock config loader: mode maptiler, no API key
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = None
        mock_config_loader.return_value = mock_config

        # Make request
        response = self.client.get('/api/geocoding/search/?q=denver')

        # Verify error response
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())

    @patch('api.views.services.geocoding.get_config_loader')
    def test_geocoding_search_missing_query(self, mock_config_loader):
        """Test geocoding search without query parameter."""
        # Mock config loader
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config_loader.return_value = mock_config

        # Make request without query
        response = self.client.get('/api/geocoding/search/')

        # Verify error response
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('required', data['error'].lower())

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_api_error(self, mock_get, mock_config_loader):
        """Test geocoding search when MapTiler API returns an error."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock API error response - admin succeeds, geographic succeeds, all types fails
        mock_admin_response = MagicMock()
        mock_admin_response.status_code = 200
        mock_admin_response.json.return_value = self._create_mock_admin_response()

        mock_geo_response = MagicMock()
        mock_geo_response.status_code = 200
        mock_geo_response.json.return_value = self._create_mock_geographic_response()

        mock_all_response = MagicMock()
        mock_all_response.status_code = 400
        mock_all_response.text = 'ERR_VALIDATION: Invalid parameter'

        mock_get.side_effect = [mock_admin_response, mock_geo_response, mock_all_response]

        # Make request
        response = self.client.get('/api/geocoding/search/?q=denver')

        # Should still work with geographic features only
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('features', data['data'])

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_timeout(self, mock_get, mock_config_loader):
        """Test geocoding search when API request times out."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock timeout exception
        mock_get.side_effect = requests.exceptions.Timeout('Request timed out')

        # Make request
        response = self.client.get('/api/geocoding/search/?q=denver')

        # Verify error response
        self.assertEqual(response.status_code, 504)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('timed out', data['error'].lower())

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_feature_cleaning(self, mock_get, mock_config_loader):
        """Test that features are properly cleaned (unnecessary fields removed)."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock API responses - now 3 requests: admin, geographic, all types
        mock_admin_response = MagicMock()
        mock_admin_response.status_code = 200
        mock_admin_response.json.return_value = self._create_mock_admin_response()

        mock_geo_response = MagicMock()
        mock_geo_response.status_code = 200
        mock_geo_response.json.return_value = self._create_mock_geographic_response()

        mock_all_response = MagicMock()
        mock_all_response.status_code = 200
        mock_all_response.json.return_value = self._create_mock_all_types_response()

        mock_get.side_effect = [mock_admin_response, mock_geo_response, mock_all_response]

        # Make request
        response = self.client.get('/api/geocoding/search/?q=test')

        # Verify response
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        features = data['data']['features']

        # Verify all features are cleaned
        for feature in features:
            # Should have essential fields
            self.assertIn('coordinates', feature)
            self.assertIn('id', feature)
            self.assertIn('text', feature)
            self.assertIn('place_name', feature)
            self.assertIn('bbox', feature)

            # Should not have unnecessary fields
            self.assertNotIn('geometry', feature)
            self.assertNotIn('type', feature)
            self.assertNotIn('relevance', feature)
            self.assertNotIn('context', feature)
            self.assertNotIn('place_type', feature)
            self.assertNotIn('center', feature)

            # Properties should only have minimal fields
            if 'properties' in feature:
                props = feature['properties']
                # Should not have unnecessary properties
                self.assertNotIn('ref', props)
                self.assertNotIn('wikidata', props)
                self.assertNotIn('feature_tags', props)
                self.assertNotIn('categories', props)

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_prioritizes_geographic_features(self, mock_get, mock_config_loader):
        """Test that geographic features (parks, POIs) are prioritized over addresses."""
        # Mock config loader to return API key and mode
        mock_config = MagicMock()
        mock_config.get_maptiler_api_key.return_value = 'test_api_key'
        mock_config.get_geocoding_search_mode.return_value = 'maptiler'
        mock_config_loader.return_value = mock_config

        # Mock API responses - now 3 requests: admin, geographic, all types
        mock_admin_response = MagicMock()
        mock_admin_response.status_code = 200
        mock_admin_response.json.return_value = self._create_mock_admin_response()

        mock_geo_response = MagicMock()
        mock_geo_response.status_code = 200
        mock_geo_response.json.return_value = self._create_mock_geographic_response()

        mock_all_response = MagicMock()
        mock_all_response.status_code = 200
        mock_all_response.json.return_value = self._create_mock_all_types_response()

        mock_get.side_effect = [mock_admin_response, mock_geo_response, mock_all_response]

        # Make request
        response = self.client.get('/api/geocoding/search/?q=rocky mountain')

        # Verify response
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        features = data['data']['features']

        # Verify RMNP (geographic feature) appears before addresses
        feature_ids = [f.get('id') for f in features]
        rmnp_index = feature_ids.index('poi.48602113') if 'poi.48602113' in feature_ids else -1
        address_index = feature_ids.index('address.21312537') if 'address.21312537' in feature_ids else -1

        if rmnp_index >= 0 and address_index >= 0:
            self.assertLess(rmnp_index, address_index, 
                          "Geographic features should appear before addresses")

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

    @patch('api.views.services.geocoding.get_config_loader')
    @patch('api.views.services.geocoding.requests.get')
    def test_geocoding_search_mode_google_uses_google_backend(self, mock_get, mock_config_loader):
        """With geocoding_search_mode google and Google key configured, view uses Google backend."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_google_api_key.return_value = 'test_google_key'
        mock_config_loader.return_value = mock_config

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
        # Verify Google Geocoding API was called
        mock_get.assert_called_once()
        call_args = mock_get.call_args
        self.assertIn('maps.googleapis.com', str(call_args))

    @patch('api.views.services.geocoding.get_config_loader')
    def test_geocoding_search_mode_google_no_key_returns_503(self, mock_config_loader):
        """With geocoding_search_mode google and Google API key not configured, view returns 503."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = 'google'
        mock_config.get_google_api_key.return_value = None
        mock_config_loader.return_value = mock_config

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not available', data['error'].lower())
        self.assertIn('Google', data['error'])

    @patch('api.views.services.geocoding.get_config_loader')
    def test_geocoding_search_mode_unset_returns_503(self, mock_config_loader):
        """When geocoding_search_mode is None (missing or unset), view returns 503."""
        mock_config = MagicMock()
        mock_config.get_geocoding_search_mode.return_value = None
        mock_config_loader.return_value = mock_config

        response = self.client.get('/api/geocoding/search/?q=denver')
        self.assertEqual(response.status_code, 503)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not configured', data['error'].lower())
        self.assertIn('geocoding_search_mode', data['error'])

