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
             patch('api.views.health.check_postgis_installation', return_value=True):
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 200)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'healthy')

    def test_health_check_unhealthy(self):
        """Test health check when system is unhealthy."""
        with patch('api.views.health.check_database_connection', return_value=False):
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 500)
            data = json.loads(response.content)
            self.assertEqual(data['status'], 'unhealthy')

    def test_health_check_exception(self):
        """Test health check when exception occurs."""
        with patch('api.views.health.check_database_connection', side_effect=Exception('Error')):
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
             patch('api.views.health.check_postgis_installation', return_value=True):
            response = self.client.get('/api/health/')
            self.assertEqual(response.status_code, 200)

    def test_get_config_no_auth_required(self):
        """Test that config endpoint doesn't require authentication."""
        # No login required
        response = self.client.get('/api/config/')
        self.assertEqual(response.status_code, 200)

