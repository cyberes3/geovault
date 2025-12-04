"""
Tests for sharing API endpoints (tag shares, collection shares, public access).
"""
import json
import uuid
from django.test import TestCase
from django.contrib.gis.geos import Point

from django.contrib.auth import get_user_model

from api.models import TagShare, CollectionShare, Collection, FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestSharingAPI(TestCase):
    """Test sharing API endpoints."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create test feature with tag
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]  # 3D coordinates with Z=0.0
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['shared-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

    def test_create_tag_share(self):
        """Test creating a tag share."""
        share_data = {
            'tag': 'shared-tag',
            'allow_downloads': False
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertTrue(TagShare.objects.filter(share_id=data['share_id']).exists())

    def test_create_tag_share_no_tag(self):
        """Test creating a tag share without tag."""
        share_data = {}
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_tag_share_tag_not_found(self):
        """Test creating a share for non-existent tag."""
        share_data = {
            'tag': 'nonexistent-tag'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 404)

    def test_create_tag_share_invalid_json(self):
        """Test creating a tag share with invalid JSON."""
        response = self.client.post(
            '/api/sharing/create/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_tag_share_extra_fields(self):
        """Test creating a tag share with extra fields."""
        share_data = {
            'tag': 'shared-tag',
            'extra_field': 'should be rejected'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_list_shares(self):
        """Test listing shares."""
        TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user
        )
        TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='another-tag',
            user=self.user
        )

        response = self.client.get('/api/sharing/list/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('shares', data)
        self.assertEqual(len(data['shares']), 2)

    def test_delete_share(self):
        """Test deleting a share."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user
        )

        response = self.client.delete(f'/api/sharing/{share.share_id}/')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(TagShare.objects.filter(id=share.id).exists())

    def test_delete_share_not_found(self):
        """Test deleting non-existent share."""
        fake_share_id = str(uuid.uuid4())
        response = self.client.delete(f'/api/sharing/{fake_share_id}/')
        self.assertEqual(response.status_code, 404)

    def test_get_public_share_info(self):
        """Test getting public share info."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=True
        )

        response = self.client.get(f'/api/sharing/public/info/{share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('tag', data)
        self.assertEqual(data['tag'], 'shared-tag')
        self.assertEqual(data['allow_downloads'], True)

    def test_get_public_share_info_not_found(self):
        """Test getting info for non-existent share."""
        fake_share_id = str(uuid.uuid4())
        response = self.client.get(f'/api/sharing/public/info/{fake_share_id}/')
        self.assertEqual(response.status_code, 404)

    def test_get_public_share(self):
        """Test getting public share data."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user
        )

        response = self.client.get(f'/api/sharing/public/{share.share_id}/?bbox=-123,37,-122,38')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_get_public_share_with_downloads(self):
        """Test getting public share with downloads allowed."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            allow_downloads=True
        )

        response = self.client.get(f'/api/sharing/public/{share.share_id}/?bbox=-123,37,-122,38')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_get_public_share_access_count(self):
        """Test that access count increments."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user,
            access_count=0
        )

        initial_count = share.access_count
        response = self.client.get(f'/api/sharing/public/{share.share_id}/?bbox=-123,37,-122,38')
        self.assertEqual(response.status_code, 200)
        share.refresh_from_db()
        self.assertEqual(share.access_count, initial_count + 1)

    def test_create_collection_share(self):
        """Test creating a collection share."""
        collection = Collection.objects.create(
            user=self.user,
            name='Shared Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

        share_data = {
            'collection_id': str(collection.id),
            'allow_downloads': False,
            'include_tags': True
        }

        response = self.client.post(
            '/api/sharing/collections/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertTrue(CollectionShare.objects.filter(share_id=data['share_id']).exists())

    def test_create_collection_share_invalid_json(self):
        """Test creating a collection share with invalid JSON."""
        response = self.client.post(
            '/api/sharing/collections/create/',
            data='invalid json',
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_extra_fields(self):
        """Test creating a collection share with extra fields."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            feature_ids=[self.feature.id]
        )

        share_data = {
            'collection_id': str(collection.id),
            'extra_field': 'should be rejected'
        }

        response = self.client.post(
            '/api/sharing/collections/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_invalid_uuid(self):
        """Test creating a collection share with invalid UUID."""
        share_data = {
            'collection_id': 'not-a-valid-uuid'
        }

        response = self.client.post(
            '/api/sharing/collections/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_collection_share_missing_collection_id(self):
        """Test creating a collection share without collection_id."""
        share_data = {
            'allow_downloads': False
        }

        response = self.client.post(
            '/api/sharing/collections/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_get_public_collection_share(self):
        """Test getting public collection share."""
        collection = Collection.objects.create(
            user=self.user,
            name='Shared Collection',
            feature_ids=[self.feature.id]
        )
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=collection,
            user=self.user
        )

        response = self.client.get(f'/api/sharing/public/collection/{share.share_id}/?bbox=-123,37,-122,38')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_get_public_collection_share_not_found(self):
        """Test getting non-existent collection share."""
        fake_share_id = str(uuid.uuid4())
        response = self.client.get(f'/api/sharing/public/collection/{fake_share_id}/')
        self.assertEqual(response.status_code, 404)

    def test_share_id_validation(self):
        """Test that invalid share IDs are rejected."""
        response = self.client.get('/api/sharing/public/info/invalid-share-id/')
        self.assertEqual(response.status_code, 404)

    def test_collection_share_access_count(self):
        """Test that collection share access count increments."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            feature_ids=[self.feature.id]
        )
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=collection,
            user=self.user,
            access_count=0
        )

        initial_count = share.access_count
        response = self.client.get(f'/api/sharing/public/collection/{share.share_id}/?bbox=-123,37,-122,38')
        self.assertEqual(response.status_code, 200)
        share.refresh_from_db()
        self.assertEqual(share.access_count, initial_count + 1)

    def test_unauthorized_access(self):
        """Test that unauthorized users cannot create shares."""
        self.client.logout()
        share_data = {
            'tag': 'shared-tag'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 401)
