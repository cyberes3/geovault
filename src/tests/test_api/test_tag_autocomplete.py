"""
Tests for tag autocomplete functionality via /api/features/by-tag/ endpoint.

These tests ensure:
1. User tags and system tags are properly separated
2. System tags never appear in user tag autocomplete suggestions
3. The API respects the 10-tag limit for pagination
4. Tag filtering and search work correctly

Also covers the lightweight /api/features/user-tags/ endpoint used for
TagPicker autocomplete.
"""
import json
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import FeatureStore
from geo_lib.feature_id import generate_feature_hash


class TestUserTagsEndpoint(TestCase):
    """Tests for the lightweight /api/features/user-tags/ endpoint."""

    def setUp(self):
        from django.contrib.auth import get_user_model

        User = get_user_model()
        self.user = User.objects.create_user(
            email='user-tags@example.com',
            password='testpass123',
            username='user-tags',
        )
        self.client.force_login(self.user)

        # Create features with overlapping user tags and system tags
        feature_defs = [
            {
                'name': 'Feature 1',
                'tags': ['alpha', 'beta', 'gamma'],
                'system_tags': ['type:point', 'elevation:high'],
            },
            {
                'name': 'Feature 2',
                'tags': ['beta', 'delta'],
                'system_tags': ['type:line'],
            },
            {
                'name': 'Feature 3',
                'tags': ['epsilon', 'alpha'],
                'system_tags': ['import-year:2025'],
            },
        ]

        for f in feature_defs:
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194, 37.7749, 0.0],
                },
                'properties': {
                    'name': f['name'],
                    'tags': f['tags'],
                    'system_tags': f['system_tags'],
                },
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_feature_hash(feature_data),
            )

    def test_user_tags_endpoint_returns_unique_sorted_tags(self):
        """The /api/features/user-tags/ endpoint should return unique, sorted user tags."""
        response = self.client.get('/api/features/user-tags/')
        self.assertEqual(response.status_code, 200)

        tags = json.loads(response.content)
        self.assertIsInstance(tags, list)

        # Combined, de-duplicated expected tags from fixtures
        expected_tags = ['alpha', 'beta', 'delta', 'epsilon', 'gamma']

        # Ensure tags match expected (order should be alphabetical)
        self.assertEqual(tags, sorted(expected_tags))

        # Ensure no system tags slipped into the list
        forbidden_system_tags = [
            'type:point',
            'elevation:high',
            'type:line',
            'import-year:2025',
        ]
        for tag in forbidden_system_tags:
            self.assertNotIn(tag, tags)


