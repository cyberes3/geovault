"""
Comprehensive tests for unified sharing API endpoints.
Tests creating, updating, accessing, downloading, and viewing elevations for all share types.
"""
import json
import uuid
from django.test import TestCase
from django.contrib.gis.geos import Point, LineString

from django.contrib.auth import get_user_model

from api.models import TagShare, CollectionShare, FeatureShare, Collection, FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestUnifiedShareCreation(TestCase):
    """Test creating shares for all types using unified endpoint."""

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
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['shared-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

        # Create test collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

    def test_create_tag_share_unified(self):
        """Test creating a tag share using unified endpoint."""
        share_data = {
            'share_type': 'tag',
            'tag': 'shared-tag',
            'allow_downloads': True
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertIn('url', data)
        self.assertIn('created_at', data)
        self.assertEqual(data['allow_downloads'], True)
        self.assertTrue(TagShare.objects.filter(share_id=data['share_id']).exists())

    def test_create_collection_share_unified(self):
        """Test creating a collection share using unified endpoint."""
        share_data = {
            'share_type': 'collection',
            'collection_id': str(self.collection.id),
            'include_tags': True,
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
        self.assertIn('url', data)
        self.assertEqual(data['allow_downloads'], False)
        self.assertEqual(data['include_tags'], True)
        self.assertTrue(CollectionShare.objects.filter(share_id=data['share_id']).exists())

    def test_create_feature_share_unified(self):
        """Test creating a feature share using unified endpoint."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': True
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertIn('url', data)
        self.assertEqual(data['allow_downloads'], True)
        self.assertTrue(FeatureShare.objects.filter(share_id=data['share_id']).exists())

    def test_create_feature_share_duplicate(self):
        """Test creating duplicate feature share returns existing share."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': False
        }
        # Create first share
        response1 = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        share_id1 = json.loads(response1.content)['share_id']

        # Try to create duplicate
        response2 = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response2.status_code, 200)
        share_id2 = json.loads(response2.content)['share_id']
        self.assertEqual(share_id1, share_id2)

    def test_create_share_invalid_type(self):
        """Test creating share with invalid share_type."""
        share_data = {
            'share_type': 'invalid',
            'tag': 'shared-tag'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

    def test_create_share_missing_required_fields(self):
        """Test creating share without required fields."""
        # Missing tag for tag share
        share_data = {
            'share_type': 'tag'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

        # Missing collection_id for collection share
        share_data = {
            'share_type': 'collection'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

        # Missing feature_id for feature share
        share_data = {
            'share_type': 'feature'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)


class TestShareUpdates(TestCase):
    """Test updating shares (only feature shares support updates)."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.client.force_login(self.user)

        # Create test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

    def test_update_feature_share_allow_downloads(self):
        """Test updating feature share allow_downloads setting."""
        # Create share with allow_downloads=False
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': False
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        share_id = json.loads(response.content)['share_id']

        # Update to allow downloads
        update_data = {
            'allow_downloads': True
        }
        response = self.client.patch(
            f'/api/sharing/features/{self.feature.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['allow_downloads'], True)

        # Verify in database
        share = FeatureShare.objects.get(share_id=share_id)
        self.assertTrue(share.allow_downloads)

    def test_update_feature_share_disable_downloads(self):
        """Test disabling downloads on feature share."""
        # Create share with allow_downloads=True
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': True
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )

        # Update to disable downloads
        update_data = {
            'allow_downloads': False
        }
        response = self.client.patch(
            f'/api/sharing/features/{self.feature.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['allow_downloads'], False)


class TestPublicShareAccess(TestCase):
    """Test accessing public shares for all types."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test features
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': ['public-tag']
                }
            }
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1], 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )

        # Create collection
        features = FeatureStore.objects.filter(user=self.user)
        self.collection = Collection.objects.create(
            user=self.user,
            name='Public Collection',
            feature_ids=[f.id for f in features]
        )

    def test_access_tag_share_public(self):
        """Test accessing public tag share."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='public-tag',
            user=self.user
        )

        response = self.client.get(
            f'/api/sharing/public/{share.share_id}/?bbox=-123,37,-122,38'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_access_collection_share_public(self):
        """Test accessing public collection share."""
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user
        )

        response = self.client.get(
            f'/api/sharing/public/collection/{share.share_id}/?bbox=-123,37,-122,38'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_access_feature_share_public(self):
        """Test accessing public feature share."""
        feature = FeatureStore.objects.filter(user=self.user).first()
        share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=feature,
            user=self.user
        )

        response = self.client.get(
            f'/api/sharing/public/feature/{share.share_id}/'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('type', data)
        self.assertEqual(data['type'], 'FeatureCollection')
        self.assertIn('features', data)
        self.assertEqual(len(data['features']), 1)
        self.assertIn('database_id', data['features'][0]['properties'])

    def test_get_public_share_info_all_types(self):
        """Test getting public share info for all types."""
        # Tag share
        tag_share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='public-tag',
            user=self.user
        )
        response = self.client.get(f'/api/sharing/public/info/{tag_share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'tag')
        self.assertEqual(data['tag'], 'public-tag')
        self.assertIsNotNone(data.get('feature_bbox'))
        self.assertEqual(len(data['feature_bbox']), 4)
        self.assertAlmostEqual(data['feature_bbox'][0], -122.4194, places=4)
        self.assertAlmostEqual(data['feature_bbox'][1], 37.7749, places=4)
        self.assertAlmostEqual(data['feature_bbox'][2], -122.3994, places=4)
        self.assertAlmostEqual(data['feature_bbox'][3], 37.7949, places=4)

        # Collection share
        collection_share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user
        )
        response = self.client.get(f'/api/sharing/public/info/{collection_share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'collection')
        self.assertIn('collection_name', data)
        self.assertIsNotNone(data.get('feature_bbox'))
        self.assertEqual(len(data['feature_bbox']), 4)
        self.assertAlmostEqual(data['feature_bbox'][0], -122.4194, places=4)
        self.assertAlmostEqual(data['feature_bbox'][1], 37.7749, places=4)
        self.assertAlmostEqual(data['feature_bbox'][2], -122.3994, places=4)
        self.assertAlmostEqual(data['feature_bbox'][3], 37.7949, places=4)

        # Feature share
        feature = FeatureStore.objects.filter(user=self.user).first()
        feature_share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=feature,
            user=self.user
        )
        response = self.client.get(f'/api/sharing/public/info/{feature_share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'feature')
        self.assertIn('feature_id', data)
        self.assertIn('feature_name', data)
        self.assertIsNone(data.get('feature_bbox'))

    def test_get_public_share_info_empty_collection_bbox_null(self):
        """Tag/collection shares with no matching geometries return null feature_bbox."""
        empty_collection = Collection.objects.create(
            user=self.user,
            name='Empty',
            feature_ids=[],
        )
        collection_share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=empty_collection,
            user=self.user,
        )
        response = self.client.get(f'/api/sharing/public/info/{collection_share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'collection')
        self.assertIsNone(data.get('feature_bbox'))

        orphan_tag_share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='no-such-tag-exists',
            user=self.user,
        )
        response = self.client.get(f'/api/sharing/public/info/{orphan_tag_share.share_id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'tag')
        self.assertIsNone(data.get('feature_bbox'))


class TestShareDownloads(TestCase):
    """Test downloading from shares for all types."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Downloadable Feature',
                'tags': ['download-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

        # Create collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Downloadable Collection',
            feature_ids=[self.feature.id]
        )

    def test_download_from_tag_share_allowed(self):
        """Test downloading from tag share when allowed."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='download-tag',
            user=self.user,
            allow_downloads=True
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertGreater(len(response.content), 0)

    def test_download_from_tag_share_disallowed(self):
        """Test downloading from tag share when not allowed."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='download-tag',
            user=self.user,
            allow_downloads=False
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 403)

    def test_download_from_collection_share_allowed(self):
        """Test downloading from collection share when allowed."""
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user,
            allow_downloads=True
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')

    def test_download_from_collection_share_disallowed(self):
        """Test downloading from collection share when not allowed."""
        share = CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user,
            allow_downloads=False
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 403)

    def test_download_from_feature_share_allowed(self):
        """Test downloading from feature share when allowed."""
        share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user,
            allow_downloads=True
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')

    def test_download_from_feature_share_disallowed(self):
        """Test downloading from feature share when not allowed."""
        share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user,
            allow_downloads=False
        )

        response = self.client.get(f'/api/export-kmz?share={share.share_id}')
        self.assertEqual(response.status_code, 403)


