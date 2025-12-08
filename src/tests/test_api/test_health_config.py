"""
Tests for health check and config API endpoints.
"""
import json
from unittest.mock import patch
from django.test import TestCase


class TestHealthConfigAPI(TestCase):
    """Test health check and config API endpoints."""

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

    def test_health_check_no_auth_required(self):
        """Test that health check doesn't require authentication."""
        # No login required
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
            
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 200)

    def test_get_config_no_auth_required(self):
        """Test that config endpoint doesn't require authentication."""
        # No login required
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)

