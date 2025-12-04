"""
Tests for user settings API endpoints.
"""
import json
from django.test import TestCase
from django.contrib.gis.geos import Point

from django.contrib.auth import get_user_model

from api.models import UserSettings, FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestUserSettingsAPI(TestCase):
    """Test user settings API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
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
            geojson_hash=generate_geojson_hash(self.feature_data)
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
                'elevation_profile_source': 'gps'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.settings['map']['elevation_profile_source'], 'gps')

    def test_update_user_setting_deep_merge(self):
        """Test that settings are deep merged."""
        UserSettings.objects.create(
            user=self.user,
            settings={
                'map': {
                    'elevation_profile_source': 'api',
                    'default_basemap': 'osm'
                },
                'account': {
                    'units': 'metric'
                }
            },
            hidden_features=[]
        )

        update_data = {
            'map': {
                'elevation_profile_source': 'gps'
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
        self.assertEqual(user_settings.settings['map']['default_basemap'], 'osm')
        self.assertEqual(user_settings.settings['account']['units'], 'metric')
        # Updated setting should be changed
        self.assertEqual(user_settings.settings['map']['elevation_profile_source'], 'gps')

    def test_bulk_update_hidden_features(self):
        """Test bulk updating hidden features."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[]
        )

        update_data = {
            'add': [self.feature.id]
        }

        response = self.client.post(
            '/api/user/settings/hidden-features/bulk/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 204)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.hidden_features, [str(self.feature.id)])

    def test_bulk_update_hidden_features_empty(self):
        """Test bulk updating hidden features with empty add/remove (optimization test)."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[str(self.feature.id)]
        )

        update_data = {
            'add': [],
            'remove': []
        }

        response = self.client.post(
            '/api/user/settings/hidden-features/bulk/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 204)
        # Should remain unchanged
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.hidden_features, [str(self.feature.id)])

    def test_bulk_update_hidden_features_add_and_remove(self):
        """Test bulk updating hidden features with both add and remove."""
        # Create another feature
        feature2_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.5, 37.8, 0.0]
            },
            'properties': {
                'name': 'Test Feature 2'
            }
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geometry=Point(-122.5, 37.8, 0.0),
            geojson_hash=generate_geojson_hash(feature2_data)
        )

        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[str(self.feature.id)]
        )

        update_data = {
            'add': [feature2.id],
            'remove': [self.feature.id]
        }

        response = self.client.post(
            '/api/user/settings/hidden-features/bulk/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 204)
        user_settings = UserSettings.objects.get(user=self.user)
        # Should have feature2 but not feature1
        self.assertIn(str(feature2.id), user_settings.hidden_features)
        self.assertNotIn(str(self.feature.id), user_settings.hidden_features)

    def test_clear_hidden_features(self):
        """Test clearing hidden features."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[self.feature.id]
        )

        response = self.client.post('/api/user/settings/hidden-features/clear/')
        self.assertEqual(response.status_code, 204)
        user_settings = UserSettings.objects.get(user=self.user)
        self.assertEqual(user_settings.hidden_features, [])

    def test_clear_hidden_features_already_empty(self):
        """Test clearing hidden features when already empty (optimization test)."""
        UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[]
        )

        response = self.client.post('/api/user/settings/hidden-features/clear/')
        self.assertEqual(response.status_code, 204)
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

    def test_get_user_settings_no_validation_on_read(self):
        """Test that invalid settings in database are returned as-is (no validation on read)."""
        # Store invalid settings directly in database (bypassing validation)
        user_settings = UserSettings.objects.create(
            user=self.user,
            settings={
                'map': {
                    'elevation_profile_source': 'invalid_value',  # Invalid enum value
                    'extra_field': 'should_be_allowed'  # Extra field that would be forbidden by validation
                }
            },
            hidden_features=[]
        )

        response = self.client.get('/api/user/settings/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Invalid settings should be returned as-is (no validation/fixing on read)
        self.assertEqual(data['settings']['map']['elevation_profile_source'], 'invalid_value')
        self.assertEqual(data['settings']['map']['extra_field'], 'should_be_allowed')

    def test_update_user_setting_response_no_hidden_features(self):
        """Test that update response doesn't include hidden_features (optimization)."""
        UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[self.feature.id]
        )

        update_data = {
            'map': {
                'elevation_profile_source': 'gps'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Response should only contain settings, not hidden_features
        self.assertIn('settings', data)
        self.assertNotIn('hidden_features', data)
        self.assertEqual(data['settings']['map']['elevation_profile_source'], 'gps')

    def test_update_user_setting_validation_on_write(self):
        """Test that invalid settings are still rejected on write (validation still happens)."""
        UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[]
        )

        # Try to update with invalid enum value
        update_data = {
            'map': {
                'elevation_profile_source': 'invalid_enum_value'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)

    def test_update_user_setting_extra_fields_rejected(self):
        """Test that extra fields are rejected on write (validation still happens)."""
        UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[]
        )

        # Try to update with extra field that's not in the schema
        update_data = {
            'map': {
                'elevation_profile_source': 'gps',
                'extra_invalid_field': 'should_be_rejected'
            }
        }

        response = self.client.put(
            '/api/user/settings/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)