class TestFeatureShareElevations(TestCase):
    """Test elevation endpoints for feature shares."""

    def setUp(self):
        """Set up test fixtures."""
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test feature with LineString (for elevations)
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [
                    [-122.4194, 37.7749, 100.0],
                    [-122.4195, 37.7750, 150.0],
                    [-122.4196, 37.7751, 200.0]
                ]
            },
            'properties': {
                'name': 'Elevation Test Feature'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=LineString(
                (-122.4194, 37.7749, 100.0),
                (-122.4195, 37.7750, 150.0),
                (-122.4196, 37.7751, 200.0)
            ),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

        self.share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user
        )

    def test_get_feature_share_elevations_internal(self):
        """Test getting internal elevations from feature share."""
        response = self.client.get(
            f'/api/sharing/public/feature/{self.share.share_id}/elevations/internal/'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('coordinates', data)
        self.assertGreater(len(data['coordinates']), 0)
        # Check that elevations are included
        first_coord = data['coordinates'][0]
        self.assertEqual(len(first_coord), 3)  # [lon, lat, elevation]
        self.assertEqual(first_coord[2], 100.0)  # elevation

    def test_get_feature_share_elevations_invalid_share(self):
        """Test getting elevations from invalid share ID."""
        fake_share_id = str(uuid.uuid4())
        response = self.client.get(
            f'/api/sharing/public/feature/{fake_share_id}/elevations/internal/'
        )
        self.assertEqual(response.status_code, 404)

    def test_get_feature_share_elevations_point_geometry(self):
        """Test that Point geometry returns error for elevations."""
        # Create Point feature
        point_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Point Feature'
            }
        }
        point_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=point_feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(point_feature_data)
        )
        point_share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=point_feature,
            user=self.user
        )

        response = self.client.get(
            f'/api/sharing/public/feature/{point_share.share_id}/elevations/internal/'
        )
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('error', data)


