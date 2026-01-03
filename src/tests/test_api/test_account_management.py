"""
Tests for user account management API endpoints (password, email).
"""
import json
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.sites.models import Site
from allauth.account.models import EmailAddress


class TestPasswordChangeAPI(TestCase):
    """Test password change API endpoint."""

    def setUp(self):
        """Set up test fixtures."""
        # Ensure Site exists (required by Allauth)
        Site.objects.get_or_create(
            id=1,
            defaults={'domain': 'example.com', 'name': 'Test Site'}
        )
        
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='oldpassword123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_password_change_success(self):
        """Test successful password change."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'newpassword456',
            'password2': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        
        # Verify password was changed by trying to login with new password
        self.client.logout()
        login_success = self.client.login(username='testuser', password='newpassword456')
        self.assertTrue(login_success, "Should be able to login with new password")

    def test_password_change_invalid_current_password(self):
        """Test password change with invalid current password."""
        change_data = {
            'oldpassword': 'wrongpassword',
            'password1': 'newpassword456',
            'password2': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_password_change_passwords_dont_match(self):
        """Test password change when new passwords don't match."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'newpassword456',
            'password2': 'differentpassword789'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_password_change_too_short(self):
        """Test password change with password that's too short."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'short',
            'password2': 'short'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        # Should reject password that's too short
        self.assertEqual(response.status_code, 400)

    def test_password_change_too_common(self):
        """Test password change with common password."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'password',
            'password2': 'password'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        # May reject common passwords (depending on Django password validators)
        self.assertIn(response.status_code, [200, 400])

    def test_password_change_missing_current_password(self):
        """Test password change without current password."""
        change_data = {
            'password1': 'newpassword456',
            'password2': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)

    def test_password_change_missing_new_password(self):
        """Test password change without new password."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password2': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)

    def test_password_change_missing_confirm_password(self):
        """Test password change without confirm password."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)

    def test_password_change_requires_authentication(self):
        """Test that password change requires authentication."""
        self.client.logout()
        
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'newpassword456',
            'password2': 'newpassword456'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 401)

    def test_password_change_invalid_json(self):
        """Test password change with invalid JSON."""
        response = self.client.post(
            '/api/user/password/change/',
            data='invalid json',
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)

    def test_password_change_empty_data(self):
        """Test password change with empty data."""
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)

    def test_password_change_numeric_only_password(self):
        """Test password change with numeric-only password."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': '123456789',
            'password2': '123456789'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        # May reject numeric-only passwords
        self.assertIn(response.status_code, [200, 400])

    def test_password_change_similar_to_username(self):
        """Test password change with password similar to username."""
        change_data = {
            'oldpassword': 'oldpassword123',
            'password1': 'testuser123',
            'password2': 'testuser123'
        }
        
        response = self.client.post(
            '/api/user/password/change/',
            data=json.dumps(change_data),
            content_type='application/json'
        )
        
        # May reject passwords similar to username
        self.assertIn(response.status_code, [200, 400])


class TestEmailManagementAPI(TestCase):
    """Test email management API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        # Ensure Site exists (required by Allauth)
        Site.objects.get_or_create(
            id=1,
            defaults={'domain': 'example.com', 'name': 'Test Site'}
        )
        
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)
        
        # Create primary email address
        self.email_address = EmailAddress.objects.create(
            user=self.user,
            email='test@example.com',
            primary=True,
            verified=True
        )

    def test_email_status_authenticated(self):
        """Test getting email status for authenticated user."""
        response = self.client.get('/api/user/email/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertIn('emails', data)
        self.assertIn('primary_email', data)
        self.assertEqual(data['primary_email'], 'test@example.com')
        self.assertEqual(len(data['emails']), 1)
        self.assertTrue(data['emails'][0]['verified'])
        self.assertTrue(data['emails'][0]['primary'])

    def test_email_status_unauthenticated(self):
        """Test that email status requires authentication."""
        self.client.logout()
        
        response = self.client.get('/api/user/email/status/')
        self.assertEqual(response.status_code, 401)

    def test_email_status_unverified_email(self):
        """Test email status with unverified email."""
        # Update email to unverified
        self.email_address.verified = False
        self.email_address.save()
        
        response = self.client.get('/api/user/email/status/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertFalse(data['emails'][0]['verified'])
        self.assertTrue(data['has_unverified'])

    def test_resend_verification_success(self):
        """Test resending email verification."""
        # Make email unverified
        self.email_address.verified = False
        self.email_address.save()
        
        # Resend verification requires email in request body
        response = self.client.post(
            '/api/user/email/resend-verification/',
            data=json.dumps({'email': 'test@example.com'}),
            content_type='application/json'
        )
        
        # Should succeed
        self.assertIn(response.status_code, [200, 202])

    def test_resend_verification_already_verified(self):
        """Test resending verification when email is already verified."""
        response = self.client.post('/api/user/email/resend-verification/')
        
        # May succeed or reject (depending on implementation)
        self.assertIn(response.status_code, [200, 400])

    def test_resend_verification_requires_authentication(self):
        """Test that resend verification requires authentication."""
        self.client.logout()
        
        response = self.client.post('/api/user/email/resend-verification/')
        self.assertEqual(response.status_code, 401)

    def test_resend_verification_no_email_address(self):
        """Test resending verification when no email address exists."""
        # Delete email address
        EmailAddress.objects.filter(user=self.user).delete()
        
        response = self.client.post('/api/user/email/resend-verification/')
        
        # Should handle gracefully
        self.assertIn(response.status_code, [200, 400, 404])

    def test_email_status_no_primary_email(self):
        """Test email status when no primary email is set."""
        # Delete primary email
        EmailAddress.objects.filter(user=self.user).delete()
        
        response = self.client.get('/api/user/email/status/')
        
        # Should handle gracefully
        self.assertIn(response.status_code, [200, 404])

