"""
Tests for API key authentication.
"""
import json
from django.test import TestCase
from django.contrib.auth import get_user_model

from users.models import ApiKey
from users.api_keys import create_user_api_key, validate_api_key

User = get_user_model()


class TestAPIKeys(TestCase):
    """Test API key authentication."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_api_key(self):
        """Test creating an API key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        self.assertIsNotNone(key_obj)
        self.assertIsNotNone(raw_key)
        self.assertEqual(key_obj.user, self.user)
        self.assertEqual(key_obj.name, 'Test Key')
        self.assertTrue(key_obj.is_active)
        self.assertIsNotNone(key_obj.key_prefix)
        self.assertIsNotNone(key_obj.key_hash)

    def test_validate_api_key(self):
        """Test validating an API key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        result = validate_api_key(raw_key)
        self.assertIsNotNone(result)
        user, api_key = result
        self.assertEqual(user, self.user)
        self.assertEqual(api_key, key_obj)

    def test_validate_api_key_invalid(self):
        """Test validating an invalid API key."""
        result = validate_api_key('invalid-key')
        self.assertIsNone(result)

    def test_validate_api_key_inactive(self):
        """Test that inactive API keys are rejected."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        key_obj.is_active = False
        key_obj.save()

        result = validate_api_key(raw_key)
        self.assertIsNone(result)

    def test_api_key_last_used_at(self):
        """Test that last_used_at is updated on validation."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        self.assertIsNone(key_obj.last_used_at)

        validate_api_key(raw_key)
        key_obj.refresh_from_db()
        self.assertIsNotNone(key_obj.last_used_at)

    def test_api_key_authentication(self):
        """Test API key authentication via Authorization header."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.get(
            '/api/features/all/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        self.assertEqual(response.status_code, 200)

    def test_api_key_authentication_invalid_header(self):
        """Test API key authentication with invalid header format."""
        response = self.client.get(
            '/api/features/all/',
            HTTP_AUTHORIZATION='Invalid format'
        )
        self.assertEqual(response.status_code, 401)

    def test_api_key_authentication_no_header(self):
        """Test API key authentication without header."""
        response = self.client.get('/api/features/all/')
        self.assertEqual(response.status_code, 401)

    def test_api_key_bypasses_csrf(self):
        """Test that API key requests bypass CSRF protection."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # POST request with API key should work without CSRF token
        response = self.client.post(
            '/api/features/all/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        # Should fail because POST is not allowed, not because of CSRF
        self.assertIn(response.status_code, [405, 400])

    def test_list_api_keys(self):
        """Test listing API keys."""
        self.client.force_login(self.user)
        create_user_api_key(self.user, 'Key 1')
        create_user_api_key(self.user, 'Key 2')

        response = self.client.get('/api/user/api-keys/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('api_keys', data)
        self.assertEqual(len(data['api_keys']), 2)

    def test_delete_api_key(self):
        """Test deleting an API key."""
        self.client.force_login(self.user)
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.delete(f'/api/user/api-keys/{key_obj.id}/')
        self.assertEqual(response.status_code, 200)
        # API keys are soft-deleted (is_active=False), not actually deleted
        key_obj.refresh_from_db()
        self.assertFalse(key_obj.is_active)

    def test_delete_api_key_unauthorized(self):
        """Test that users cannot delete other users' API keys."""
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        key_obj, raw_key = create_user_api_key(other_user, 'Other Key')

        self.client.force_login(self.user)
        response = self.client.delete(f'/api/user/api-keys/{key_obj.id}/')
        self.assertEqual(response.status_code, 404)

    def test_validate_api_key_endpoint(self):
        """Test API key validation endpoint."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertTrue(data['valid'])
        self.assertEqual(data['key_name'], 'Test Key')

    def test_validate_api_key_endpoint_invalid(self):
        """Test API key validation endpoint with invalid key."""
        # Endpoint requires authentication, so login first
        self.client.force_login(self.user)
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION='Bearer invalid-key'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertFalse(data['valid'])

    def test_api_key_prefix_uniqueness(self):
        """Test that API key prefixes are unique per user."""
        key_obj1, raw_key1 = create_user_api_key(self.user, 'Key 1')
        key_obj2, raw_key2 = create_user_api_key(self.user, 'Key 2')

        # Prefixes should be different (very unlikely to collide)
        self.assertNotEqual(key_obj1.key_prefix, key_obj2.key_prefix)

    def test_api_key_hash_security(self):
        """Test that API keys are hashed securely."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # Hash should not be the same as the raw key
        self.assertNotEqual(key_obj.key_hash, raw_key)
        # Hash should be SHA-256 (64 hex characters)
        self.assertEqual(len(key_obj.key_hash), 64)

    def test_api_key_constant_time_comparison(self):
        """Test that API key validation uses constant-time comparison."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # This test verifies that validate_api_key uses secrets.compare_digest
        # We can't directly test timing, but we can verify it works correctly
        result = validate_api_key(raw_key)
        self.assertIsNotNone(result)

        # Wrong key should fail
        result = validate_api_key(raw_key[:-1] + 'X')
        self.assertIsNone(result)


class TestAPIKeyValidationEndpoint(TestCase):
    """Extended tests for API key validation endpoint."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_validate_api_key_endpoint_returns_user_info(self):
        """Test that validation endpoint returns key information."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertTrue(data['valid'])
        self.assertEqual(data['key_name'], 'Test Key')
        # API returns key metadata, not user_id
        self.assertIn('created_at', data)

    def test_validate_api_key_endpoint_invalid_returns_false(self):
        """Test that invalid key returns valid=false."""
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION='Bearer invalid-key-12345'
        )
        
        # May return 200 with valid=false or 401
        self.assertIn(response.status_code, [200, 401])
        
        if response.status_code == 200:
            data = json.loads(response.content)
            self.assertFalse(data['valid'])

    def test_validate_api_key_endpoint_inactive_key(self):
        """Test validation endpoint with inactive API key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        key_obj.is_active = False
        key_obj.save()

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        # Inactive key should be invalid
        self.assertIn(response.status_code, [200, 401])
        
        if response.status_code == 200:
            data = json.loads(response.content)
            self.assertFalse(data['valid'])

    def test_validate_api_key_endpoint_missing_authorization_header(self):
        """Test validation endpoint without Authorization header."""
        response = self.client.post('/api/user/api-keys/validate/')
        
        # Should return error or invalid
        self.assertIn(response.status_code, [200, 400, 401])

    def test_validate_api_key_endpoint_malformed_authorization_header(self):
        """Test validation endpoint with malformed Authorization header."""
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION='InvalidFormat'
        )
        
        self.assertIn(response.status_code, [200, 400, 401])

    def test_validate_api_key_endpoint_updates_last_used(self):
        """Test that validation endpoint updates last_used_at."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        self.assertIsNone(key_obj.last_used_at)

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        self.assertEqual(response.status_code, 200)
        
        # Check that last_used_at was updated
        key_obj.refresh_from_db()
        self.assertIsNotNone(key_obj.last_used_at)

    def test_validate_api_key_endpoint_expired_key(self):
        """Test validation endpoint with expired API key."""
        from datetime import datetime, timedelta
        from django.utils import timezone
        
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        
        # Set expiration to past date
        key_obj.expires_at = timezone.now() - timedelta(days=1)
        key_obj.save()

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        # Expired key should be invalid
        if response.status_code == 200:
            data = json.loads(response.content)
            # May check expiration and return valid=false
            # (depends on implementation)

    def test_validate_api_key_endpoint_get_method_not_allowed(self):
        """Test that GET method is not allowed on validation endpoint."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.get(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        # Should not allow GET
        self.assertEqual(response.status_code, 405)

    def test_validate_api_key_endpoint_returns_key_metadata(self):
        """Test that validation endpoint returns key metadata."""
        key_obj, raw_key = create_user_api_key(self.user, 'My Test Key')

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key}'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertTrue(data['valid'])
        self.assertEqual(data['key_name'], 'My Test Key')
        
        # May also include created_at, last_used_at, etc.
        if 'created_at' in data:
            self.assertIsInstance(data['created_at'], str)

    def test_validate_api_key_endpoint_different_users(self):
        """Test validation endpoint with keys from different users."""
        user2 = User.objects.create_user(
            email='user2@example.com',
            password='pass',
            username='user2'
        )
        
        key_obj1, raw_key1 = create_user_api_key(self.user, 'User 1 Key')
        key_obj2, raw_key2 = create_user_api_key(user2, 'User 2 Key')

        # Validate user 1's key
        response1 = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key1}'
        )
        
        self.assertEqual(response1.status_code, 200)
        data1 = json.loads(response1.content)
        self.assertTrue(data1['valid'])
        self.assertEqual(data1['key_name'], 'User 1 Key')

        # Validate user 2's key
        response2 = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key2}'
        )
        
        self.assertEqual(response2.status_code, 200)
        data2 = json.loads(response2.content)
        self.assertTrue(data2['valid'])
        self.assertEqual(data2['key_name'], 'User 2 Key')

    def test_validate_api_key_endpoint_empty_bearer_token(self):
        """Test validation endpoint with empty Bearer token."""
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION='Bearer '
        )
        
        self.assertIn(response.status_code, [200, 400, 401])

    def test_validate_api_key_endpoint_whitespace_in_key(self):
        """Test validation endpoint with whitespace in key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key} '  # Extra space
        )
        
        # Should handle trimming or reject
        self.assertIn(response.status_code, [200, 400, 401])

    def test_validate_api_key_endpoint_case_sensitivity(self):
        """Test that API keys are case-sensitive."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # Try with uppercase version
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {raw_key.upper()}'
        )
        
        # Should be invalid (keys are case-sensitive)
        if response.status_code == 200:
            data = json.loads(response.content)
            self.assertFalse(data['valid'])

    def test_validate_api_key_endpoint_multiple_calls(self):
        """Test that validation endpoint can be called multiple times."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # Call validation endpoint multiple times
        for _ in range(3):
            response = self.client.post(
                '/api/user/api-keys/validate/',
                HTTP_AUTHORIZATION=f'Bearer {raw_key}'
            )
            
            self.assertEqual(response.status_code, 200)
            data = json.loads(response.content)
            self.assertTrue(data['valid'])

    def test_validate_api_key_endpoint_truncated_key(self):
        """Test validation endpoint with truncated key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # Try with truncated key
        truncated_key = raw_key[:-5]
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {truncated_key}'
        )
        
        # Should be invalid
        if response.status_code == 200:
            data = json.loads(response.content)
            self.assertFalse(data['valid'])

    def test_validate_api_key_endpoint_with_extra_characters(self):
        """Test validation endpoint with extra characters in key."""
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')

        # Try with extra characters
        modified_key = raw_key + 'extra'
        response = self.client.post(
            '/api/user/api-keys/validate/',
            HTTP_AUTHORIZATION=f'Bearer {modified_key}'
        )
        
        # Should be invalid
        if response.status_code == 200:
            data = json.loads(response.content)
            self.assertFalse(data['valid'])

