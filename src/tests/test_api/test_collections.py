"""Collections API: CRUD, membership rules, and bulk ops on the collection kernel."""
import json
import uuid

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule
from api.services.feature_service import FeatureService


def _point(name: str, tags=None, geometry_type='Point', coordinates=None) -> dict:
    if geometry_type == 'LineString':
        geom = {'type': 'LineString', 'coordinates': [[10.0, 20.0, 0.0], [11.0, 21.0, 0.0]]}
    elif geometry_type == 'Polygon':
        geom = {
            'type': 'Polygon',
            'coordinates': [[[10.0, 20.0, 0.0], [11.0, 20.0, 0.0], [11.0, 21.0, 0.0], [10.0, 21.0, 0.0], [10.0, 20.0, 0.0]]],
        }
    else:
        geom = {'type': 'Point', 'coordinates': coordinates or [10.0, 20.0, 0.0]}
    return {
        'type': 'Feature',
        'geometry': geom,
        'properties': {
            'name': name,
            'tags': tags or [],
        },
    }


def _collection_rules(collection) -> list[str]:
    return list(CollectionTagRule.objects.filter(collection=collection).values_list('tag', flat=True))


def _collection_pins(collection) -> list[int]:
    return list(CollectionFeatureMembership.objects.filter(collection=collection).values_list('feature_id', flat=True))


