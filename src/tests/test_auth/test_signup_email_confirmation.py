"""
Tests for signup email confirmation to ensure only one email is sent.
"""
import json
import re
from datetime import timedelta
from django.test import TestCase, override_settings
from django.contrib.auth import get_user_model
from django.core import mail
from django.core.cache import cache
from django.contrib.sites.models import Site
from django.urls import reverse
from django.utils import timezone

from django.test import RequestFactory

from allauth.account.models import EmailAddress, EmailConfirmation
from users.adapters import NoUsernameAccountAdapter
from users.constants import EMAIL_VERIFICATION_CACHE_KEY, EMAIL_VERIFICATION_COOLDOWN_SECONDS

User = get_user_model()


@override_settings(
    EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend',
    ACCOUNT_EMAIL_VERIFICATION='optional',  # Match production: verification is optional, users request it manually
    ACCOUNT_CONFIRM_EMAIL_ON_GET=True  # Enable auto-confirm on GET so clicking the link confirms the email
)
class TestSignupEmailConfirmation(TestCase):
    """Test email confirmation functionality (via resend API since verification is optional)."""

    def setUp(self):
        """Set up test fixtures."""
        # Ensure Site exists (required by Allauth)
        Site.objects.get_or_create(
            id=1,
            defaults={'domain': 'example.com', 'name': 'Test Site'}
        )
        # Clear mail outbox before each test
        mail.outbox.clear()
        
        # Clean database for test isolation
        User.objects.all().delete()
        EmailAddress.objects.all().delete()
        EmailConfirmation.objects.all().delete()

    def tearDown(self):
        """Clean up after tests."""
        mail.outbox.clear()

    def test_signup_sends_single_verification_email(self):
        """Test that signup sends exactly ONE verification email, not duplicates.
        
        With ACCOUNT_EMAIL_VERIFICATION='optional' and our custom adapter,
        a verification email IS sent during signup, but users can still use
        the site without verifying. The email allows them to verify if they choose to.
        """
        # Sign up a new user
        signup_data = {
            'email': 'newuser@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(
            reverse('account_signup'),
            signup_data,
            follow=True
        )
        
        # Signup should succeed
        self.assertIn(response.status_code, [200, 302])
        
        # Verify user was created
        user = User.objects.get(email='newuser@example.com')
        self.assertIsNotNone(user)
        
        # Verify EmailAddress was created
        email_address = EmailAddress.objects.get(user=user, email='newuser@example.com')
        self.assertIsNotNone(email_address)
        self.assertFalse(email_address.verified, "Email should be unverified after signup")
        
        # Should send EXACTLY 1 verification email during signup
        email_count = len(mail.outbox)
        self.assertEqual(email_count, 1, f"Signup must send exactly 1 email, got {email_count}")
        
        # Verify email content
        email = mail.outbox[0]
        self.assertIn('newuser@example.com', email.to)
        self.assertIn('/accounts/confirm-email/', email.body or '',
                      "Email must contain confirmation link")

    def test_signup_via_api_sets_username_to_uuid(self):
        """Sign up via the real account_signup API and assert username is a UUID.

        Previously the adapter set a UUID in save_user, but allauth's
        DefaultAccountAdapter overwrote it (user_username from form, then
        populate_username from email), so username became the email local part.
        This test ensures the fix: username is a UUID.
        """
        signup_data = {
            'email': 'test.user.name@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        response = self.client.post(
            reverse('account_signup'),
            signup_data,
            follow=True,
        )
        self.assertIn(response.status_code, [200, 302], "Signup should succeed")
        user = User.objects.get(email='test.user.name@example.com')
        self.assertIsNotNone(user)
        # Username must be a UUID (e.g. from uuid.uuid4(): 8-4-4-4-12 hex with hyphens)
        uuid_re = re.compile(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z",
            re.IGNORECASE,
        )
        self.assertRegex(
            user.username,
            uuid_re,
            f"Username must be a UUID, got {user.username!r}",
        )

    def test_resend_api_sends_single_email_with_confirmation_link(self):
        """Test that requesting verification via resend API sends exactly one email with a valid link.
        
        This tests the user settings page flow where users click to resend verification.
        """
        signup_data = {
            'email': 'testuser@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(reverse('account_signup'), signup_data, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        user = User.objects.get(email='testuser@example.com')
        email_addr = EmailAddress.objects.get(user=user, email='testuser@example.com')
        self.client.force_login(user)
        
        # Clear cache and mail outbox (signup may have sent an email)
        cache.clear()
        mail.outbox.clear()
        
        # Request verification email from user settings page
        response = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'testuser@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Check exactly 1 email was sent (not 2+)
        email_count = len(mail.outbox)
        self.assertEqual(email_count, 1, f"Resend must send exactly 1 email, got {email_count}")
        
        # Verify recipient and content
        email = mail.outbox[0]
        self.assertIn('testuser@example.com', email.to)
        self.assertIn('/accounts/confirm-email/', email.body or '',
                      "Email must contain confirmation link")

    def test_multiple_resend_requests_within_cooldown_blocked(self):
        """Test that multiple resend requests within 60-second cooldown are blocked.
        
        This simulates a user clicking 'Resend Verification' multiple times
        in quick succession from their settings page.
        """
        # Create a user with unverified email
        user = User.objects.create_user(
            email='multipletest@example.com',
            password='testpass123',
            username='multipletest'
        )
        EmailAddress.objects.create(
            user=user,
            email='multipletest@example.com',
            primary=True,
            verified=False
        )
        self.client.force_login(user)
        
        # Clear cache and mail outbox
        cache.clear()
        mail.outbox.clear()
        
        # First request - should succeed
        response1 = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'multipletest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(len(mail.outbox), 1, "First request should send email")
        
        mail.outbox.clear()
        
        # Second request immediately after - should be blocked by cooldown
        response2 = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'multipletest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response2.status_code, 429, "Second request should return 429 (Too Many Requests)")
        self.assertEqual(len(mail.outbox), 0, "Second request should NOT send email")
        
        # Verify cooldown information in response
        data = json.loads(response2.content)
        self.assertIn('error', data)
        self.assertIn('cooldown_remaining', data)
        self.assertGreater(data['cooldown_remaining'], 0, "Should indicate remaining cooldown time")
        
        # Third request immediately after - also should be blocked
        response3 = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'multipletest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response3.status_code, 429, "Third request should also be blocked")
        self.assertEqual(len(mail.outbox), 0, "Third request should NOT send email")

    def test_cooldown_expires_after_60_seconds(self):
        """Test that cooldown allows resend after 60 seconds have passed."""
        # Create a user with unverified email
        user = User.objects.create_user(
            email='expiretest@example.com',
            password='testpass123',
            username='expiretest'
        )
        EmailAddress.objects.create(
            user=user,
            email='expiretest@example.com',
            primary=True,
            verified=False
        )
        self.client.force_login(user)
        
        # Clear cache and mail outbox
        cache.clear()
        mail.outbox.clear()
        
        # First request
        response1 = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'expiretest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(len(mail.outbox), 1, "First request should send email")
        
        # Manually expire the cooldown by setting timestamp to 61 seconds ago
        cache_key = EMAIL_VERIFICATION_CACHE_KEY.format(
            user_id=user.id,
            email='expiretest@example.com'
        )
        past_time = timezone.now() - timedelta(seconds=61)
        cache.set(cache_key, past_time, timeout=120)  # Long timeout so key doesn't expire
        
        # Try to resend after cooldown expires - should succeed
        mail.outbox.clear()
        response2 = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'expiretest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response2.status_code, 200, "Should succeed after cooldown expires")
        self.assertEqual(len(mail.outbox), 1, "Should send email after cooldown expires")

    def test_adapter_cooldown_blocks_duplicate_sends(self):
        """Test that adapter's cooldown prevents duplicate emails when called directly."""
        
        # Create a user with unverified email
        user = User.objects.create_user(
            email='adaptercooldown@example.com',
            password='testpass123',
            username='adaptercooldown'
        )
        email_addr = EmailAddress.objects.create(
            user=user,
            email='adaptercooldown@example.com',
            primary=True,
            verified=False
        )
        
        # Clear cache
        cache.clear()
        mail.outbox.clear()
        
        # Create request and adapter
        factory = RequestFactory()
        request = factory.post('/')
        request.user = user
        adapter = NoUsernameAccountAdapter()
        
        # First send via adapter directly
        email_confirmation1 = EmailConfirmation.create(email_addr)
        email_confirmation1.save()
        adapter.send_confirmation_mail(request, email_confirmation1, signup=False)
        
        self.assertEqual(len(mail.outbox), 1, "First send should send one email")
        
        # Try to send again immediately (should be blocked by adapter's cooldown)
        mail.outbox.clear()
        email_confirmation2 = EmailConfirmation.create(email_addr)
        email_confirmation2.save()
        adapter.send_confirmation_mail(request, email_confirmation2, signup=False)
        
        email_count = len(mail.outbox)
        self.assertEqual(email_count, 0,
                        f"Adapter's cooldown should have blocked duplicate email, "
                        f"but {email_count} email(s) were sent")
        
        # Verify the cooldown cache was set using the shared cache key
        cache_key = EMAIL_VERIFICATION_CACHE_KEY.format(
            user_id=user.id,
            email='adaptercooldown@example.com'
        )
        last_sent = cache.get(cache_key)
        self.assertIsNotNone(last_sent, "Adapter should have set cooldown cache key")
        self.assertIsInstance(last_sent, type(timezone.now()), "Cache value should be a datetime")

    def test_email_confirmation_link_verifies_email(self):
        """Test that clicking the email confirmation link marks the email as verified."""
        signup_data = {
            'email': 'verifytest@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(reverse('account_signup'), signup_data, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        user = User.objects.get(email='verifytest@example.com')
        email_addr = EmailAddress.objects.get(user=user, email='verifytest@example.com')
        self.assertFalse(email_addr.verified, "Email should be unverified after signup")
        
        self.client.force_login(user)
        
        # Clear cache and mail outbox
        cache.clear()
        mail.outbox.clear()
        
        # Request verification email from settings page
        response = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'verifytest@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(mail.outbox), 1, "Should have received exactly one confirmation email")
        
        # Extract confirmation URL from email
        match = re.search(r'(/accounts/confirm-email/[^/\s]+/)', mail.outbox[0].body)
        self.assertIsNotNone(match, "Email should contain confirmation link")
        confirm_url = match.group(1)
        
        # User clicks the link in their email (GET request)
        # With ACCOUNT_CONFIRM_EMAIL_ON_GET=True, the GET request should confirm the email
        response = self.client.get(confirm_url, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        email_addr.refresh_from_db()
        self.assertTrue(email_addr.verified,
                       "Email should be verified after clicking confirmation link")

    def test_email_confirmation_full_flow(self):
        """Test the complete flow: signup -> request verification -> receive email -> click link -> verified."""
        signup_data = {
            'email': 'fullflow@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(reverse('account_signup'), signup_data, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        user = User.objects.get(email='fullflow@example.com')
        email_addr = EmailAddress.objects.get(user=user, email='fullflow@example.com')
        self.assertFalse(email_addr.verified, "Email should be unverified after signup")
        
        self.client.force_login(user)
        
        # Clear cache and mail outbox
        cache.clear()
        mail.outbox.clear()
        
        # Request verification email from settings page
        response = self.client.post(
            reverse('api_resend_verification'),
            json.dumps({'email': 'fullflow@example.com'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(mail.outbox), 1, "Should have received exactly one confirmation email")
        
        match = re.search(r'(/accounts/confirm-email/[^/\s]+/)', mail.outbox[0].body)
        self.assertIsNotNone(match, "Email should contain confirmation link")
        confirm_url = match.group(1)
        
        response = self.client.get(confirm_url, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        email_addr.refresh_from_db()
        self.assertTrue(email_addr.verified,
                       "Email should be verified after clicking confirmation link")
        
        verified_emails = EmailAddress.objects.filter(user=user, verified=True)
        self.assertEqual(verified_emails.count(), 1)
        self.assertEqual(verified_emails.first().email, 'fullflow@example.com')

    def test_first_user_becomes_superuser(self):
        """Test that the first user to sign up is automatically made a superuser and staff member."""
        # Ensure no users exist (setUp already clears, but be explicit)
        User.objects.all().delete()
        
        signup_data = {
            'email': 'firstuser@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(reverse('account_signup'), signup_data, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        user = User.objects.get(email='firstuser@example.com')
        self.assertTrue(user.is_superuser, "First user should be superuser")
        self.assertTrue(user.is_staff, "First user should be staff")
    
    def test_second_user_not_superuser(self):
        """Test that subsequent users are NOT made superusers."""
        # Create first user
        User.objects.create_user(
            email='first@example.com',
            password='pass123',
            username='first'
        )
        
        # Sign up second user
        signup_data = {
            'email': 'second@example.com',
            'password1': 'SecurePass123!',
            'password2': 'SecurePass123!',
        }
        
        response = self.client.post(reverse('account_signup'), signup_data, follow=True)
        self.assertIn(response.status_code, [200, 302])
        
        user = User.objects.get(email='second@example.com')
        self.assertFalse(user.is_superuser, "Second user should NOT be superuser")
        self.assertFalse(user.is_staff, "Second user should NOT be staff")

