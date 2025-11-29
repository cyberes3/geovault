"""
Tests for user settings API endpoints.
"""
import json
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import UserSettings, FeatureStore
from geo_lib.feature_id import generate_feature_hash


class TestUserSettingsAPI(TestCase):
    """Test user settings API endpoints."""

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

        # Create test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # 3D coordinates with Z=0.0
            },
            'properties': {
                'name': 'Test Feature'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            file_hash=generate_feature_hash(self.feature_data)
        )

    def test_get_user_settings(self):
        """Test getting user settings."""
        UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[]
        )

        response = self.client.get('/api/user/settings/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('settings', data)
        self.assertIn('hidden_features', data)

    def test_get_user_settings_creates_if_not_exists(self):
        """Test that settings are created if they don't exist."""
        response = self.client.get('/api/user/settings/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('settings', data)
        self.assertTrue(UserSettings.objects.filter(user=self.user).exists())

    def test_update_user_setting(self):
        """Test updating user settings."""
        UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[]
        )

        update_data = {
            'map': {
                'elevation_profile_source': 'local'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.settings['map']['elevation_profile_source'], 'local')

    def test_update_user_setting_deep_merge(self):
        """Test that settings are deep merged."""
        UserSettings.objects.create(
            user=self.user,
            settings={
                'map': {
                    'elevation_profile_source': 'api',
                    'other_setting': 'value'
                },
                'other_section': {
                    'key': 'value'
                }
            },
            hidden_features=[]
        )

        update_data = {
            'map': {
                'elevation_profile_source': 'local'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        user_settings = UserSettings.objects.get(user=self.user)
        # Other setting should be preserved
        self.assertEqual(user_settings.settings['map']['other_setting'], 'value')
        self.assertEqual(user_settings.settings['other_section']['key'], 'value')
        # Updated setting should be changed
        self.assertEqual(user_settings.settings['map']['elevation_profile_source'], 'local')

    def test_bulk_update_hidden_features(self):
        """Test bulk updating hidden features."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[]
        )

        update_data = {
            'hidden_features': [self.feature.id]
        }

        response = self.client.post(
            '/api/user/settings/hidden-features/bulk/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.hidden_features, [self.feature.id])

    def test_clear_hidden_features(self):
        """Test clearing hidden features."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[self.feature.id]
        )

        response = self.client.post('/api/user/settings/hidden-features/clear/')
        self.assertEqual(response.status_code, 200)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.hidden_features, [])

    def test_update_user_setting_invalid_json(self):
        """Test updating with invalid JSON."""
        response = self.client.put(
            '/api/user/settings/update/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_user_setting_not_dict(self):
        """Test updating with non-dict data."""
        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps('not a dict'),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_hidden_features_with_names(self):
        """Test that hidden features include names."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[self.feature.id]
        )

        response = self.client.get('/api/user/settings/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('hidden_features', data)
        if data['hidden_features']:
            self.assertIn('name', data['hidden_features'][0])

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access settings."""
        self.client.logout()
        response = self.client.get('/api/user/settings/')
        self.assertEqual(response.status_code, 401)

