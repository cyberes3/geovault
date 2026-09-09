"""Tag catalog and names endpoints used by autocomplete and the tags page."""
from django.contrib.auth import get_user_model
from django.test import TestCase

from api.services.feature_service import FeatureService


def _point(name: str, tags=None, system_tags=None) -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
        'properties': {
            'name': name,
            'tags': tags or [],
            'system_tags': system_tags or [],
        },
    }


class TestUserTagsEndpoint(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='user-tags@example.com',
            password='testpass123',
            username='user-tags',
        )
        self.client.force_login(self.user)
        FeatureService.create(self.user, _point('Feature 1', tags=['alpha', 'beta', 'gamma'], system_tags=['type:point', 'elevation:high']))
        FeatureService.create(self.user, _point('Feature 2', tags=['beta', 'delta'], system_tags=['type:line']))
        FeatureService.create(self.user, _point('Feature 3', tags=['epsilon', 'alpha'], system_tags=['import-year:2025']))

    def test_names_endpoint_returns_unique_sorted_user_tags(self):
        response = self.client.get('/api/tags/names/')
        self.assertEqual(response.status_code, 200)
        tags = response.json()['items']
        self.assertEqual(tags, ['alpha', 'beta', 'delta', 'epsilon', 'gamma'])
        for tag in ['type:point', 'elevation:high', 'type:line', 'import-year:2025']:
            self.assertNotIn(tag, tags)

    def test_names_prefix_filter(self):
        response = self.client.get('/api/tags/names/', {'prefix': 'al'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['items'], ['alpha'])


class TestTagSeparation(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )
        self.client.force_login(self.user)

    def _catalog_names(self, kind: str) -> list[str]:
        response = self.client.get('/api/tags/', {'page_size': '100'})
        self.assertEqual(response.status_code, 200)
        return [item['name'] for item in response.json()['items'] if item['kind'] == kind]

    def test_user_and_system_tags_separated(self):
        FeatureService.create(
            self.user,
            _point('Test Point', tags=['hiking', 'mountain', 'scenic'], system_tags=['type:point', 'import-year:2025', 'elevation:high']),
        )
        user_tag_names = self._catalog_names('user')
        system_tag_names = self._catalog_names('system')
        for tag in ['hiking', 'mountain', 'scenic']:
            self.assertIn(tag, user_tag_names)
            self.assertNotIn(tag, system_tag_names)
        for tag in ['type:point', 'import-year:2025', 'elevation:high']:
            self.assertIn(tag, system_tag_names)
            self.assertNotIn(tag, user_tag_names)

    def test_system_tag_patterns_not_in_user_tags(self):
        system_tag_patterns = [
            'type:line',
            'import-year:2024',
            'import-month:december',
            'feature-year:2023',
            'feature-month:june',
            'source-file:test.gpx',
            'type:track',
            'elevation:high',
            'reverse_geocoding:success',
            'driving:yes',
        ]
        FeatureService.create(
            self.user,
            _point('Test Point with System Tags', tags=['user-tag-1', 'user-tag-2'], system_tags=system_tag_patterns),
        )
        user_tag_names = self._catalog_names('user')
        system_tag_names = self._catalog_names('system')
        for tag in system_tag_patterns:
            self.assertIn(tag, system_tag_names)
            self.assertNotIn(tag, user_tag_names)
        self.assertIn('user-tag-1', user_tag_names)
        self.assertNotIn('user-tag-1', system_tag_names)

    def test_multiple_features_tags_aggregated_correctly(self):
        FeatureService.create(self.user, _point('Feature 1', tags=['hiking', 'trail'], system_tags=['type:point', 'elevation:high']))
        FeatureService.create(self.user, _point('Feature 2', tags=['hiking', 'camping'], system_tags=['type:line', 'elevation:high']))
        FeatureService.create(self.user, _point('Feature 3', tags=['fishing', 'lake'], system_tags=['type:polygon', 'import-year:2025']))
        user_tag_names = set(self._catalog_names('user'))
        system_tag_names = set(self._catalog_names('system'))
        self.assertEqual(user_tag_names, {'hiking', 'trail', 'camping', 'fishing', 'lake'})
        self.assertEqual(system_tag_names, {'type:point', 'type:line', 'type:polygon', 'elevation:high', 'import-year:2025'})


class TestTagCatalogPagingAndSearch(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='page@example.com',
            password='testpass123',
            username='pageuser',
        )
        self.client.force_login(self.user)
        FeatureService.create(
            self.user,
            _point('Hike', tags=['hiking', 'trail'], system_tags=['type:point']),
        )

    def test_search_is_case_insensitive(self):
        response = self.client.get('/api/tags/', {'search': 'HIK'})
        self.assertEqual(response.status_code, 200)
        names = [item['name'] for item in response.json()['items']]
        self.assertIn('hiking', names)

    def test_search_no_matches(self):
        response = self.client.get('/api/tags/', {'search': 'nonexistenttag12345'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['items'], [])

    def test_catalog_is_paginated(self):
        for index in range(12):
            FeatureService.create(self.user, _point(f'N{index}', tags=[f'extra-{index}']))
        response = self.client.get('/api/tags/', {'page': '1', 'page_size': '10'})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data['items']), 10)
        self.assertGreater(data['total_items'], 10)
        self.assertGreaterEqual(data['total_pages'], 2)

    def test_legacy_by_tag_removed(self):
        self.assertEqual(self.client.get('/api/features/by-tag/').status_code, 404)
        self.assertEqual(self.client.get('/api/features/user-tags/').status_code, 404)