class TestShareURLFormat(TestCase):
    """Test that share URLs are returned in the correct path format."""

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
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['shared-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

        # Create test collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test'],
            feature_ids=[self.feature.id]
        )

    def test_create_tag_share_url_format(self):
        """Test that creating a tag share returns URL in path format."""
        share_data = {
            'share_type': 'tag',
            'tag': 'shared-tag'
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertTrue(data['url'].startswith('/#/mapshare?id='))
        self.assertEqual(data['url'].split('id=')[1], data['share_id'])

    def test_create_collection_share_url_format(self):
        """Test that creating a collection share returns URL in path format."""
        share_data = {
            'share_type': 'collection',
            'collection_id': str(self.collection.id)
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertTrue(data['url'].startswith('/#/mapshare?id='))
        self.assertEqual(data['url'].split('id=')[1], data['share_id'])

    def test_create_feature_share_url_format(self):
        """Test that creating a feature share returns URL in path format."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id
        }
        response = self.client.post(
            '/api/sharing/create/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertTrue(data['url'].startswith('/#/mapshare?id='))
        self.assertEqual(data['url'].split('id=')[1], data['share_id'])

    def test_list_shares_url_format(self):
        """Test that listing shares returns URLs in path format."""
        # Create shares
        TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='shared-tag',
            user=self.user
        )
        CollectionShare.objects.create(
            share_id=str(uuid.uuid4()),
            collection=self.collection,
            user=self.user
        )
        FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user
        )

        response = self.client.get('/api/sharing/list/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('shares', data)
        self.assertEqual(len(data['shares']), 3)

        for share in data['shares']:
            self.assertIn('url', share)
            self.assertTrue(share['url'].startswith('/#/mapshare?id='))
            self.assertEqual(share['url'].split('id=')[1], share['share_id'])

    def test_get_feature_share_url_format(self):
        """Test that getting feature share returns URL in path format."""
        share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user
        )

        response = self.client.get(f'/api/sharing/features/{self.feature.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertTrue(data['url'].startswith('/#/mapshare?id='))
        self.assertEqual(data['url'].split('id=')[1], share.share_id)

    def test_update_feature_share_url_format(self):
        """Test that updating feature share returns URL in path format."""
        share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=self.feature,
            user=self.user
        )

        update_data = {'allow_downloads': True}
        response = self.client.patch(
            f'/api/sharing/features/{self.feature.id}/update/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertTrue(data['url'].startswith('/#/mapshare?id='))
        self.assertEqual(data['url'].split('id=')[1], share.share_id)

