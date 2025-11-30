"""
Tests for collections API endpoints.
"""
import json
import uuid
from django.test import TestCase
from django.contrib.gis.geos import Point

from api.models import Collection, FeatureStore
from geo_lib.feature_id import generate_feature_hash


class TestCollectionsAPI(TestCase):
    """Test collections API endpoints."""

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

        # Create test features
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # 3D coordinates with Z=0.0
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['test']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_feature_hash(self.feature_data)
        )

    def test_create_collection(self):
        """Test creating a collection."""
        collection_data = {
            'name': 'Test Collection',
            'description': 'A test collection',
            'tags': ['test'],
            'feature_ids': [self.feature.id]
        }
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.content)
        self.assertIn('collection', data)
        self.assertEqual(data['collection']['name'], 'Test Collection')
        self.assertTrue(Collection.objects.filter(name='Test Collection').exists())

    def test_create_collection_no_name(self):
        """Test creating a collection without a name."""
        collection_data = {
            'description': 'A test collection'
        }
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_invalid_json(self):
        """Test creating a collection with invalid JSON."""
        response = self.client.post(
            '/api/collections/create/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_extra_fields(self):
        """Test creating a collection with extra fields."""
        collection_data = {
            'name': 'Test Collection',
            'extra_field': 'should be rejected'
        }
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_invalid_feature_ids(self):
        """Test creating a collection with invalid feature_ids."""
        collection_data = {
            'name': 'Test Collection',
            'feature_ids': ['not', 'integers']
        }
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_list_collections(self):
        """Test listing collections."""
        Collection.objects.create(
            user=self.user,
            name='Collection 1',
            tags=['test']
        )
        Collection.objects.create(
            user=self.user,
            name='Collection 2',
            tags=['test']
        )

        response = self.client.get('/api/collections/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('collections', data)
        self.assertEqual(len(data['collections']), 2)

    def test_get_collection(self):
        """Test getting a collection by ID."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            description='A test',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('collection', data)
        self.assertEqual(data['collection']['name'], 'Test Collection')
        self.assertEqual(data['collection']['feature_count'], 1)

    def test_get_collection_not_found(self):
        """Test getting non-existent collection."""
        fake_uuid = uuid.uuid4()
        response = self.client.get(f'/api/collections/{fake_uuid}/')
        self.assertEqual(response.status_code, 404)

    def test_get_collection_unauthorized(self):
        """Test getting another user's collection."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other'
        )
        collection = Collection.objects.create(
            user=other_user,
            name='Other Collection'
        )

        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 404)

    def test_update_collection(self):
        """Test updating a collection."""
        collection = Collection.objects.create(
            user=self.user,
            name='Original Name',
            tags=['old']
        )

        update_data = {
            'name': 'Updated Name',
            'description': 'Updated description',
            'tags': ['new'],
            'feature_ids': [self.feature.id]
        }

        response = self.client.put(
            f'/api/collections/{collection.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        collection.refresh_from_db()
        self.assertEqual(collection.name, 'Updated Name')
        self.assertEqual(collection.tags, ['new'])

    def test_update_collection_invalid_json(self):
        """Test updating a collection with invalid JSON."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection'
        )

        response = self.client.put(
            f'/api/collections/{collection.id}/update/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_update_collection_extra_fields(self):
        """Test updating a collection with extra fields."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection'
        )

        update_data = {
            'name': 'Updated Name',
            'extra_field': 'should be rejected'
        }

        response = self.client.put(
            f'/api/collections/{collection.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_delete_collection(self):
        """Test deleting a collection."""
        collection = Collection.objects.create(
            user=self.user,
            name='To Delete'
        )

        response = self.client.delete(f'/api/collections/{collection.id}/delete/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(Collection.objects.filter(id=collection.id).exists())

    def test_get_collection_features(self):
        """Test getting features in a collection."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

        response = self.client.get(f'/api/collections/{collection.id}/features/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_get_collection_features_by_tags(self):
        """Test getting collection features filtered by tags."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test']
        )

        response = self.client.get(f'/api/collections/{collection.id}/features/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_apply_bulk_operations_to_collection(self):
        """Test applying bulk operations to collection features."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-tag'],
                'pointColor': '#ff0000'
            }
        }

        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('updated_count', data)

    def test_apply_bulk_operations_to_collection_invalid(self):
        """Test bulk operations with invalid payload."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test']
        )

        bulk_ops = {
            'bulk_operations': {
                'pointColor': 'invalid-color'
            }
        }

        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_bulk_operations_to_collection_not_found(self):
        """Test bulk operations on non-existent collection."""
        fake_uuid = uuid.uuid4()
        bulk_ops = {
            'bulk_operations': {
                'tags': ['test']
            }
        }

        response = self.client.post(
            f'/api/collections/{fake_uuid}/bulk-operations/',
            data=json.dumps(bulk_ops),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 404)

    def test_collection_feature_count(self):
        """Test that collection feature count is correct."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            feature_ids=[self.feature.id]
        )

        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['collection']['feature_count'], 1)

    def test_collection_with_multiple_features(self):
        """Test collection with multiple features."""
        # Create different feature data for the second feature
        feature2_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]  # Different coordinates
            },
            'properties': {
                'name': 'Test Feature 2',
                'tags': ['test']
            }
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geometry=Point(-122.4094, 37.7849, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_feature_hash(feature2_data)
        )

        collection = Collection.objects.create(
            user=self.user,
            name='Multi Feature Collection',
            feature_ids=[self.feature.id, feature2.id]
        )

        response = self.client.get(f'/api/collections/{collection.id}/features/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['feature_count'], 2)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot access collections."""
        self.client.logout()
        response = self.client.get('/api/collections/')
        self.assertEqual(response.status_code, 401)

