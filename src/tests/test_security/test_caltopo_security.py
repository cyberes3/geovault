"""
Security tests for CalTopo integration.
"""
from django.test import TestCase
from django.contrib.auth import get_user_model

from api.models import CalTopoUser

User = get_user_model()


class TestCalTopoSecurity(TestCase):
    """Test security aspects of CalTopo integration."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
    
    def test_map_id_and_feature_id_are_validated_pattern(self):
        """Test map_id and feature_id are validated (pattern, length)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Test invalid map_id (contains invalid characters)
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map<script>alert(1)</script>',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('error', data)
        
        # Test invalid feature_id (too long)
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'a' * 200,  # Too long
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('error', data)
    
    def test_map_id_and_feature_id_are_validated_length(self):
        """Test map_id and feature_id are validated (length)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Test empty map_id
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': '',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        
        # Test empty feature_id
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': '',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
    
    def test_feature_class_is_validated_whitelist(self):
        """Test feature_class is validated (whitelist)."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Test invalid feature_class
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'InvalidClass'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('Invalid feature_class', data['error'])
        
        # Test valid feature_class
        response = self.client.post('/api/caltopo/import/feature/', {
            'map_id': 'map1',
            'feature_id': 'feature1',
            'feature_class': 'Marker'
        }, content_type='application/json')
        
        # Should not fail on validation (may fail on other things like missing feature)
        # But validation should pass
        self.assertNotEqual(response.status_code, 400)  # Should not be validation error
    
    def test_credential_fields_are_validated_length(self):
        """Test credential fields are validated (length, format)."""
        # Test account_id too short
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc12',  # 5 characters, should be 6
            'credential_id': '123456789012',
            'credential_key': 'test-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn('error', data)
        
        # Test account_id too long
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc1234',  # 7 characters, should be 6
            'credential_id': '123456789012',
            'credential_key': 'test-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        
        # Test credential_id too short
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc123',
            'credential_id': '12345678901',  # 11 characters, should be 12
            'credential_key': 'test-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
        
        # Test credential_id too long
        response = self.client.post('/api/caltopo/connect/', {
            'account_id': 'abc123',
            'credential_id': '1234567890123',  # 13 characters, should be 12
            'credential_key': 'test-key'
        }, content_type='application/json')
        
        self.assertEqual(response.status_code, 400)
    
    def test_rate_limiting_prevents_abuse(self):
        """Test rate limiting prevents abuse (multiple rapid requests)."""
        from django.core.cache import caches
        
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        # Clear cache
        cache = caches['rate_limiting']
        cache.clear()
        
        # Make first request (should succeed)
        with patch('geo_lib.services.caltopo_service.list_maps', return_value=[]):
            response = self.client.get('/api/caltopo/maps/')
            self.assertEqual(response.status_code, 200)
        
        # Make second request immediately (should be rate limited)
        with patch('geo_lib.services.caltopo_service.list_maps', return_value=[]):
            response = self.client.get('/api/caltopo/maps/')
            self.assertEqual(response.status_code, 429)
            data = response.json()
            self.assertIn('Rate limit exceeded', data['error'])
    
    def test_account_id_is_not_exposed_in_status_endpoint(self):
        """Test account_id is NOT exposed in status endpoint."""
        CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key='test-key'
        )
        
        response = self.client.get('/api/caltopo/status/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        
        # Should not contain account_id
        self.assertNotIn('account_id', data)
        self.assertTrue(data['connected'])

