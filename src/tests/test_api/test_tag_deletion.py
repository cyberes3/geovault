"""Tag delete API: strip a user tag or delete features that carry a tag."""
from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import FeatureStore
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


class TestTagDeletion(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )
        self.client.force_login(self.user)
        self.feature_with_user_tag_1 = FeatureService.create(
            self.user, _point('Feature 1', tags=['mountain', 'hiking'])
        )
        self.feature_with_user_tag_2 = FeatureService.create(
            self.user, _point('Feature 2', tags=['mountain', 'camping'])
        )
        self.feature_with_system_tag_1 = FeatureService.create(
            self.user, _point('System Feature 1', tags=['user-tag'], system_tags=['type:point', 'elevation:high'])
        )
        self.feature_with_system_tag_2 = FeatureService.create(
            self.user, _point('System Feature 2', tags=['another-user-tag'], system_tags=['type:point', 'elevation:high'])
        )
        self.feature_mixed = FeatureService.create(
            self.user, _point('Mixed Feature', tags=['mountain', 'user-tag'], system_tags=['type:point', 'quick-point'])
        )

    def test_delete_features_by_user_tag(self):
        response = self.client.delete('/api/tags/mountain/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data['deleted_count'], 3)
        self.assertEqual(data['tag'], 'mountain')
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_2.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())

    def test_delete_features_by_system_tag(self):
        response = self.client.delete('/api/tags/elevation:high/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 2)
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())

    def test_delete_features_nonexistent_tag(self):
        response = self.client.delete('/api/tags/nonexistent-tag/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 0)
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 5)

    def test_legacy_bulk_delete_route_removed(self):
        response = self.client.post('/api/features/bulk-delete-by-tag/', data='{}', content_type='application/json')
        self.assertEqual(response.status_code, 404)

    def test_delete_unauthorized(self):
        self.client.logout()
        response = self.client.delete('/api/tags/mountain/?delete_features=true')
        self.assertEqual(response.status_code, 401)

    def test_delete_only_current_user_features(self):
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='otherpass123',
            username='otheruser',
        )
        other_feature = FeatureService.create(other_user, _point('Other User Feature', tags=['mountain']))
        response = self.client.delete('/api/tags/mountain/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 3)
        self.assertTrue(FeatureStore.objects.filter(id=other_feature.id).exists())

    def test_delete_normalizes_case(self):
        FeatureService.create(self.user, _point('Uppercase', tags=['RIDGE']))
        response = self.client.delete('/api/tags/ridge/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 1)

    def test_delete_features_by_type_point(self):
        response = self.client.delete('/api/tags/type:point/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 3)
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())

    def test_remove_user_tag_keeps_features(self):
        response = self.client.delete('/api/tags/hiking/')
        self.assertEqual(response.status_code, 200)
        self.feature_with_user_tag_1.refresh_from_db()
        self.assertNotIn('hiking', self.feature_with_user_tag_1.geojson['properties']['tags'])
        self.assertIn('mountain', self.feature_with_user_tag_1.geojson['properties']['tags'])
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())

    def test_cannot_strip_system_tag(self):
        response = self.client.delete('/api/tags/type:point/')
        self.assertEqual(response.status_code, 400)

    def test_delete_quick_point_system_tag(self):
        response = self.client.delete('/api/tags/quick-point/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 1)
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())

    def test_delete_special_character_tags(self):
        feature_special = FeatureService.create(
            self.user,
            _point('Special Tag Feature', tags=['tag-with-dash', 'tag:with:colon', 'tag_with_underscore']),
        )
        response = self.client.delete('/api/tags/tag-with-dash/?delete_features=true')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['deleted_count'], 1)
        self.assertFalse(FeatureStore.objects.filter(id=feature_special.id).exists())
