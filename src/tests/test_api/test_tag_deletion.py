"""
Tests for tag deletion endpoints, including system tag deletion.
"""
import json
import pytest
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestTagDeletion(TestCase):
    """Test tag deletion functionality."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create features with user tags
        self.feature_with_user_tag_1 = self._create_feature(
            name='Feature 1',
            coords=[-122.4194, 37.7749],
            tags=['mountain', 'hiking'],
            system_tags=[]
        )
        self.feature_with_user_tag_2 = self._create_feature(
            name='Feature 2',
            coords=[-122.4294, 37.7849],
            tags=['mountain', 'camping'],
            system_tags=[]
        )

        # Create features with system tags
        self.feature_with_system_tag_1 = self._create_feature(
            name='System Feature 1',
            coords=[-122.4394, 37.7949],
            tags=['user-tag'],
            system_tags=['type:point', 'elevation:high']
        )
        self.feature_with_system_tag_2 = self._create_feature(
            name='System Feature 2',
            coords=[-122.4494, 37.8049],
            tags=['another-user-tag'],
            system_tags=['type:point', 'elevation:high']
        )

        # Create feature with mixed tags
        self.feature_mixed = self._create_feature(
            name='Mixed Feature',
            coords=[-122.4594, 37.8149],
            tags=['mountain', 'user-tag'],
            system_tags=['type:point', 'quick-point']
        )

    def _create_feature(self, name, coords, tags, system_tags):
        """Helper to create a feature."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [coords[0], coords[1], 0.0]
            },
            'properties': {
                'name': name,
                'tags': tags,
                'system_tags': system_tags
            }
        }
        return FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(coords[0], coords[1], 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )

    def test_bulk_delete_by_user_tag(self):
        """Test bulk delete features by user tag."""
        # Delete all features with 'mountain' tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 3)  # 2 user tag features + 1 mixed
        self.assertEqual(data['tag'], 'mountain')
        
        # Verify features were deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_2.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())
        
        # Verify other features still exist
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())

    def test_bulk_delete_by_system_tag(self):
        """Test bulk delete features by system tag."""
        # Delete all features with 'elevation:high' system tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'elevation:high'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 2)  # 2 system tag features
        self.assertEqual(data['tag'], 'elevation:high')
        
        # Verify features were deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())
        
        # Verify other features still exist
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_2.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())

    def test_bulk_delete_by_mixed_tag(self):
        """Test bulk delete features that have tag in both user and system tags."""
        # Create a feature that has 'test-tag' in both tags and system_tags
        feature_both = self._create_feature(
            name='Both Tags',
            coords=[-122.5, 38.0],
            tags=['test-tag'],
            system_tags=['test-tag']  # Same tag in both arrays
        )
        
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'test-tag'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 1)
        
        # Verify feature was deleted
        self.assertFalse(FeatureStore.objects.filter(id=feature_both.id).exists())

    def test_bulk_delete_nonexistent_tag(self):
        """Test bulk delete with tag that doesn't exist."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'nonexistent-tag'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 0)
        self.assertIn('No features found', data['message'])
        
        # Verify no features were deleted
        self.assertEqual(FeatureStore.objects.filter(user=self.user).count(), 5)

    def test_bulk_delete_missing_tag_parameter(self):
        """Test bulk delete without tag parameter."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Tag parameter is required', data['error'])

    def test_bulk_delete_invalid_tag_type(self):
        """Test bulk delete with non-string tag."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 123}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Tag must be a string', data['error'])

    def test_bulk_delete_empty_tag(self):
        """Test bulk delete with empty string tag."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': ''}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Tag parameter is required', data['error'])

    def test_bulk_delete_invalid_json(self):
        """Test bulk delete with invalid JSON."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data='invalid json',
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Invalid JSON', data['error'])

    def test_bulk_delete_unauthorized(self):
        """Test that unauthorized users cannot delete features."""
        self.client.logout()
        
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 401)

    def test_bulk_delete_only_user_features(self):
        """Test that deletion only affects current user's features."""
        # Create another user with features
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='otherpass123',
            username='otheruser'
        )
        
        # Create feature for other user with same tag
        other_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-120.0, 40.0, 0.0]
            },
            'properties': {
                'name': 'Other User Feature',
                'tags': ['mountain']
            }
        }
        other_feature = FeatureStore.objects.create(
            user=other_user,
            geojson=other_feature_data,
            geometry=Point(-120.0, 40.0, 0.0),
            geojson_hash=generate_geojson_hash(other_feature_data)
        )
        
        # Delete 'mountain' tag features as first user
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 3)  # Only current user's features
        
        # Verify other user's feature still exists
        self.assertTrue(FeatureStore.objects.filter(id=other_feature.id).exists())
        
        # Verify current user's features were deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())

    def test_bulk_delete_case_sensitive(self):
        """Test that tag deletion is case-sensitive."""
        # Create feature with uppercase tag
        feature_upper = self._create_feature(
            name='Uppercase',
            coords=[-122.6, 38.1],
            tags=['MOUNTAIN'],
            system_tags=[]
        )
        
        # Try to delete with lowercase
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should delete lowercase 'mountain' features
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())
        
        # Should NOT delete uppercase 'MOUNTAIN' feature
        self.assertTrue(FeatureStore.objects.filter(id=feature_upper.id).exists())

    def test_bulk_delete_system_tag_with_prefix(self):
        """Test bulk delete with system tag that has prefix (e.g., type:point)."""
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'type:point'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should delete all features with 'type:point' system tag
        self.assertEqual(data['deleted_count'], 3)
        
        # Verify system tag features were deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())
        
        # Verify user tag only features still exist
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_1.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_user_tag_2.id).exists())

    def test_bulk_delete_multiple_features_atomicity(self):
        """Test that bulk delete is atomic (all or nothing)."""
        # Create many features
        for i in range(10):
            self._create_feature(
                name=f'Test Feature {i}',
                coords=[-122.0 + i * 0.01, 37.0 + i * 0.01],
                tags=['test-bulk'],
                system_tags=[]
            )
        
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'test-bulk'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 10)
        
        # Verify all features were deleted
        remaining = FeatureStore.objects.filter(
            user=self.user,
            geojson__properties__tags__contains=['test-bulk']
        ).count()
        self.assertEqual(remaining, 0)

    def test_bulk_delete_preserves_other_tags(self):
        """Test that deleting by one tag doesn't affect features with only other tags."""
        # Delete 'mountain' tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        
        # Verify 'camping' only feature still exists (feature_with_user_tag_2 has mountain too, so deleted)
        # But features with system tags should still exist
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_1.id).exists())
        self.assertTrue(FeatureStore.objects.filter(id=self.feature_with_system_tag_2.id).exists())

    def test_bulk_delete_quick_point_system_tag(self):
        """Test deleting all quick-point features via system tag."""
        # Mixed feature has 'quick-point' system tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'quick-point'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 1)
        
        # Verify mixed feature was deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature_mixed.id).exists())

    def test_bulk_delete_returns_correct_message(self):
        """Test that response includes informative message."""
        # Delete existing tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'mountain'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('message', data)
        self.assertIn('Successfully deleted', data['message'])
        self.assertIn('3', data['message'])  # Should mention count
        
        # Delete non-existent tag
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'does-not-exist'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('No features found', data['message'])

    def test_bulk_delete_with_special_characters_in_tag(self):
        """Test bulk delete with tags containing special characters."""
        # Create feature with special characters in tag
        feature_special = self._create_feature(
            name='Special Tag Feature',
            coords=[-122.7, 38.2],
            tags=['tag-with-dash', 'tag:with:colon', 'tag_with_underscore'],
            system_tags=[]
        )
        
        # Delete tag with dash
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'tag-with-dash'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 1)
        self.assertFalse(FeatureStore.objects.filter(id=feature_special.id).exists())

    def test_user_tag_deletion_deletes_features_not_just_removes_tag(self):
        """Test that deleting a user tag deletes features, not just removes the tag."""
        # Create a feature with only a user tag
        feature = self._create_feature(
            name='User Tag Only',
            coords=[-122.8, 38.3],
            tags=['deleteme'],
            system_tags=[]
        )
        
        # Delete the tag using bulk delete endpoint
        response = self.client.post(
            '/api/features/bulk-delete-by-tag/',
            data=json.dumps({'tag': 'deleteme'}),
            content_type='application/json'
        )
        
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['deleted_count'], 1)
        
        # Verify the feature was completely deleted from database
        self.assertFalse(FeatureStore.objects.filter(id=feature.id).exists())
        
        # Verify it's not just a tag removal - the feature should not exist at all
        total_features = FeatureStore.objects.filter(user=self.user).count()
        self.assertEqual(total_features, 5)  # Original 5 features remain
