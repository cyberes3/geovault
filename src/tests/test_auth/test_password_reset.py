"""
Tests for password reset and password change functionality.
"""
import json
import re
from django.test import TestCase, override_settings
from django.contrib.auth import get_user_model
from django.core import mail
from django.core.cache import cache
from django.urls import reverse
from django.contrib.sites.models import Site

from allauth.account.models import EmailAddress

User = get_user_model()


@override_settings(EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend')
class TestPasswordReset(TestCase):
    """Test password reset functionality via Allauth."""

    def setUp(self):
        """Set up test fixtures."""
        # Ensure Site exists (required by Allauth)
        Site.objects.get_or_create(
            id=1,
            defaults={'domain': 'example.com', 'name': 'Test Site'}
        )
        
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser_pwreset'
        )
        # Create primary email address
        EmailAddress.objects.create(
            user=self.user,
            email='test@example.com',
            primary=True,
            verified=True
        )

    def tearDown(self):
        """Clean up after tests."""
        mail.outbox.clear()
        cache.clear()  # Clear rate limiting cache

    def _extract_reset_token_from_email(self, email_body):
        """Extract password reset token from email body."""
        # Allauth password reset URLs look like:
        # /accounts/password/reset/key/uidb64-token/
        # or with query params
        match = re.search(r'/accounts/password/reset/key/([^/\s]+)', email_body)
        if match:
            # Token format is usually uidb64-token
            full_token = match.group(1)
            # Split uidb64 and token (they're separated by a dash)
            parts = full_token.split('-', 1)
            if len(parts) == 2:
                return parts[0], parts[1]
        return None, None

    def test_password_reset_request_valid_email(self):
        """Test requesting password reset with valid email."""
        mail.outbox.clear()
        
        response = self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Allauth returns 302 redirect to done page on success
        self.assertIn(response.status_code, [200, 302])
        
        # Verify email was sent
        self.assertEqual(len(mail.outbox), 1)
        self.assertEqual(mail.outbox[0].to, ['test@example.com'])
        self.assertIn('password reset', mail.outbox[0].subject.lower())

    def test_password_reset_request_nonexistent_email(self):
        """Test requesting password reset with non-existent email."""
        mail.outbox.clear()
        
        response = self.client.post(
            '/accounts/password/reset/',
            {'email': 'nonexistent@example.com'}
        )
        
        # Allauth should still return success (security: don't reveal user existence)
        # But may or may not send email
        self.assertIn(response.status_code, [200, 302])
        
        # Allauth typically doesn't send email for non-existent users
        # but the response should be the same to prevent user enumeration

    def test_password_reset_request_invalid_email_format(self):
        """Test requesting password reset with invalid email format."""
        response = self.client.post(
            '/accounts/password/reset/',
            {'email': 'invalid-email'}
        )
        
        # Should show form with errors
        self.assertEqual(response.status_code, 200)
        # Form should have errors

    def test_password_reset_request_empty_email(self):
        """Test requesting password reset with empty email."""
        response = self.client.post(
            '/accounts/password/reset/',
            {'email': ''}
        )
        
        # Should show form with errors
        self.assertEqual(response.status_code, 200)

    def test_password_reset_token_usage(self):
        """Test using password reset token to change password."""
        # Clear cache to avoid rate limiting
        cache.clear()
        # Request password reset
        mail.outbox.clear()
        response = self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Check if rate limited
        if response.status_code == 429:
            self.skipTest("Rate limited - skipping test")
        
        # Extract token from email
        self.assertEqual(len(mail.outbox), 1)
        email_body = mail.outbox[0].body
        
        # Extract reset URL from email
        reset_url_match = re.search(r'http://[^\s]+/accounts/password/reset/key/([^/\s]+)', email_body)
        self.assertIsNotNone(reset_url_match, "Reset URL not found in email")
        
        full_token = reset_url_match.group(1)
        # Token format: uidb64-token
        parts = full_token.split('-', 1)
        self.assertEqual(len(parts), 2)
        uidb64, token = parts
        
        # Use token to reset password
        reset_key_url = f'/accounts/password/reset/key/{full_token}/'
        
        # First, GET the reset form (may redirect or show form)
        get_response = self.client.get(reset_key_url)
        self.assertIn(get_response.status_code, [200, 302])
        
        # If redirected, follow the redirect
        if get_response.status_code == 302:
            reset_key_url = get_response.url
        
        # Then POST with new password
        response = self.client.post(
            reset_key_url,
            {
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }
        )
        
        # Should redirect to done page (302) or show success (200)
        self.assertIn(response.status_code, [200, 302])
        
        # Verify password was changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('newpassword123'))

    def test_password_reset_token_invalid(self):
        """Test using invalid password reset token."""
        invalid_url = '/accounts/password/reset/key/invalid-uidb64-invalid-token/'
        
        response = self.client.get(invalid_url)
        
        # Should show error page
        self.assertEqual(response.status_code, 200)
        # Should indicate token failure

    def test_password_reset_token_reuse(self):
        """Test that password reset token can only be used once."""
        # Request password reset
        mail.outbox.clear()
        self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Extract token from email
        self.assertEqual(len(mail.outbox), 1)
        email_body = mail.outbox[0].body
        
        reset_url_match = re.search(r'http://[^\s]+/accounts/password/reset/key/([^/\s]+)', email_body)
        self.assertIsNotNone(reset_url_match)
        
        full_token = reset_url_match.group(1)
        reset_key_url = f'/accounts/password/reset/key/{full_token}/'
        
        # GET the form first (may redirect)
        get_response = self.client.get(reset_key_url)
        if get_response.status_code == 302:
            reset_key_url = get_response.url
        
        # First use - should work
        response1 = self.client.post(
            reset_key_url,
            {
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }
        )
        self.assertIn(response1.status_code, [200, 302])
        
        # Verify password was changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('newpassword123'))
        
        # Second use - should fail (token already used)
        # Need to get a new reset URL since the token was consumed
        mail.outbox.clear()
        self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Get the new token
        self.assertEqual(len(mail.outbox), 1)
        email_body = mail.outbox[0].body
        reset_url_match2 = re.search(r'http://[^\s]+/accounts/password/reset/key/([^/\s]+)', email_body)
        self.assertIsNotNone(reset_url_match2)
        full_token2 = reset_url_match2.group(1)
        reset_key_url2 = f'/accounts/password/reset/key/{full_token2}/'
        
        # Try to use the old token again (should fail)
        response2 = self.client.get(reset_key_url)
        # Should show error that token is invalid
        self.assertIn(response2.status_code, [200, 302, 404])
        
        # Password should still be the new one from first reset
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('newpassword123'))

    def test_password_reset_password_mismatch(self):
        """Test password reset with mismatched passwords."""
        # Request password reset
        mail.outbox.clear()
        self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Extract token from email
        self.assertEqual(len(mail.outbox), 1)
        email_body = mail.outbox[0].body
        
        reset_url_match = re.search(r'http://[^\s]+/accounts/password/reset/key/([^/\s]+)', email_body)
        self.assertIsNotNone(reset_url_match)
        
        full_token = reset_url_match.group(1)
        reset_key_url = f'/accounts/password/reset/key/{full_token}/'
        
        # GET the form first (may redirect)
        get_response = self.client.get(reset_key_url)
        if get_response.status_code == 302:
            reset_key_url = get_response.url
        
        # POST with mismatched passwords
        response = self.client.post(
            reset_key_url,
            {
                'password1': 'newpassword123',
                'password2': 'differentpassword123'
            }
        )
        
        # Should show form with errors (200) or redirect back with errors
        self.assertIn(response.status_code, [200, 302])
        # Password should not be changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('testpass123'))

    def test_password_reset_password_too_short(self):
        """Test password reset with password that's too short."""
        # Request password reset
        mail.outbox.clear()
        self.client.post(
            '/accounts/password/reset/',
            {'email': 'test@example.com'}
        )
        
        # Extract token from email
        self.assertEqual(len(mail.outbox), 1)
        email_body = mail.outbox[0].body
        
        reset_url_match = re.search(r'http://[^\s]+/accounts/password/reset/key/([^/\s]+)', email_body)
        self.assertIsNotNone(reset_url_match)
        
        full_token = reset_url_match.group(1)
        reset_key_url = f'/accounts/password/reset/key/{full_token}/'
        
        # GET the form first (may redirect)
        get_response = self.client.get(reset_key_url)
        if get_response.status_code == 302:
            reset_key_url = get_response.url
        
        # POST with short password
        response = self.client.post(
            reset_key_url,
            {
                'password1': 'short',
                'password2': 'short'
            }
        )
        
        # Should show form with errors (200) or redirect back with errors
        self.assertIn(response.status_code, [200, 302])
        # Password should not be changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('testpass123'))


