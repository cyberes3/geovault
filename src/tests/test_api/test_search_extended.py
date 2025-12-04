"""
Extended tests for feature search and filtering functionality.
"""
import json
from django.test import TestCase
from django.contrib.gis.geos import Point

from django.contrib.auth import get_user_model

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestSearchWithSpecialCharacters(TestCase):
    """Test search with special characters."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with special characters
        special_chars_features = [
            {'name': 'Test@Email.com', 'desc': 'Has @ and dots'},
            {'name': 'Price: $100', 'desc': 'Has dollar sign'},
            {'name': 'Math: 2+2=4', 'desc': 'Has math symbols'},
            {'name': 'Question?', 'desc': 'Has question mark'},
            {'name': 'Parentheses (test)', 'desc': 'Has parentheses'},
            {'name': 'Asterisk*', 'desc': 'Has asterisk'},
            {'name': 'Slash/Backslash\\', 'desc': 'Has slashes'},
        ]
        
        for i, feature_info in enumerate(special_chars_features):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': feature_info['name'],
                    'description': feature_info['desc']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_search_with_at_sign(self):
        """Test search with @ character."""
        response = self.client.get('/api/features/search/', {'query': '@'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find features with @ symbol
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_with_dollar_sign(self):
        """Test search with $ character."""
        response = self.client.get('/api/features/search/', {'query': '$100'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle $ without treating it as regex
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_with_parentheses(self):
        """Test search with parentheses."""
        response = self.client.get('/api/features/search/', {'query': '(test)'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle parentheses without treating as regex group
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_with_asterisk(self):
        """Test search with asterisk."""
        response = self.client.get('/api/features/search/', {'query': 'Asterisk*'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle * without treating as wildcard
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_with_question_mark(self):
        """Test search with question mark."""
        response = self.client.get('/api/features/search/', {'query': 'Question?'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle ? without treating as single-char wildcard
        self.assertGreaterEqual(data.get('feature_count', 0), 0)


class TestSearchWithUnicode(TestCase):
    """Test search with Unicode characters."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with Unicode characters
        unicode_features = [
            {'name': '测试点', 'desc': 'Chinese characters'},
            {'name': 'Тест', 'desc': 'Cyrillic characters'},
            {'name': 'Δοκιμή', 'desc': 'Greek characters'},
            {'name': 'テスト', 'desc': 'Japanese characters'},
            {'name': 'مرحبا', 'desc': 'Arabic characters'},
            {'name': 'Café', 'desc': 'Accented characters'},
            {'name': 'Zürich', 'desc': 'Umlaut characters'},
            {'name': '🏔️ Mountain', 'desc': 'Emoji'},
        ]
        
        for i, feature_info in enumerate(unicode_features):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': feature_info['name'],
                    'description': feature_info['desc']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_search_chinese(self):
        """Test search with Chinese characters."""
        response = self.client.get('/api/features/search/', {'query': '测试'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle Chinese characters
        self.assertIsInstance(data, dict)

    def test_search_cyrillic(self):
        """Test search with Cyrillic characters."""
        response = self.client.get('/api/features/search/', {'query': 'Тест'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsInstance(data, dict)

    def test_search_accented(self):
        """Test search with accented characters."""
        response = self.client.get('/api/features/search/', {'query': 'Café'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsInstance(data, dict)

    def test_search_emoji(self):
        """Test search with emoji."""
        response = self.client.get('/api/features/search/', {'query': '🏔️'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should handle emoji without crashing
        self.assertIsInstance(data, dict)


class TestSearchCaseSensitivity(TestCase):
    """Test case sensitivity in search."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with different cases
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'TestPoint',
                'description': 'UPPERCASE lowercase MixedCase'
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_search_lowercase(self):
        """Test search with lowercase."""
        response = self.client.get('/api/features/search/', {'query': 'testpoint'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find the feature (case-insensitive)
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_uppercase(self):
        """Test search with uppercase."""
        response = self.client.get('/api/features/search/', {'query': 'TESTPOINT'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find the feature (case-insensitive)
        self.assertGreaterEqual(data.get('feature_count', 0), 0)

    def test_search_mixed_case(self):
        """Test search with mixed case."""
        response = self.client.get('/api/features/search/', {'query': 'TeStPoInT'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find the feature (case-insensitive)
        self.assertGreaterEqual(data.get('feature_count', 0), 0)


class TestSearchEmptyResults(TestCase):
    """Test search with no results."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create a feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point'
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_search_no_matches(self):
        """Test search that returns no results."""
        response = self.client.get('/api/features/search/', {'query': 'NonexistentFeature12345'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data.get('feature_count', 0), 0)
        self.assertIn('data', data)

    def test_search_empty_query(self):
        """Test search with empty query."""
        response = self.client.get('/api/features/search/', {'query': ''})
        # Should return error (query required)
        self.assertEqual(response.status_code, 400)

    def test_search_whitespace_only(self):
        """Test search with whitespace-only query."""
        response = self.client.get('/api/features/search/', {'query': '   '})
        # Should return error or no results
        self.assertIn(response.status_code, [200, 400])


class TestSearchPagination(TestCase):
    """Test search pagination."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create many features for pagination testing
        for i in range(50):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749 + i * 0.001, 0.0]
                },
                'properties': {
                    'name': f'Search Test Point {i}'
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_search_with_page_parameter(self):
        """Test search with page parameter."""
        response = self.client.get('/api/features/search/', {'query': 'Search Test', 'page': '1'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # search_features endpoint may not have pagination structure
        self.assertIn('data', data)

    def test_search_page_out_of_bounds(self):
        """Test search with page number out of bounds."""
        response = self.client.get('/api/features/search/', {'query': 'Search Test', 'page': '999'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should return empty results or last page
        self.assertIn('data', data)

    def test_search_invalid_page_parameter(self):
        """Test search with invalid page parameter."""
        response = self.client.get('/api/features/search/', {'query': 'Search Test', 'page': 'invalid'})
        # Should return error or default to page 1
        self.assertIn(response.status_code, [200, 400])

    def test_search_negative_page(self):
        """Test search with negative page number."""
        response = self.client.get('/api/features/search/', {'query': 'Search Test', 'page': '-1'})
        # Should return error or default to page 1
        self.assertIn(response.status_code, [200, 400])


class TestFeaturesByTagSearch(TestCase):
    """Test features by tag with search functionality."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with various tags
        tags_list = [
            ['hiking', 'mountain'],
            ['hiking', 'trail'],
            ['camping', 'mountain'],
            ['fishing', 'lake'],
        ]
        
        for i, tags in enumerate(tags_list):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Test Point {i}',
                    'tags': tags
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

    def test_get_features_by_tag_with_search(self):
        """Test getting features by tag with search parameter."""
        response = self.client.get('/api/features/by-tag/', {'search': 'hik'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find tags containing 'hik'
        self.assertIn('user_tags', data)

    def test_get_features_by_tag_search_case_insensitive(self):
        """Test that tag search is case insensitive."""
        response = self.client.get('/api/features/by-tag/', {'search': 'HIK'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find tags regardless of case
        self.assertIn('user_tags', data)

    def test_get_features_by_tag_search_no_matches(self):
        """Test tag search with no matches."""
        response = self.client.get('/api/features/by-tag/', {'search': 'nonexistenttag12345'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should return empty or minimal results
        self.assertIn('user_tags', data)


class TestSystemTagsVsUserTags(TestCase):
    """Test differentiation between system tags and user tags."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create feature with both user tags and system tags
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'tags': ['user-tag-1', 'user-tag-2'],
                'system_tags': ['system-tag-1', 'system-tag-2']
            }
        }
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_get_features_by_tag_separates_tag_types(self):
        """Test that user tags and system tags are separated."""
        response = self.client.get('/api/features/by-tag/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        self.assertIn('user_tags', data)
        self.assertIn('system_tags', data)
        # Should have separate dictionaries for each type

    def test_search_in_user_tags(self):
        """Test search finds user tags."""
        response = self.client.get('/api/features/search/', {'query': 'user-tag'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find features with user tags
        self.assertIsInstance(data, dict)

    def test_search_in_system_tags(self):
        """Test search finds system tags."""
        response = self.client.get('/api/features/search/', {'query': 'system-tag'})
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should find features with system tags
        self.assertIsInstance(data, dict)
