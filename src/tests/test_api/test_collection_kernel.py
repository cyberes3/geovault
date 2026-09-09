import json
import uuid

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule
from api.services.feature_service import FeatureService
from geo_lib.collections.membership import CollectionMembership


def _point(name: str, tags=None) -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
        'properties': {
            'name': name,
            'tags': tags or [],
        },
    }


class TestCollectionKernelApi(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='colapi@example.com',
            password='testpass123',
            username='colapi',
        )
        self.client.force_login(self.user)
        self.feature = FeatureService.create(self.user, _point('A', tags=['lake']))

    def test_create_list_get_patch_delete(self):
        response = self.client.post(
            '/api/collections/',
            data=json.dumps({
                'name': 'Lakes',
                'description': 'Water',
                'tags': ['lake'],
                'feature_ids': [self.feature.id],
            }),
            content_type='application/json',
        )
        self.assertEqual(response.status_code, 201)
        collection = response.json()['collection']
        self.assertEqual(collection['name'], 'Lakes')
        self.assertEqual(collection['tags'], ['lake'])
        self.assertEqual(collection['feature_ids'], [self.feature.id])
        self.assertEqual(collection['feature_count'], 1)
        collection_id = collection['id']
        uuid.UUID(collection_id)

        listed = self.client.get('/api/collections/')
        self.assertEqual(listed.status_code, 200)
        self.assertEqual(listed.json()['total_items'], 1)

        fetched = self.client.get(f'/api/collections/{collection_id}/')
        self.assertEqual(fetched.status_code, 200)
        self.assertEqual(fetched.json()['collection']['feature_count'], 1)

        patched = self.client.patch(
            f'/api/collections/{collection_id}/',
            data=json.dumps({'description': None}),
            content_type='application/json',
        )
        self.assertEqual(patched.status_code, 200)
        self.assertEqual(patched.json()['collection']['description'], '')

        deleted = self.client.delete(f'/api/collections/{collection_id}/')
        self.assertEqual(deleted.status_code, 200)
        self.assertFalse(Collection.objects.filter(id=collection_id).exists())

    def test_suffix_and_features_routes_removed(self):
        collection = Collection.objects.create(user=self.user, name='X')
        self.assertEqual(self.client.post('/api/collections/create/', data='{}', content_type='application/json').status_code, 404)
        self.assertEqual(self.client.get(f'/api/collections/{collection.id}/features/').status_code, 404)

    def test_places_never_join(self):
        places = FeatureService.create(self.user, _point('Place', tags=['lake']), scope='places')
        collection = Collection.objects.create(user=self.user, name='Main')
        CollectionTagRule.objects.create(collection=collection, tag='lake')
        CollectionFeatureMembership.objects.create(collection=collection, feature=places)
        response = self.client.get(f'/api/collections/{collection.id}/')
        self.assertEqual(response.json()['collection']['feature_count'], 1)
        self.assertEqual(CollectionMembership.feature_ids(collection), {self.feature.id})