class TestCollectionsAPI(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser',
        )
        self.client.force_login(self.user)
        self.feature = FeatureService.create(self.user, _point('Test Feature', tags=['test']))

    def test_create_collection(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Test Collection',
                'description': 'A test collection',
                'tags': ['test'],
                'feature_ids': [self.feature.id],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data['collection']['name'], 'Test Collection')
        self.assertTrue(Collection.objects.filter(name='Test Collection').exists())

    def test_create_collection_no_name(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({'description': 'A test collection'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_invalid_json(self):
        response = self.client.post(
            '/api/collections/',
            data='invalid json',
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_extra_fields(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({'name': 'Test Collection', 'extra_field': 'should be rejected'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_invalid_feature_ids(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({'name': 'Test Collection', 'feature_ids': ['not', 'integers']}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_list_collections(self):
        Collection.objects.create(user=self.user, name='Collection 1')
        Collection.objects.create(user=self.user, name='Collection 2')
        response = self.client.get('/api/collections/')
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn('items', data)
        self.assertEqual(len(data['items']), 2)

    def test_get_collection(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Test Collection',
                'description': 'A test',
                'tags': ['test'],
                'feature_ids': [self.feature.id],
            }),
            content_type='application/json',
        )
        collection_id = response.json()['collection']['id']
        fetched = self.client.get(f'/api/collections/{collection_id}/')
        self.assertEqual(fetched.status_code, 200)
        self.assertEqual(fetched.json()['collection']['name'], 'Test Collection')
        self.assertEqual(fetched.json()['collection']['feature_count'], 1)

    def test_get_collection_not_found(self):
        response = self.client.get(f'/api/collections/{uuid.uuid4()}/')
        self.assertEqual(response.status_code, 404)

    def test_get_collection_unauthorized(self):
        User = get_user_model()
        other_user = User.objects.create_user(
            email='other@example.com',
            password='pass',
            username='other',
        )
        collection = Collection.objects.create(user=other_user, name='Other Collection')
        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 404)

    def test_update_collection(self):
        collection = Collection.objects.create(user=self.user, name='Original Name')
        CollectionTagRule.objects.create(collection=collection, tag='old')
        response = self.client.patch(
            f'/api/collections/{collection.id}/',
            data=json.dumps({
                'name': 'Updated Name',
                'description': 'Updated description',
                'tags': ['test'],
                'feature_ids': [self.feature.id],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        collection.refresh_from_db()
        self.assertEqual(collection.name, 'Updated Name')
        self.assertEqual(_collection_rules(collection), ['test'])

    def test_save_collection_strips_stale_tags(self):
        FeatureService.create(self.user, _point('Keep', tags=['keep'], coordinates=[11.0, 21.0, 0.0]))
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Stale Tags Collection',
                'tags': ['keep', 'gone'],
                'feature_ids': [],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = Collection.objects.get(id=response.json()['collection']['id'])
        self.assertEqual(_collection_rules(collection), ['keep'])

    def test_save_collection_strips_unknown_feature_ids(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Mixed IDs Collection',
                'tags': ['test'],
                'feature_ids': [self.feature.id, 999_999_999],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = Collection.objects.get(id=response.json()['collection']['id'])
        self.assertEqual(_collection_pins(collection), [self.feature.id])

    def test_update_collection_strips_missing_features(self):
        feature2 = FeatureService.create(self.user, _point('Second', tags=['test'], coordinates=[12.0, 22.0, 0.0]))
        collection = Collection.objects.create(user=self.user, name='Two Features')
        CollectionTagRule.objects.create(collection=collection, tag='test')
        CollectionFeatureMembership.objects.create(collection=collection, feature=self.feature)
        CollectionFeatureMembership.objects.create(collection=collection, feature=feature2)
        removed_id = feature2.id
        feature2.delete()
        response = self.client.patch(
            f'/api/collections/{collection.id}/',
            data=json.dumps({'feature_ids': [self.feature.id, removed_id]}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(_collection_pins(collection), [self.feature.id])

    def test_update_collection_invalid_json(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        response = self.client.patch(
            f'/api/collections/{collection.id}/',
            data='invalid json',
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_update_collection_extra_fields(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        response = self.client.patch(
            f'/api/collections/{collection.id}/',
            data=json.dumps({'name': 'Updated Name', 'extra_field': 'should be rejected'}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_delete_collection(self):
        collection = Collection.objects.create(user=self.user, name='To Delete')
        response = self.client.delete(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(Collection.objects.filter(id=collection.id).exists())

    def test_collection_features_route_removed(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionTagRule.objects.create(collection=collection, tag='test')
        self.assertEqual(self.client.get(f'/api/collections/{collection.id}/features/').status_code, 404)

    def test_apply_bulk_operations_to_collection(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionTagRule.objects.create(collection=collection, tag='test')
        CollectionFeatureMembership.objects.create(collection=collection, feature=self.feature)
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'tags': ['bulk-tag'], 'pointColor': '#ff0000'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn('updated_count', response.json())

    def test_apply_bulk_operations_to_collection_invalid(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionTagRule.objects.create(collection=collection, tag='test')
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'pointColor': 'invalid-color'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 400)

    def test_apply_bulk_operations_to_collection_not_found(self):
        response = self.client.post(
            f'/api/collections/{uuid.uuid4()}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'tags': ['test']}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 404)

    def test_apply_bulk_operations_to_collection_no_features(self):
        collection = Collection.objects.create(user=self.user, name='Empty Collection')
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'tags': ['bulk-tag'], 'pointColor': '#ff0000'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data['updated_count'], 0)

    def test_apply_bulk_operations_to_collection_point_icon(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=self.feature)
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'pointIcon': 'assets/icons/test.png'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertGreater(response.json()['updated_count'], 0)
        self.feature.refresh_from_db()
        self.assertEqual(self.feature.geojson['properties'].get('icon'), 'assets/icons/test.png')

    def test_apply_bulk_operations_to_collection_line_color(self):
        line_feature = FeatureService.create(
            self.user,
            _point('Test Line', tags=['line-feature'], geometry_type='LineString'),
        )
        collection = Collection.objects.create(user=self.user, name='Line Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=line_feature)
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'lineColor': '#ff00ff'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['updated_count'], 1)
        line_feature.refresh_from_db()
        self.assertEqual(line_feature.geojson['properties'].get('stroke'), '#ff00ff')

    def test_apply_bulk_operations_to_collection_polygon_color(self):
        polygon_feature = FeatureService.create(
            self.user,
            _point('Test Polygon', tags=['polygon-feature'], geometry_type='Polygon'),
        )
        collection = Collection.objects.create(user=self.user, name='Polygon Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=polygon_feature)
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'polyColor': '#0000ff'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['updated_count'], 1)
        polygon_feature.refresh_from_db()
        self.assertEqual(polygon_feature.geojson['properties'].get('stroke'), '#0000ff')
        self.assertEqual(polygon_feature.geojson['properties'].get('fill'), '#0000ff')

    def test_apply_bulk_operations_to_collection_all_operations(self):
        line_feature = FeatureService.create(
            self.user,
            _point('Test Line', tags=['test'], geometry_type='LineString'),
        )
        polygon_feature = FeatureService.create(
            self.user,
            _point('Test Polygon', tags=['test'], geometry_type='Polygon'),
        )
        collection = Collection.objects.create(user=self.user, name='Mixed Collection')
        CollectionTagRule.objects.create(collection=collection, tag='test')
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({
                'bulk_operations': {
                    'tags': ['comprehensive-test'],
                    'pointColor': '#ff0000',
                    'pointIcon': 'assets/icons/test.png',
                    'lineColor': '#00ff00',
                    'polyColor': '#0000ff',
                }
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['updated_count'], 3)
        self.feature.refresh_from_db()
        self.assertIn('comprehensive-test', self.feature.geojson['properties'].get('tags', []))
        self.assertEqual(self.feature.geojson['properties'].get('marker-color'), '#ff0000')
        line_feature.refresh_from_db()
        self.assertEqual(line_feature.geojson['properties'].get('stroke'), '#00ff00')
        polygon_feature.refresh_from_db()
        self.assertEqual(polygon_feature.geojson['properties'].get('fill'), '#0000ff')

    def test_apply_bulk_operations_to_collection_by_tags(self):
        feature1 = FeatureService.create(self.user, _point('Tagged Feature 1', tags=['collection-tag']))
        feature2 = FeatureService.create(
            self.user,
            _point('Tagged Feature 2', tags=['collection-tag'], coordinates=[11.0, 21.0, 0.0]),
        )
        collection = Collection.objects.create(user=self.user, name='Tag Collection')
        CollectionTagRule.objects.create(collection=collection, tag='collection-tag')
        response = self.client.post(
            f'/api/collections/{collection.id}/bulk-operations/',
            data=json.dumps({'bulk_operations': {'tags': ['bulk-applied'], 'pointColor': '#00ff00'}}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['updated_count'], 2)
        feature1.refresh_from_db()
        feature2.refresh_from_db()
        self.assertIn('bulk-applied', feature1.geojson['properties'].get('tags', []))
        self.assertIn('bulk-applied', feature2.geojson['properties'].get('tags', []))

    def test_collection_feature_count(self):
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=self.feature)
        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['collection']['feature_count'], 1)

    def test_collection_with_multiple_features(self):
        feature2 = FeatureService.create(
            self.user,
            _point('Test Feature 2', tags=['test'], coordinates=[11.0, 21.0, 0.0]),
        )
        collection = Collection.objects.create(user=self.user, name='Multi Feature Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=self.feature)
        CollectionFeatureMembership.objects.create(collection=collection, feature=feature2)
        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['collection']['feature_count'], 2)

    def test_unauthorized_access(self):
        self.client.logout()
        response = self.client.get('/api/collections/')
        self.assertEqual(response.status_code, 401)


class TestCollectionEdgeCases(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='edge@example.com',
            password='testpass123',
            username='edgeuser',
        )
        self.client.force_login(self.user)

    def test_create_collection_with_empty_feature_ids(self):
        FeatureService.create(self.user, _point('Tagged', tags=['test']))
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Empty Collection',
                'description': 'No features',
                'tags': ['test'],
                'feature_ids': [],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = Collection.objects.get(id=response.json()['collection']['id'])
        self.assertEqual(_collection_pins(collection), [])

    def test_create_collection_with_empty_tags(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'No Tags Collection',
                'description': 'Collection without tags',
                'tags': [],
                'feature_ids': [],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = Collection.objects.get(id=response.json()['collection']['id'])
        self.assertEqual(_collection_rules(collection), [])

    def test_create_collection_with_null_description(self):
        FeatureService.create(self.user, _point('Tagged', tags=['test']))
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Null Description Collection',
                'description': None,
                'tags': ['test'],
                'feature_ids': [],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = Collection.objects.get(id=response.json()['collection']['id'])
        self.assertIsNone(collection.description)

    def test_update_collection_to_empty_feature_ids(self):
        feature = FeatureService.create(self.user, _point('Test Feature'))
        collection = Collection.objects.create(user=self.user, name='Test Collection')
        CollectionFeatureMembership.objects.create(collection=collection, feature=feature)
        response = self.client.patch(
            f'/api/collections/{collection.id}/',
            data=json.dumps({'feature_ids': []}),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(_collection_pins(collection), [])
