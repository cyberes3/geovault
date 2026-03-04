"""
Tests for Django admin site access (superuser-only restriction).
"""
from django.contrib.auth import get_user_model
from django.test import TestCase


User = get_user_model()


class TestDjangoAdminSiteSuperuserOnly(TestCase):
    """Test that /admin/ requires superuser (not just staff)."""

    def test_admin_anonymous_redirects_to_login(self):
        """Anonymous user GET /admin/ redirects to login."""
        response = self.client.get('/admin/')
        self.assertEqual(response.status_code, 302)
        self.assertIn('login', response.url)

    def test_admin_staff_not_superuser_forbidden(self):
        """Staff user without superuser cannot access admin (403 or no access)."""
        user = User.objects.create_user(
            email='staff@example.com',
            password='testpass123',
            username='staffuser',
            is_staff=True,
            is_superuser=False,
        )
        self.client.force_login(user)
        response = self.client.get('/admin/')
        # Custom AdminSite has_permission requires is_superuser; staff-only gets denied
        self.assertIn(response.status_code, (403, 302))
        if response.status_code == 200:
            self.assertIn(b'permission', response.content.lower())

    def test_admin_superuser_can_access(self):
        """Superuser can access admin index."""
        user = User.objects.create_user(
            email='admin@example.com',
            password='adminpass123',
            username='adminuser',
            is_staff=True,
            is_superuser=True,
        )
        self.client.force_login(user)
        response = self.client.get('/admin/')
        self.assertEqual(response.status_code, 200)
        # Admin index typically contains "Site administration" or similar
        self.assertIn(b'admin', response.content.lower())