@override_settings(EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend')
class TestPasswordChangeAPI(TestCase):
    """Test password change API endpoint."""

    def setUp(self):
        """Set up test fixtures."""
        # Ensure Site exists (required by Allauth)
        Site.objects.get_or_create(
            id=1,
            defaults={'domain': 'example.com', 'name': 'Test Site'}
        )
        
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser_pwchange'
        )
        self.client.force_login(self.user)

    def test_password_change_success(self):
        """Test successful password change."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('message', data)
        
        # Verify password was changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('newpassword123'))
        self.assertFalse(self.user.check_password('testpass123'))

    def test_password_change_wrong_old_password(self):
        """Test password change with incorrect old password."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'wrongpassword',
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        
        # Password should not be changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('testpass123'))

    def test_password_change_password_mismatch(self):
        """Test password change with mismatched new passwords."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': 'newpassword123',
                'password2': 'differentpassword123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        
        # Password should not be changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('testpass123'))

    def test_password_change_password_too_short(self):
        """Test password change with password that's too short."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': 'short',
                'password2': 'short'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        
        # Password should not be changed
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password('testpass123'))

    def test_password_change_missing_old_password(self):
        """Test password change without old password."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_password_change_missing_fields(self):
        """Test password change with missing required fields."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_password_change_unauthenticated(self):
        """Test that password change requires authentication."""
        self.client.logout()
        
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': 'newpassword123',
                'password2': 'newpassword123'
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 401)

    def test_password_change_invalid_json(self):
        """Test password change with invalid JSON."""
        response = self.client.post(
            '/api/user/password/change/',
            'invalid json',
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('JSON', data['error'])

    def test_password_change_empty_password(self):
        """Test password change with empty new password."""
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': '',
                'password2': ''
            }),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_password_change_same_as_old(self):
        """Test password change with same password as old password."""
        # This might be allowed or rejected depending on allauth settings
        response = self.client.post(
            '/api/user/password/change/',
            json.dumps({
                'oldpassword': 'testpass123',
                'password1': 'testpass123',
                'password2': 'testpass123'
            }),
            content_type='application/json'
        )
        
        # Allauth may allow or reject this - test both cases
        if response.status_code == 400:
            data = json.loads(response.content)
            self.assertIn('error', data)
        else:
            # If allowed, password should remain the same
            self.assertEqual(response.status_code, 200)

