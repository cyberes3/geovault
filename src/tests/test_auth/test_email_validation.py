"""
Tests for email validation and email management API endpoints.
"""
import json
from django.test import TestCase, override_settings
from django.contrib.auth import get_user_model
from django.core import mail
from django.core.cache import cache
from django.utils import timezone
from datetime import timedelta

from allauth.account.models import EmailAddress

User = get_user_model()


@override_settings(EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend')
class TestEmailValidation(TestCase):
    """Test email format validation and email management API."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        # Create primary email address
        EmailAddress.objects.create(
            user=self.user,
            email='test@example.com',
            primary=True,
            verified=True
        )
        self.client.force_login(self.user)

    def tearDown(self):
        """Clean up cache after each test."""
        cache.clear()

    def test_email_status_api(self):
        """Test email status API endpoint."""
        response = self.client.get('/api/user/email/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('emails', data)
        self.assertIn('primary_email', data)
        self.assertIn('pending_verification', data)
        self.assertIn('has_unverified', data)
        self.assertIn('resend_cooldown_remaining', data)
        self.assertIn('resend_on_cooldown', data)
        
        self.assertEqual(len(data['emails']), 1)
        self.assertEqual(data['emails'][0]['email'], 'test@example.com')
        self.assertTrue(data['emails'][0]['verified'])
        self.assertTrue(data['emails'][0]['primary'])

    def test_email_status_api_multiple_emails(self):
        """Test email status API with multiple email addresses."""
        # Add additional email addresses
        EmailAddress.objects.create(
            user=self.user,
            email='secondary@example.com',
            primary=False,
            verified=False
        )
        EmailAddress.objects.create(
            user=self.user,
            email='tertiary@example.com',
            primary=False,
            verified=True
        )
        
        response = self.client.get('/api/user/email/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(len(data['emails']), 3)
        self.assertEqual(data['primary_email'], 'test@example.com')
        self.assertEqual(len(data['pending_verification']), 1)
        self.assertIn('secondary@example.com', data['pending_verification'])

    def test_email_status_api_unauthenticated(self):
        """Test that email status requires authentication."""
        self.client.logout()
        
        response = self.client.get('/api/user/email/status/')
        
        self.assertEqual(response.status_code, 401)

    def test_resend_verification_email(self):
        """Test resending verification email."""
        # Create unverified email
        EmailAddress.objects.filter(user=self.user).delete()
        email_addr = EmailAddress.objects.create(
            user=self.user,
            email='unverified@example.com',
            primary=True,
            verified=False
        )
        
        mail.outbox.clear()
        
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'unverified@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('message', data)
        self.assertEqual(data['cooldown_remaining'], 60)
        self.assertFalse(data['on_cooldown'])
        
        # Verify email was sent
        self.assertEqual(len(mail.outbox), 1)
        self.assertIn('unverified@example.com', mail.outbox[0].to)

    def test_resend_verification_email_cooldown(self):
        """Test that resend verification respects cooldown period."""
        # Create unverified email
        EmailAddress.objects.filter(user=self.user).delete()
        email_addr = EmailAddress.objects.create(
            user=self.user,
            email='unverified@example.com',
            primary=True,
            verified=False
        )
        
        # First request
        response1 = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'unverified@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        
        # Immediate second request should be on cooldown
        response2 = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'unverified@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response2.status_code, 429)
        data = json.loads(response2.content)
        self.assertIn('error', data)
        self.assertTrue(data['on_cooldown'])
        self.assertIn('cooldown_remaining', data)
        self.assertGreater(data['cooldown_remaining'], 0)
        self.assertLessEqual(data['cooldown_remaining'], 60)

    def test_resend_verification_email_after_cooldown(self):
        """Test that resend works after cooldown expires."""
        # Create unverified email
        EmailAddress.objects.filter(user=self.user).delete()
        email_addr = EmailAddress.objects.create(
            user=self.user,
            email='unverified@example.com',
            primary=True,
            verified=False
        )
        
        # First request
        response1 = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'unverified@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        
        # Manually expire the cooldown by setting cache to past time
        cache_key = f'email_verification_resend_{self.user.id}_unverified@example.com'
        past_time = timezone.now() - timedelta(seconds=61)
        cache.set(cache_key, past_time, timeout=60)
        
        # Second request should work now
        response2 = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'unverified@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response2.status_code, 200)
        data = json.loads(response2.content)
        self.assertFalse(data['on_cooldown'])

    def test_resend_verification_already_verified(self):
        """Test that resend fails for already verified email."""
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'test@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('already verified', data['error'].lower())

    def test_resend_verification_nonexistent_email(self):
        """Test that resend fails for non-existent email."""
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'nonexistent@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 404)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not found', data['error'].lower())

    def test_resend_verification_empty_email(self):
        """Test that resend requires email field."""
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': ''}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_resend_verification_unauthenticated(self):
        """Test that resend verification requires authentication."""
        self.client.logout()
        
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'test@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 401)

    def test_resend_verification_other_user_email(self):
        """Test that users cannot resend verification for other users' emails."""
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        EmailAddress.objects.create(
            user=other_user,
            email='other@example.com',
            primary=True,
            verified=False
        )
        
        response = self.client.post(
            '/api/user/email/resend-verification/',
            json.dumps({'email': 'other@example.com'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 404)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('not found', data['error'].lower())

    def test_resend_verification_invalid_json(self):
        """Test resend verification with invalid JSON."""
        response = self.client.post(
            '/api/user/email/resend-verification/',
            'invalid json',
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
        self.assertIn('JSON', data['error'])