class TestTagSeparation(TestCase):
    """Test that user tags and system tags are properly separated."""

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

    def test_user_and_system_tags_separated(self):
        """Test that user tags and system tags are in separate response fields."""
        # Create a feature with both user tags and system tags
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['hiking', 'mountain', 'scenic'],
                'system_tags': ['type:point', 'import-year:2025', 'elevation:high']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        # Verify response structure
        self.assertIn('user_tags', data)
        self.assertIn('system_tags', data)
        self.assertIsInstance(data['user_tags'], dict)
        self.assertIsInstance(data['system_tags'], dict)

        # Verify user tags are in user_tags
        user_tag_names = list(data['user_tags'].keys())
        self.assertIn('hiking', user_tag_names)
        self.assertIn('mountain', user_tag_names)
        self.assertIn('scenic', user_tag_names)

        # Verify system tags are in system_tags
        system_tag_names = list(data['system_tags'].keys())
        self.assertIn('type:point', system_tag_names)
        self.assertIn('import-year:2025', system_tag_names)
        self.assertIn('elevation:high', system_tag_names)

        # CRITICAL: Verify no cross-contamination
        # System tags should NOT appear in user_tags
        for system_tag in ['type:point', 'import-year:2025', 'elevation:high']:
            self.assertNotIn(system_tag, user_tag_names,
                           f"System tag '{system_tag}' should not appear in user_tags")

        # User tags should NOT appear in system_tags
        for user_tag in ['hiking', 'mountain', 'scenic']:
            self.assertNotIn(user_tag, system_tag_names,
                           f"User tag '{user_tag}' should not appear in system_tags")

    def test_system_tag_patterns_not_in_user_tags(self):
        """Test that various system tag patterns are properly categorized."""
        # Create features with various system tag patterns
        system_tag_patterns = [
            'type:line',
            'import-year:2024',
            'import-month:December',
            'feature-year:2023',
            'feature-month:June',
            'source-file:test.gpx',
            'track:yes',
            'elevation:high',
            'geocoding:success',
            'driving:yes'  # The specific tag from the bug report
        ]

        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point with System Tags',
                'tags': ['user-tag-1', 'user-tag-2'],
                'system_tags': system_tag_patterns
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())

        # Verify ALL system tag patterns are in system_tags, not user_tags
        for system_tag in system_tag_patterns:
            self.assertIn(system_tag, system_tag_names,
                        f"System tag '{system_tag}' should be in system_tags")
            self.assertNotIn(system_tag, user_tag_names,
                           f"System tag '{system_tag}' should NOT be in user_tags")

        # Verify user tags are only in user_tags
        self.assertIn('user-tag-1', user_tag_names)
        self.assertIn('user-tag-2', user_tag_names)
        self.assertNotIn('user-tag-1', system_tag_names)
        self.assertNotIn('user-tag-2', system_tag_names)

    def test_multiple_features_tags_aggregated_correctly(self):
        """Test that tags from multiple features are aggregated and separated correctly."""
        # Create multiple features with different tag combinations
        features = [
            {
                'name': 'Feature 1',
                'tags': ['hiking', 'trail'],
                'system_tags': ['type:point', 'elevation:high']
            },
            {
                'name': 'Feature 2',
                'tags': ['hiking', 'camping'],
                'system_tags': ['type:line', 'elevation:high']
            },
            {
                'name': 'Feature 3',
                'tags': ['fishing', 'lake'],
                'system_tags': ['type:polygon', 'import-year:2025']
            }
        ]

        for i, feature_info in enumerate(features):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': feature_info['name'],
                    'tags': feature_info['tags'],
                    'system_tags': feature_info['system_tags']
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

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())

        # Verify all user tags are present (deduplicated)
        expected_user_tags = {'hiking', 'trail', 'camping', 'fishing', 'lake'}
        self.assertEqual(set(user_tag_names), expected_user_tags)

        # Verify all system tags are present (deduplicated)
        expected_system_tags = {'type:point', 'type:line', 'type:polygon',
                               'elevation:high', 'import-year:2025'}
        self.assertEqual(set(system_tag_names), expected_system_tags)

        # Verify no cross-contamination
        for system_tag in expected_system_tags:
            self.assertNotIn(system_tag, user_tag_names)
        for user_tag in expected_user_tags:
            self.assertNotIn(user_tag, system_tag_names)

    def test_empty_tags_filtered_out(self):
        """Test that empty tags are filtered out from results."""
        # Create a feature with empty tags mixed in
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['valid-tag', '', 'another-tag'],
                'system_tags': ['type:point', '', 'import-year:2025']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())

        # Verify empty string tags are not present
        self.assertNotIn('', user_tag_names)
        self.assertNotIn('', system_tag_names)

        # Verify valid tags are present
        self.assertIn('valid-tag', user_tag_names)
        self.assertIn('another-tag', user_tag_names)
        self.assertIn('type:point', system_tag_names)
        self.assertIn('import-year:2025', system_tag_names)


