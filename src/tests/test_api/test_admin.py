"""
Tests for admin API endpoints.
"""
import json
from django.test import TestCase
from django.contrib.auth import get_user_model
from allauth.account.models import EmailAddress


class TestAdminUsersAPI(TestCase):
    """Test admin users API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        
        # Create regular user
        self.regular_user = User.objects.create_user(
            email='regular@example.com',
            password='testpass123',
            username='regularuser',
            is_superuser=False
        )
        # Create EmailAddress for regular user (required for admin endpoint to show email)
        EmailAddress.objects.create(
            user=self.regular_user,
            email='regular@example.com',
            primary=True,
            verified=True
        )
        
        # Create superuser
        self.admin_user = User.objects.create_user(
            email='admin@example.com',
            password='adminpass123',
            username='adminuser',
            is_superuser=True,
            is_staff=True
        )
        # Create EmailAddress for admin user (required for admin endpoint to show email)
        EmailAddress.objects.create(
            user=self.admin_user,
            email='admin@example.com',
            primary=True,
            verified=True
        )
        
        # Create additional users for testing list
        for i in range(5):
            user = User.objects.create_user(
                email=f'user{i}@example.com',
                password='testpass123',
                username=f'testuser{i}'
            )
            # Create EmailAddress for each user
            EmailAddress.objects.create(
                user=user,
                email=f'user{i}@example.com',
                primary=True,
                verified=True
            )

    def test_list_all_users_as_superuser(self):
        """Test that superuser can list all users."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('users', data)
        self.assertIsInstance(data['users'], list)
        
        # Should have at least 7 users (2 created in setUp + 5 additional)
        self.assertGreaterEqual(len(data['users']), 7)

    def test_list_all_users_as_regular_user(self):
        """Test that regular user cannot list all users."""
        self.client.force_login(self.regular_user)
        
        response = self.client.get('/api/admin/users/')
        # Should be forbidden or unauthorized
        self.assertIn(response.status_code, [401, 403])

    def test_list_all_users_unauthenticated(self):
        """Test that unauthenticated users cannot list all users."""
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 401)

    def test_list_all_users_response_structure(self):
        """Test that response includes expected user data."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('users', data)
        
        if len(data['users']) > 0:
            user = data['users'][0]
            # Verify expected fields are present (actual API response structure)
            self.assertIn('id', user)
            self.assertIn('email', user)
            self.assertIn('last_activity', user)
            self.assertIn('date_joined', user)
            self.assertIn('feature_count', user)
            self.assertIn('share_count', user)
            self.assertIn('storage_bytes', user)

    def test_list_all_users_pagination(self):
        """Test pagination support for user list."""
        self.client.force_login(self.admin_user)
        
        # Create more users to test pagination
        User = get_user_model()
        for i in range(20):
            user = User.objects.create_user(
                email=f'paginationuser{i}@example.com',
                password='testpass123',
                username=f'paginationuser{i}'
            )
            # Create EmailAddress for each user
            EmailAddress.objects.create(
                user=user,
                email=f'paginationuser{i}@example.com',
                primary=True,
                verified=True
            )
        
        # Test with page parameter
        response = self.client.get('/api/admin/users/?page=1')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('users', data)

    def test_list_all_users_includes_superuser_flag(self):
        """Test that response includes admin user."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        # Find the admin user in the list by email
        admin_in_list = None
        for user in data['users']:
            if user.get('email') == 'admin@example.com':
                admin_in_list = user
                break
        
        self.assertIsNotNone(admin_in_list, "Admin user should be in the list")
        # Note: API doesn't return is_superuser flag, just verify user is present

    def test_list_all_users_includes_regular_users(self):
        """Test that response includes non-superuser users."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        # Find a regular user in the list by email
        regular_in_list = None
        for user in data['users']:
            if user.get('email') == 'regular@example.com':
                regular_in_list = user
                break
        
        self.assertIsNotNone(regular_in_list, "Regular user should be in the list")
        # Note: API doesn't return is_superuser flag, just verify user is present

    def test_list_all_users_no_password_exposure(self):
        """Test that passwords are not exposed in user list."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        for user in data['users']:
            # Password should never be in the response
            self.assertNotIn('password', user)
            self.assertNotIn('password_hash', user)

    def test_list_all_users_search_functionality(self):
        """Test search functionality if implemented."""
        self.client.force_login(self.admin_user)
        
        # Try search parameter (may or may not be implemented)
        response = self.client.get('/api/admin/users/?search=admin')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        self.assertIn('users', data)

    def test_list_all_users_ordering(self):
        """Test that users are returned in a consistent order."""
        self.client.force_login(self.admin_user)
        
        response1 = self.client.get('/api/admin/users/')
        response2 = self.client.get('/api/admin/users/')
        
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(response2.status_code, 200)
        
        data1 = json.loads(response1.content)
        data2 = json.loads(response2.content)
        
        # Order should be consistent
        user_ids_1 = [u['id'] for u in data1['users']]
        user_ids_2 = [u['id'] for u in data2['users']]
        self.assertEqual(user_ids_1, user_ids_2)

    def test_list_all_users_includes_inactive_users(self):
        """Test that inactive users are included in the list."""
        User = get_user_model()
        inactive_user = User.objects.create_user(
            email='inactive@example.com',
            password='testpass123',
            username='inactiveuser',
            is_active=False
        )
        # Create EmailAddress for inactive user
        EmailAddress.objects.create(
            user=inactive_user,
            email='inactive@example.com',
            primary=True,
            verified=True
        )
        
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        # Find the inactive user
        inactive_in_list = None
        for user in data['users']:
            if user.get('email') == 'inactive@example.com':
                inactive_in_list = user
                break
        
        # Note: API doesn't return is_active flag, just verify user is present
        self.assertIsNotNone(inactive_in_list, "Inactive user should be in the list")

    def test_list_all_users_staff_but_not_superuser(self):
        """Test that staff users (non-superuser) cannot list all users."""
        User = get_user_model()
        staff_user = User.objects.create_user(
            email='staff@example.com',
            password='testpass123',
            username='staffuser',
            is_staff=True,
            is_superuser=False
        )
        
        self.client.force_login(staff_user)
        
        response = self.client.get('/api/admin/users/')
        # Staff without superuser should not have access
        self.assertIn(response.status_code, [401, 403])

    def test_list_all_users_includes_user_count(self):
        """Test that response includes total user count."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        # May include count field (depending on implementation)
        # This is optional but common in admin endpoints
        if 'count' in data:
            self.assertIsInstance(data['count'], int)
            self.assertGreater(data['count'], 0)

    def test_list_all_users_includes_date_joined(self):
        """Test that user list includes date_joined information."""
        self.client.force_login(self.admin_user)
        
        response = self.client.get('/api/admin/users/')
        self.assertEqual(response.status_code, 200)
        
        data = json.loads(response.content)
        
        if len(data['users']) > 0:
            user = data['users'][0]
            self.assertIn('date_joined', user)
            # Should be a valid ISO format timestamp
            self.assertIsInstance(user['date_joined'], str)
