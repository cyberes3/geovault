"""
Tests for health check and config API endpoints.
"""
import json
from unittest.mock import patch
from django.test import TestCase
from django.contrib.auth import get_user_model

User = get_user_model()


class TestHealthConfigAPI(TestCase):
    """Test health check and config API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_health_check(self):
        """Test health check endpoint."""
        with patch('api.views.health.check_database_connection', return_value=True), \
             patch('api.views.health.check_redis_connection', return_value=True), \
             patch('api.views.health.check_postgis_installation', return_value=True), \
             patch('api.views.health.check_overpass_api', return_value=True), \
             patch('api.views.health.check_elevation_api', return_value=True), \
             patch('api.views.health.check_maptiler_geocoding_api', return_value=True), \
             patch('website.settings_utils.get_required_setting') as mock_get_setting, \
             patch('website.config_loader.get_config_loader') as mock_get_config:
            # Mock get_required_setting to return False for ELEVATION_API_ENABLED (disabled)
            def get_setting_side_effect(attr_name):
                if attr_name == 'ELEVATION_API_ENABLED':
                    return False
                return 'default_value'
            mock_get_setting.side_effect = get_setting_side_effect
            
            # Mock config loader
            mock_config = mock_get_config.return_value
            mock_config.get_bool.return_value = False  # reverse_geocoding.enabled = False
            mock_config.get_maptiler_api_key.return_value = None  # No API key
            
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 200)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'healthy')

    def test_health_check_unhealthy(self):
        """Test health check when system is unhealthy."""
        with patch('api.views.health.check_database_connection', return_value=False), \
             patch('api.views.health.check_redis_connection', return_value=True), \
             patch('api.views.health.check_postgis_installation', return_value=True), \
             patch('api.views.health.check_overpass_api', return_value=True), \
             patch('api.views.health.check_elevation_api', return_value=True), \
             patch('api.views.health.check_maptiler_geocoding_api', return_value=True), \
             patch('website.settings_utils.get_required_setting') as mock_get_setting, \
             patch('website.config_loader.get_config_loader') as mock_get_config:
            # Mock get_required_setting to return False for ELEVATION_API_ENABLED (disabled)
            def get_setting_side_effect(attr_name):
                if attr_name == 'ELEVATION_API_ENABLED':
                    return False
                return 'default_value'
            mock_get_setting.side_effect = get_setting_side_effect
            
            # Mock config loader
            mock_config = mock_get_config.return_value
            mock_config.get_bool.return_value = False  # reverse_geocoding.enabled = False
            mock_config.get_maptiler_api_key.return_value = None  # No API key
            
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 500)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'unhealthy')

    def test_health_check_exception(self):
        """Test health check when exception occurs."""
        with patch('api.views.health.check_database_connection', side_effect=Exception('Error')), \
             patch('api.views.health.check_redis_connection', return_value=True), \
             patch('api.views.health.check_postgis_installation', return_value=True), \
             patch('api.views.health.check_overpass_api', return_value=True), \
             patch('api.views.health.check_elevation_api', return_value=True), \
             patch('api.views.health.check_maptiler_geocoding_api', return_value=True), \
             patch('website.settings_utils.get_required_setting') as mock_get_setting, \
             patch('website.config_loader.get_config_loader') as mock_get_config:
            # Mock get_required_setting to return False for ELEVATION_API_ENABLED (disabled)
            def get_setting_side_effect(attr_name):
                if attr_name == 'ELEVATION_API_ENABLED':
                    return False
                return 'default_value'
            mock_get_setting.side_effect = get_setting_side_effect
            
            # Mock config loader
            mock_config = mock_get_config.return_value
            mock_config.get_bool.return_value = False  # reverse_geocoding.enabled = False
            mock_config.get_maptiler_api_key.return_value = None  # No API key
            
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 500)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'unhealthy')

    def test_get_config(self):
        """Test getting server configuration."""
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('systemTagPrefixes', data)
        self.assertIsInstance(data['systemTagPrefixes'], list)
        self.assertIn('tagPriorities', data)
        self.assertIsInstance(data['tagPriorities'], dict)

    def test_health_check_auth_required(self):
        """Test that health check requires authentication."""
        # Logout to test unauthenticated access
        self.client.logout()
        
        response = self.client.get('/api/health/')
        self.assertEqual(response.status_code, 401)
        data = json.loads(response.content)
        self.assertEqual(data['error'], 'Unauthorized')

    def test_health_check_with_api_key(self):
        """Test that health check works with API key authentication."""
        from users.api_keys import create_user_api_key
        
        # Logout to test API key auth
        self.client.logout()
        
        # Create API key
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        
        with patch('api.views.health.check_database_connection', return_value=True), \
             patch('api.views.health.check_redis_connection', return_value=True), \
             patch('api.views.health.check_postgis_installation', return_value=True), \
             patch('api.views.health.check_overpass_api', return_value=True), \
             patch('api.views.health.check_elevation_api', return_value=True), \
             patch('api.views.health.check_maptiler_geocoding_api', return_value=True), \
             patch('website.settings_utils.get_required_setting') as mock_get_setting, \
             patch('website.config_loader.get_config_loader') as mock_get_config:
            # Mock get_required_setting to return False for ELEVATION_API_ENABLED (disabled)
            def get_setting_side_effect(attr_name):
                if attr_name == 'ELEVATION_API_ENABLED':
                    return False
                # Return a default value for other settings
                return 'default_value'
            mock_get_setting.side_effect = get_setting_side_effect
            
            # Mock config loader to return a config with reverse_geocoding disabled
            mock_config = mock_get_config.return_value
            mock_config.get_bool.return_value = False  # reverse_geocoding.enabled = False
            mock_config.get_maptiler_api_key.return_value = None  # No API key
            
            response = self.client.get(
                '/api/health/',
                HTTP_AUTHORIZATION=f'Bearer {raw_key}'
            )
            self.assertEqual(response.status_code, 200)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'healthy')

    def test_get_config_no_auth_required(self):
        """Test that config endpoint doesn't require authentication."""
        # No login required
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)

    @patch('api.views.config.get_config_loader')
    def test_maptiler_config_excludes_api_key_when_proxying(self, mock_get_config_loader):
        """Test that MapTiler API key is excluded from config when proxy_tiles is enabled."""
        mock_config_loader = mock_get_config_loader.return_value
        mock_config_loader.get_maptiler_api_key.return_value = 'test-api-key-12345'
        # Mock get_bool to return True specifically for 'maptiler.proxy_tiles'
        def get_bool_side_effect(key, default=False):
            if key == 'maptiler.proxy_tiles':
                return True
            return default
        mock_config_loader.get_bool.side_effect = get_bool_side_effect
        
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Verify MapTiler config is present
        self.assertIn('maptiler', data)
        maptiler_config = data['maptiler']
        
        # Verify proxy_tiles is True
        self.assertTrue(maptiler_config.get('proxy_tiles'))
        
        # Verify API key is NOT present
        self.assertNotIn('apiKey', maptiler_config, "API key should not be exposed when proxying is enabled")

    @patch('api.views.config.get_config_loader')
    def test_maptiler_config_includes_api_key_when_not_proxying(self, mock_get_config_loader):
        """Test that MapTiler API key is included in config when proxy_tiles is disabled."""
        mock_config_loader = mock_get_config_loader.return_value
        mock_config_loader.get_maptiler_api_key.return_value = 'test-api-key-12345'
        # Mock get_bool to return False specifically for 'maptiler.proxy_tiles'
        def get_bool_side_effect(key, default=False):
            if key == 'maptiler.proxy_tiles':
                return False
            return default
        mock_config_loader.get_bool.side_effect = get_bool_side_effect
        
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Verify MapTiler config is present
        self.assertIn('maptiler', data)
        maptiler_config = data['maptiler']
        
        # Verify proxy_tiles is False
        self.assertFalse(maptiler_config.get('proxy_tiles'))
        
        # Verify API key IS present
        self.assertIn('apiKey', maptiler_config, "API key should be exposed when proxying is disabled")
        self.assertEqual(maptiler_config['apiKey'], 'test-api-key-12345')