class TestAllTagsReturned(TestCase):
    """Test that the API returns all tags (no pagination)."""

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

    def test_few_tags_returns_all(self):
        """Test that when there are few tags, all are returned."""
        # Create feature with 5 user tags
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['tag1', 'tag2', 'tag3', 'tag4', 'tag5'],
                'system_tags': []
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        # All 5 tags should be returned
        user_tag_names = list(data['user_tags'].keys())
        self.assertEqual(len(user_tag_names), 5)
        for i in range(1, 6):
            self.assertIn(f'tag{i}', user_tag_names)

    def test_many_tags_returns_all(self):
        """Test that all tags are returned even when there are many."""
        # Create multiple features with unique tags (total 15 tags)
        all_tags = [f'tag{i:02d}' for i in range(1, 16)]  # tag01 to tag15
        
        # Distribute tags across 3 features
        for i in range(3):
            feature_tags = all_tags[i*5:(i+1)*5]
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i+1}',
                    'tags': feature_tags,
                    'system_tags': []
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

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        # Should return all 15 tags
        user_tag_names = list(data['user_tags'].keys())
        self.assertEqual(len(user_tag_names), 15,
                        f"Expected all 15 tags, got {len(user_tag_names)}")
        
        # Verify all tags are present
        for tag in all_tags:
            self.assertIn(tag, user_tag_names)

    def test_mixed_user_and_system_tags_all_returned(self):
        """Test that all user and system tags are returned and properly separated."""
        # Create features with both user and system tags
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['user1', 'user2', 'user3', 'user4', 'user5', 'user6'],
                'system_tags': ['type:point', 'import-year:2025', 'elevation:high',
                              'source-file:test.gpx', 'track:yes']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags via API
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        # All tags should be returned
        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())
        
        self.assertEqual(len(user_tag_names), 6)
        self.assertEqual(len(system_tag_names), 5)

        # Verify tags are properly separated (no cross-contamination)
        for user_tag in user_tag_names:
            self.assertNotIn(user_tag, system_tag_names)
        for system_tag in system_tag_names:
            self.assertNotIn(system_tag, user_tag_names)


