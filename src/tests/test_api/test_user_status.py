"""
Tests for user status API endpoint.
"""
import json
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportQueue
from geo_lib.feature_id import generate_feature_hash


class TestUserStatusEndpoint(TestCase):
    """Test /api/user/status/ endpoint."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_user_status_authenticated(self):
        """Test user status for authenticated user."""
        self.client.force_login(self.user)
        response = self.client.get('/api/user/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertTrue(data['authorized'])
        self.assertEqual(data['id'], self.user.id)
        self.assertIn('email', data)
        self.assertIn('featureCount', data)
        self.assertIn('tags', data)
        self.assertIn('is_superuser', data)
        self.assertFalse(data['is_superuser'])

    def test_user_status_unauthenticated(self):
        """Test user status for unauthenticated user."""
        response = self.client.get('/api/user/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertFalse(data['authorized'])
        self.assertIsNone(data['id'])
        self.assertIsNone(data['email'])
        self.assertEqual(data['featureCount'], 0)
        self.assertEqual(data['tags'], [])

    def test_user_status_superuser_flag(self):
        """Test that is_superuser flag is correctly set for superuser."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        admin = User.objects.create_user(
            email='admin@example.com',
            password='adminpass123',
            username='adminuser',
            is_superuser=True
        )
        
        self.client.force_login(admin)
        response = self.client.get('/api/user/status/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertTrue(data['authorized'])
        self.assertTrue(data['is_superuser'])


class TestFeatureCountCalculation(TestCase):
    """Test feature count calculation in user status."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_feature_count_zero(self):
        """Test feature count when user has no features."""
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        self.assertEqual(data['featureCount'], 0)

    def test_feature_count_with_features(self):
        """Test feature count when user has features."""
        # Create multiple features
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i}'
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        self.assertEqual(data['featureCount'], 5)

    def test_feature_count_only_own_features(self):
        """Test that feature count only includes user's own features."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        
        # Create features for current user
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'My Point {i}'
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        # Create features for other user
        for i in range(7):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4094 + i * 0.01, 37.7849 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Other Point {i}'
                }
            }
            FeatureStore.objects.create(
                user=other_user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        # Should only count own features
        self.assertEqual(data['featureCount'], 3)


class TestEmailAddress(TestCase):
    """Test email address handling in user status."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_email_address_returned(self):
        """Test that email address is returned for authenticated user."""
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        # Email may be None if no EmailAddress record exists
        # (depends on how user was created)
        self.assertIn('email', data)

    def test_email_address_with_primary(self):
        """Test email address when primary email is set."""
        from allauth.account.models import EmailAddress
        
        # Create primary email address
        EmailAddress.objects.create(
            user=self.user,
            email='primary@example.com',
            primary=True,
            verified=True
        )
        
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        self.assertEqual(data['email'], 'primary@example.com')

    def test_email_address_fallback(self):
        """Test email address fallback when no primary is set."""
        from allauth.account.models import EmailAddress
        
        # Create non-primary email addresses
        EmailAddress.objects.create(
            user=self.user,
            email='first@example.com',
            primary=False,
            verified=True
        )
        EmailAddress.objects.create(
            user=self.user,
            email='second@example.com',
            primary=False,
            verified=True
        )
        
        response = self.client.get('/api/user/status/')
        data = json.loads(response.content)
        
        # Should fallback to first email
        self.assertIn(data['email'], ['first@example.com', 'second@example.com'])


class TestUserStorageEndpoint(TestCase):
    """Test /api/user/storage/ endpoint."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

    def test_storage_endpoint_authenticated(self):
        """Test storage endpoint for authenticated user."""
        response = self.client.get('/api/user/storage/')
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertIn('storage_bytes', data)
        self.assertIsInstance(data['storage_bytes'], int)
        self.assertGreaterEqual(data['storage_bytes'], 0)

    def test_storage_endpoint_unauthenticated(self):
        """Test that unauthenticated users cannot access storage endpoint."""
        self.client.logout()
        response = self.client.get('/api/user/storage/')
        
        self.assertEqual(response.status_code, 401)

    def test_storage_with_features(self):
        """Test storage calculation with features."""
        # Create feature with some data
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'description': 'A test point with some data'
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )
        
        response = self.client.get('/api/user/storage/')
        data = json.loads(response.content)
        
        # Should have some storage usage
        self.assertGreater(data['storage_bytes'], 0)

    def test_storage_with_import_queue(self):
        """Test storage calculation with import queue items."""
        # Create import queue item
        kml_content = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Placemark</name>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        
        ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file=kml_content,
            geofeatures=[]
        )
        
        response = self.client.get('/api/user/storage/')
        data = json.loads(response.content)
        
        # Should have some storage usage
        self.assertGreater(data['storage_bytes'], 0)

    def test_storage_only_own_data(self):
        """Test that storage only includes user's own data."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        
        # Create feature for current user
        my_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'My Point'
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=my_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(my_feature_data)
        )
        
        # Create feature for other user
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {
                'name': 'Other Point',
                'description': 'This is a very long description' * 100  # Large data
            }
        }
        FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_feature_hash(other_feature_data)
        )
        
        response = self.client.get('/api/user/storage/')
        data = json.loads(response.content)
        
        # Storage should only reflect own data (much smaller)
        my_storage = data['storage_bytes']
        
        # Login as other user and check their storage
        self.client.force_login(other_user)
        response = self.client.get('/api/user/storage/')
        other_data = json.loads(response.content)
        other_storage = other_data['storage_bytes']
        
        # Other user should have much more storage
        self.assertGreater(other_storage, my_storage)

    def test_storage_empty_user(self):
        """Test storage for user with no data."""
        response = self.client.get('/api/user/storage/')
        data = json.loads(response.content)
        
        # Should be zero or very small
        self.assertGreaterEqual(data['storage_bytes'], 0)

