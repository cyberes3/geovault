"""
Tests for GET /api/users/ (users.views.list_users.list_users).

Lists other users' emails for sharing UIs (e.g. live track share). The endpoint also
carries a Redis rate limit, but that primitive is covered centrally in
test_api/test_rate_limit.py rather than re-tested per endpoint here.
"""
import json

from django.contrib.auth import get_user_model
from django.test import TestCase

User = get_user_model()


class TestListUsers(TestCase):
    """Test GET /api/users/."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='self@example.com',
            password='testpass123',
            username='selfuser',
        )
        self.other_a = User.objects.create_user(
            email='alice@example.com',
            password='testpass123',
            username='alice',
        )
        self.other_b = User.objects.create_user(
            email='bob@example.com',
            password='testpass123',
            username='bob',
        )
        self.no_email_user = User.objects.create_user(
            email='',
            password='testpass123',
            username='noemail',
        )
        self.client.force_login(self.user)

    def test_requires_authentication(self):
        self.client.logout()
        response = self.client.get('/api/users/')
        self.assertEqual(response.status_code, 401)

    def test_lists_other_users_excluding_self(self):
        response = self.client.get('/api/users/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        emails = {u['email'] for u in data['users']}
        self.assertIn('alice@example.com', emails)
        self.assertIn('bob@example.com', emails)
        self.assertNotIn('self@example.com', emails)

    def test_excludes_users_without_email(self):
        response = self.client.get('/api/users/')
        data = json.loads(response.content)
        emails = {u['email'] for u in data['users']}
        self.assertNotIn('', emails)
        ids = {u['id'] for u in data['users']}
        self.assertNotIn(self.no_email_user.id, ids)

    def test_results_ordered_by_email(self):
        response = self.client.get('/api/users/')
        data = json.loads(response.content)
        emails = [u['email'] for u in data['users']]
        self.assertEqual(emails, sorted(emails))

    def test_only_get_allowed(self):
        response = self.client.post('/api/users/')
        self.assertEqual(response.status_code, 405)