class TestTagSearch(TestCase):
    """Test tag search/filtering functionality."""

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

        # Create features with various tags
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['hiking', 'biking', 'camping', 'fishing', 'swimming'],
                'system_tags': ['type:point', 'import-year:2025', 'elevation:high']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

    def test_search_filters_user_tags(self):
        """Test that search parameter filters user tags correctly."""
        # Search for tags containing 'ing'
        response = self.client.get('/api/features/by-tag/', {'search': 'ing'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        
        # Should include: hiking, biking, camping, fishing, swimming
        # (all contain 'ing')
        self.assertIn('hiking', user_tag_names)
        self.assertIn('biking', user_tag_names)
        self.assertIn('camping', user_tag_names)
        self.assertIn('fishing', user_tag_names)
        self.assertIn('swimming', user_tag_names)

    def test_search_filters_system_tags(self):
        """Test that search parameter filters system tags correctly."""
        # Search for tags containing 'type'
        response = self.client.get('/api/features/by-tag/', {'search': 'type'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        system_tag_names = list(data['system_tags'].keys())
        
        # Should include: type:point
        self.assertIn('type:point', system_tag_names)
        # Should not include tags without 'type'
        self.assertNotIn('elevation:high', system_tag_names)
        self.assertNotIn('import-year:2025', system_tag_names)

    def test_search_case_insensitive(self):
        """Test that search is case-insensitive."""
        # Search with uppercase
        response = self.client.get('/api/features/by-tag/', {'search': 'HIK'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        self.assertIn('hiking', user_tag_names)

    def test_search_no_matches_returns_empty(self):
        """Test that search with no matches returns empty results."""
        response = self.client.get('/api/features/by-tag/', {'search': 'nonexistent'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        # Should have empty tag dictionaries
        self.assertEqual(len(data['user_tags']), 0)
        self.assertEqual(len(data['system_tags']), 0)

    def test_search_maintains_separation(self):
        """Test that search maintains user/system tag separation."""
        # Search for 'i' which appears in both user and system tags
        response = self.client.get('/api/features/by-tag/', {'search': 'i'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())

        # Verify separation is maintained
        for user_tag in user_tag_names:
            self.assertNotIn(user_tag, system_tag_names)
        for system_tag in system_tag_names:
            self.assertNotIn(system_tag, user_tag_names)


class TestTagAutocompleteIntegration(TestCase):
    """Integration tests simulating real-world tag autocomplete scenarios."""

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

    def test_user_imports_multiple_features_tags_available(self):
        """
        Test scenario: User imports multiple GPX files with different tags.
        All user tags should be available for autocomplete on subsequent features.
        """
        # Simulate importing 3 GPX tracks with different tags
        tracks = [
            {
                'name': '2025-06-21 hike North Park',
                'user_tags': ['nevada', 'four-wheeling', 'overlanding', 'mines'],
                'system_tags': ['source-file:2025-06-21 hike North Park.gpx',
                              'elevation:high', 'feature-month:June', 'import-month:December',
                              'feature-year:2025', 'import-year:2025', 'track:yes']
            },
            {
                'name': '2025-05-20 Nevada overlanding',
                'user_tags': ['nevada', 'four-wheeling', 'overlanding', 'mines',
                            'nuclear history', 'ghost towns'],
                'system_tags': ['source-file:2025-05-20 Nevada overlanding.gpx',
                              'driving:yes', 'elevation:high']
            },
            {
                'name': 'Morning jog',
                'user_tags': ['running', 'exercise', 'local'],
                'system_tags': ['source-file:morning-jog.gpx', 'track:yes']
            }
        ]

        for i, track in enumerate(tracks):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'LineString',
                    'coordinates': [
                        [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0],
                        [-122.4194 + i * 0.01 + 0.001, 37.7749 + i * 0.01 + 0.001, 10.0]
                    ]
                },
                'properties': {
                    'name': track['name'],
                    'tags': track['user_tags'],
                    'system_tags': track['system_tags']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )

        # Fetch tags for autocomplete
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())
        system_tag_names = list(data['system_tags'].keys())

        # Verify all unique user tags are available
        expected_user_tags = {'nevada', 'four-wheeling', 'overlanding', 'mines',
                             'nuclear history', 'ghost towns', 'running', 'exercise', 'local'}
        for tag in expected_user_tags:
            self.assertIn(tag, user_tag_names,
                        f"User tag '{tag}' should be available for autocomplete")

        # CRITICAL: Verify system tags are NOT in user autocomplete list
        system_tags_to_check = ['driving:yes', 'track:yes', 'elevation:high',
                               'source-file:2025-06-21 hike North Park.gpx']
        for system_tag in system_tags_to_check:
            self.assertNotIn(system_tag, user_tag_names,
                           f"System tag '{system_tag}' should NOT appear in user tag autocomplete")
            
        # Verify system tags are in the system_tags field
        for system_tag in ['driving:yes', 'track:yes', 'elevation:high']:
            self.assertIn(system_tag, system_tag_names,
                        f"System tag '{system_tag}' should be in system_tags")

    def test_editing_feature_shows_only_user_tags_in_autocomplete(self):
        """
        Test scenario from bug report: When editing a feature, only user tags
        should appear in autocomplete, not system tags like 'driving:yes'.
        """
        # Create a feature with the exact tags from the bug report
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [
                    [-116.0, 36.0, 100.0],
                    [-116.1, 36.1, 150.0]
                ]
            },
            'properties': {
                'name': '2025-05-20 Nevada overlanding',
                'tags': ['nevada', 'four-wheeling', 'overlanding', 'mines'],
                'system_tags': ['source-file:2025-05-20 Nevada overlanding.gpx',
                              'elevation:high', 'driving:yes']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-116.0, 36.0, 100.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

        # Fetch tags for autocomplete (simulating tag picker)
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)

        user_tag_names = list(data['user_tags'].keys())

        # User should see their tags
        self.assertIn('nevada', user_tag_names)
        self.assertIn('four-wheeling', user_tag_names)
        self.assertIn('overlanding', user_tag_names)
        self.assertIn('mines', user_tag_names)

        # BUG FIX VERIFICATION: driving:yes should NOT be in user tags
        self.assertNotIn('driving:yes', user_tag_names,
                        "Bug: 'driving:yes' is a system tag and should not appear in user tag autocomplete")
        
        # Verify it's properly in system_tags instead
        system_tag_names = list(data['system_tags'].keys())
        self.assertIn('driving:yes', system_tag_names,
                     "'driving:yes' should be in system_tags, not user_tags")

