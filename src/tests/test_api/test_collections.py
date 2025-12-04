"""
Tests for collections API endpoints.
"""
import json
import uuid
from django.test import TestCase
from django.contrib.gis.geos import Point

from django.contrib.auth import get_user_model

from api.models import Collection, FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestCollectionsAPI(TestCase):
    """Test collections API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
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
            geojson_hash=generate_geojson_hash(self.feature_data)
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

    def test_apply_bulk_operations_to_collection_no_features(self):
        """Test bulk operations on collection with no features."""
        collection = Collection.objects.create(
            user=self.user,
            name='Empty Collection',
            tags=[],  # No tags to avoid matching existing features
            feature_ids=[]
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
        self.assertEqual(data['updated_count'], 0)
        self.assertIn('msg', data)

    def test_apply_bulk_operations_to_collection_point_icon(self):
        """Test applying point icon through bulk operations to collection."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

        bulk_ops = {
            'bulk_operations': {
                'pointIcon': 'assets/icons/test.png'
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
        self.assertGreater(data['updated_count'], 0)

        # Verify feature got the icon
        self.feature.refresh_from_db()
        props = self.feature.geojson['properties']
        self.assertEqual(props.get('icon'), 'assets/icons/test.png')

    def test_apply_bulk_operations_to_collection_line_color(self):
        """Test applying line color through bulk operations to collection."""
        # Create a line feature
        line_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]
            },
            'properties': {
                'name': 'Test Line',
                'tags': ['line-feature']  # Different tag to avoid matching setup feature
            }
        }
        line_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=line_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(line_feature_data)
        )

        collection = Collection.objects.create(
            user=self.user,
            name='Line Collection',
            tags=[],  # No tags to avoid matching other features
            feature_ids=[line_feature.id]
        )

        bulk_ops = {
            'bulk_operations': {
                'lineColor': '#ff00ff'
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
        self.assertEqual(data['updated_count'], 1)

        # Verify line feature got the color
        line_feature.refresh_from_db()
        props = line_feature.geojson['properties']
        self.assertEqual(props.get('stroke'), '#FF00FF')

    def test_apply_bulk_operations_to_collection_polygon_color(self):
        """Test applying polygon color through bulk operations to collection."""
        # Create a polygon feature
        polygon_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749, 0.0], [-122.4094, 37.7749, 0.0],
                                [-122.4094, 37.7849, 0.0], [-122.4194, 37.7849, 0.0],
                                [-122.4194, 37.7749, 0.0]]]
            },
            'properties': {
                'name': 'Test Polygon',
                'tags': ['polygon-feature']  # Different tag to avoid matching setup feature
            }
        }
        polygon_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=polygon_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(polygon_feature_data)
        )

        collection = Collection.objects.create(
            user=self.user,
            name='Polygon Collection',
            tags=[],  # No tags to avoid matching other features
            feature_ids=[polygon_feature.id]
        )

        bulk_ops = {
            'bulk_operations': {
                'polyColor': '#0000ff'
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
        self.assertEqual(data['updated_count'], 1)

        # Verify polygon feature got the color
        polygon_feature.refresh_from_db()
        props = polygon_feature.geojson['properties']
        self.assertEqual(props.get('stroke'), '#0000FF')
        self.assertEqual(props.get('fill'), '#0000FF')

    def test_apply_bulk_operations_to_collection_all_operations(self):
        """Test applying all bulk operation types to collection."""
        # Create features of different types
        point_feature = self.feature
        line_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749, 0.0], [-122.4094, 37.7849, 0.0]]
            },
            'properties': {
                'name': 'Test Line',
                'tags': ['test']
            }
        }
        line_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=line_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(line_feature_data)
        )

        polygon_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749, 0.0], [-122.4094, 37.7749, 0.0],
                                [-122.4094, 37.7849, 0.0], [-122.4194, 37.7849, 0.0],
                                [-122.4194, 37.7749, 0.0]]]
            },
            'properties': {
                'name': 'Test Polygon',
                'tags': ['test']
            }
        }
        polygon_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=polygon_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(polygon_feature_data)
        )

        collection = Collection.objects.create(
            user=self.user,
            name='Mixed Collection',
            tags=['test'],
            feature_ids=[point_feature.id, line_feature.id, polygon_feature.id]
        )

        bulk_ops = {
            'bulk_operations': {
                'tags': ['comprehensive-test'],
                'pointColor': '#ff0000',
                'pointIcon': 'assets/icons/test.png',
                'lineColor': '#00ff00',
                'polyColor': '#0000ff'
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
        self.assertEqual(data['updated_count'], 3)

        # Verify operations were applied to each feature type
        point_feature.refresh_from_db()
        point_props = point_feature.geojson['properties']
        self.assertIn('comprehensive-test', point_props.get('tags', []))
        self.assertEqual(point_props.get('marker-color'), '#FF0000')
        self.assertEqual(point_props.get('icon'), 'assets/icons/test.png')

        line_feature.refresh_from_db()
        line_props = line_feature.geojson['properties']
        self.assertEqual(line_props.get('stroke'), '#00FF00')

        polygon_feature.refresh_from_db()
        poly_props = polygon_feature.geojson['properties']
        self.assertEqual(poly_props.get('stroke'), '#0000FF')
        self.assertEqual(poly_props.get('fill'), '#0000FF')

    def test_apply_bulk_operations_to_collection_by_tags(self):
        """Test bulk operations on collection that matches features by tags."""
        # Create features with matching tags
        feature1_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Tagged Feature 1',
                'tags': ['collection-tag']
            }
        }
        feature1 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature1_data)
        )

        feature2_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            },
            'properties': {
                'name': 'Tagged Feature 2',
                'tags': ['collection-tag']
            }
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geometry=Point(-122.4094, 37.7849, 0.0),
            geojson_hash=generate_geojson_hash(feature2_data)
        )

        # Collection matches by tags, not feature_ids
        collection = Collection.objects.create(
            user=self.user,
            name='Tag Collection',
            tags=['collection-tag'],
            feature_ids=[]
        )

        bulk_ops = {
            'bulk_operations': {
                'tags': ['bulk-applied'],
                'pointColor': '#00ff00'
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
        self.assertEqual(data['updated_count'], 2)

        # Verify both features were updated
        feature1.refresh_from_db()
        feature2.refresh_from_db()
        self.assertIn('bulk-applied', feature1.geojson['properties'].get('tags', []))
        self.assertIn('bulk-applied', feature2.geojson['properties'].get('tags', []))
        self.assertEqual(feature1.geojson['properties'].get('marker-color'), '#00FF00')
        self.assertEqual(feature2.geojson['properties'].get('marker-color'), '#00FF00')

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
            geojson_hash=generate_geojson_hash(feature2_data)
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


class TestCollectionEdgeCases(TestCase):
    """Edge case tests for collection operations."""
    
    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='edge@example.com',
            password='testpass123',
            username='edgeuser'
        )
        self.client.force_login(self.user)
    
    def test_create_collection_with_empty_feature_ids(self):
        """Test creating a collection with empty feature_ids array."""
        collection_data = {
            'name': 'Empty Collection',
            'description': 'No features',
            'tags': ['test'],
            'feature_ids': []  # Empty feature_ids
        }
        
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.content)
        collection_id = data['collection']['id']
        
        # Verify collection was created with empty feature_ids
        collection = Collection.objects.get(id=collection_id)
        self.assertEqual(collection.feature_ids, [])
    
    def test_create_collection_with_empty_tags(self):
        """Test creating a collection with empty tags array."""
        collection_data = {
            'name': 'No Tags Collection',
            'description': 'Collection without tags',
            'tags': [],  # Empty tags
            'feature_ids': []
        }
        
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.content)
        collection_id = data['collection']['id']
        
        # Verify collection was created with empty tags
        collection = Collection.objects.get(id=collection_id)
        self.assertEqual(collection.tags, [])
    
    def test_create_collection_with_null_description(self):
        """Test creating a collection with null description."""
        collection_data = {
            'name': 'Null Description Collection',
            'description': None,  # Null description
            'tags': ['test'],
            'feature_ids': []
        }
        
        response = self.client.post(
            '/api/collections/create/',
            data=json.dumps(collection_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.content)
        collection_id = data['collection']['id']
        
        # Verify collection was created with null description
        collection = Collection.objects.get(id=collection_id)
        self.assertIsNone(collection.description)
    
    def test_update_collection_to_empty_feature_ids(self):
        """Test updating a collection to have empty feature_ids."""
        # Create collection with a feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            feature_ids=[feature.id]
        )
        
        # Update to empty feature_ids
        update_data = {
            'feature_ids': []  # Empty array
        }
        
        response = self.client.put(
            f'/api/collections/{collection.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Verify feature_ids is now empty
        collection.refresh_from_db()
        self.assertEqual(collection.feature_ids, [])
    
    def test_query_collection_features_when_empty(self):
        """Test querying features of an empty collection."""
        collection = Collection.objects.create(
            user=self.user,
            name='Empty Collection',
            feature_ids=[]  # No features
        )
        
        response = self.client.get(f'/api/collections/{collection.id}/features/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        # Should return empty or zero count
        self.assertIn('feature_count', data)
        self.assertEqual(data['feature_count'], 0)
