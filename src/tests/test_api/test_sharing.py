"""Tests for the /api/shares/ owner and public map surfaces."""
import json
import uuid

from django.contrib.auth import get_user_model
from django.test import TestCase

from api.sharing.models import ShareLink
from test_utils.share_fixtures import (
    SHARE_TEST_BBOX,
    create_collection_share,
    create_feature_share,
    create_owned_collection,
    create_share_feature,
    create_tag_share,
)


class TestSharingAPI(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        self.feature = create_share_feature(self.user, name='Test Feature', tags=['shared-tag'])

    def test_create_tag_share(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({
                'share_type': 'tag',
                'tag': 'shared-tag',
                'allow_downloads': False
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertTrue(ShareLink.objects.filter(token=data['share_id']).exists())

    def test_create_tag_share_no_tag(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({'share_type': 'tag'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_tag_share_tag_not_found(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({'share_type': 'tag', 'tag': 'nonexistent-tag'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 404)

    def test_create_tag_share_invalid_json(self):
        response = self.client.post(
            '/api/shares/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_tag_share_extra_fields(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({
                'share_type': 'tag',
                'tag': 'shared-tag',
                'extra_field': 'should be rejected'
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_list_shares(self):
        create_tag_share(self.user, 'shared-tag')
        create_tag_share(self.user, 'another-tag')

        response = self.client.get('/api/shares/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('items', data)
        self.assertEqual(len(data['items']), 2)
        self.assertEqual(data['page'], 1)
        self.assertEqual(data['page_size'], 10)
        self.assertEqual(data['total_items'], 2)

    def test_delete_share(self):
        share = create_tag_share(self.user, 'shared-tag')
        response = self.client.delete(f'/api/shares/{share.token}/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(ShareLink.objects.filter(id=share.id).exists())

    def test_delete_share_not_found(self):
        response = self.client.delete(f'/api/shares/{uuid.uuid4()}/')
        self.assertEqual(response.status_code, 404)

    def test_get_public_share_info(self):
        share = create_tag_share(self.user, 'shared-tag', allow_downloads=True)
        response = self.client.get(f'/api/shares/{share.token}/info/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['tag'], 'shared-tag')
        self.assertEqual(data['allow_downloads'], True)

    def test_get_public_share_info_not_found(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/info/')
        self.assertEqual(response.status_code, 404)

    def test_get_public_share(self):
        share = create_tag_share(self.user, 'shared-tag')
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_get_public_share_with_downloads(self):
        share = create_tag_share(self.user, 'shared-tag', allow_downloads=True)
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_get_public_share_access_count(self):
        share = create_tag_share(self.user, 'shared-tag')
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        share.refresh_from_db()
        self.assertEqual(share.access_count, 1)

    def test_create_collection_share(self):
        collection = create_owned_collection(self.user, 'Shared Collection', features=[self.feature])
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({
                'share_type': 'collection',
                'collection_id': str(collection.id),
                'allow_downloads': False,
                'include_tags': True
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertTrue(ShareLink.objects.filter(token=data['share_id']).exists())

    def test_create_collection_share_invalid_json(self):
        response = self.client.post(
            '/api/shares/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_extra_fields(self):
        collection = create_owned_collection(self.user, 'Test Collection', features=[self.feature])
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({
                'share_type': 'collection',
                'collection_id': str(collection.id),
                'extra_field': 'should be rejected'
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_invalid_uuid(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({
                'share_type': 'collection',
                'collection_id': 'not-a-valid-uuid'
            }),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_missing_collection_id(self):
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({'share_type': 'collection', 'allow_downloads': False}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_get_public_collection_share(self):
        collection = create_owned_collection(self.user, 'Shared Collection', features=[self.feature])
        share = create_collection_share(self.user, collection)
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_get_public_collection_share_not_found(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/features/')
        self.assertEqual(response.status_code, 404)

    def test_share_id_validation(self):
        response = self.client.get('/api/shares/invalid-share-id/info/')
        self.assertEqual(response.status_code, 404)

    def test_collection_share_access_count(self):
        collection = create_owned_collection(self.user, 'Test Collection', features=[self.feature])
        share = create_collection_share(self.user, collection)
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        share.refresh_from_db()
        self.assertEqual(share.access_count, 1)

    def test_unauthorized_access(self):
        self.client.logout()
        response = self.client.post(
            '/api/shares/',
            data=json.dumps({'share_type': 'tag', 'tag': 'shared-tag'}),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 401)

    def test_list_shares_filters(self):
        collection = create_owned_collection(self.user, 'Filter Collection', features=[self.feature])
        tag_share = create_tag_share(self.user, 'shared-tag')
        collection_share = create_collection_share(self.user, collection)
        feature_share = create_feature_share(self.user, self.feature)

        by_type = json.loads(self.client.get('/api/shares/?type=tag').content)
        self.assertEqual(by_type['total_items'], 1)
        self.assertEqual(by_type['items'][0]['share_id'], tag_share.token)

        by_tag = json.loads(self.client.get('/api/shares/?tag=shared-tag').content)
        self.assertEqual(by_tag['total_items'], 1)
        self.assertEqual(by_tag['items'][0]['share_id'], tag_share.token)

        by_collection = json.loads(
            self.client.get(f'/api/shares/?collection_id={collection.id}').content
        )
        self.assertEqual(by_collection['total_items'], 1)
        self.assertEqual(by_collection['items'][0]['share_id'], collection_share.token)

        by_feature = json.loads(
            self.client.get(f'/api/shares/?feature_id={self.feature.id}').content
        )
        self.assertEqual(by_feature['total_items'], 1)
        self.assertEqual(by_feature['items'][0]['share_id'], feature_share.token)

        by_audience = json.loads(self.client.get('/api/shares/?audience=world').content)
        self.assertEqual(by_audience['total_items'], 3)

        invalid = self.client.get('/api/shares/?type=not-a-kind')
        self.assertEqual(invalid.status_code, 400)
