"""
Comprehensive tests for unified sharing API endpoints.
Tests creating, updating, accessing, downloading, and viewing elevations for all share types.
"""
import json
import uuid
from unittest.mock import patch
from django.test import TestCase
from django.contrib.gis.geos import Point, LineString

from django.contrib.auth import get_user_model

from api.models import Collection, FeatureStore
from api.sharing.models import ShareLink
from geo_lib.feature_id import generate_geojson_hash
from test_utils.share_fixtures import (
    SHARE_TEST_BBOX,
    SHARE_TEST_LAT,
    SHARE_TEST_LON,
    create_collection_share,
    create_feature_share,
    create_owned_collection,
    create_share_feature,
    create_tag_share,
    index_feature_tags,
)


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

        self.feature = create_share_feature(self.user, name='Test Feature', tags=['shared-tag'])

        # Create test collection
        self.collection = create_owned_collection(self.user, 'Test Collection', tags=['test'], features=[self.feature])

    def test_create_tag_share_unified(self):
        """Test creating a tag share using unified endpoint."""
        share_data = {
            'share_type': 'tag',
            'tag': 'shared-tag',
            'allow_downloads': True
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertIn('url', data)
        self.assertIn('created_at', data)
        self.assertEqual(data['allow_downloads'], True)
        self.assertTrue(ShareLink.objects.filter(token=data['share_id']).exists())

    @patch("api.sharing.service.trigger_social_preview_warmup_async")
    def test_create_tag_share_triggers_preview_warmup(self, mock_warmup):
        share_data = {
            'share_type': 'tag',
            'tag': 'shared-tag',
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        created_share_id = json.loads(response.content)['share_id']
        mock_warmup.assert_called_once_with(created_share_id)

    def test_create_collection_share_unified(self):
        """Test creating a collection share using unified endpoint."""
        share_data = {
            'share_type': 'collection',
            'collection_id': str(self.collection.id),
            'include_tags': True,
            'allow_downloads': False
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertIn('url', data)
        self.assertEqual(data['allow_downloads'], False)
        self.assertEqual(data['include_tags'], True)
        self.assertTrue(ShareLink.objects.filter(token=data['share_id']).exists())

    @patch("api.sharing.service.trigger_social_preview_warmup_async")
    def test_create_collection_share_triggers_preview_warmup(self, mock_warmup):
        share_data = {
            'share_type': 'collection',
            'collection_id': str(self.collection.id),
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        created_share_id = json.loads(response.content)['share_id']
        mock_warmup.assert_called_once_with(created_share_id)

    def test_create_feature_share_unified(self):
        """Test creating a feature share using unified endpoint."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': True
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('share_id', data)
        self.assertIn('url', data)
        self.assertEqual(data['allow_downloads'], True)
        self.assertTrue(ShareLink.objects.filter(token=data['share_id']).exists())

    @patch("api.sharing.service.trigger_social_preview_warmup_async")
    def test_create_feature_share_triggers_preview_warmup(self, mock_warmup):
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        created_share_id = json.loads(response.content)['share_id']
        mock_warmup.assert_called_once_with(created_share_id)

    def test_create_feature_share_duplicate(self):
        """Test creating duplicate feature share returns existing share."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': False
        }
        # Create first share
        response1 = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        share_id1 = json.loads(response1.content)['share_id']

        # Try to create duplicate
        response2 = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response2.status_code, 200)
        share_id2 = json.loads(response2.content)['share_id']
        self.assertEqual(share_id1, share_id2)

    @patch("api.sharing.service.trigger_social_preview_warmup_async")
    def test_create_feature_share_duplicate_does_not_trigger_warmup_again(self, mock_warmup):
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': False
        }
        response1 = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(mock_warmup.call_count, 1)

        response2 = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response2.status_code, 200)
        self.assertEqual(mock_warmup.call_count, 1)

    def test_create_share_invalid_type(self):
        """Test creating share with invalid share_type."""
        share_data = {
            'share_type': 'invalid',
            'tag': 'shared-tag'
        }
        response = self.client.post(
            '/api/shares/',
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
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

        # Missing collection_id for collection share
        share_data = {
            'share_type': 'collection'
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 400)

        # Missing feature_id for feature share
        share_data = {
            'share_type': 'feature'
        }
        response = self.client.post(
            '/api/shares/',
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

        self.feature = create_share_feature(self.user, name='Test Feature')

    def test_update_feature_share_allow_downloads(self):
        """Test updating feature share allow_downloads setting."""
        # Create share with allow_downloads=False
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id,
            'allow_downloads': False
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        share_id = json.loads(response.content)['share_id']

        # Update to allow downloads
        update_data = {
            'allow_downloads': True
        }
        response = self.client.patch(
            f'/api/shares/{share_id}/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['allow_downloads'], True)

        # Verify in database
        share = ShareLink.objects.get(token=share_id)
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
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        share_id = json.loads(response.content)['share_id']

        # Update to disable downloads
        update_data = {
            'allow_downloads': False
        }
        response = self.client.patch(
            f'/api/shares/{share_id}/',
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
                    'coordinates': [SHARE_TEST_LON + i * 0.01, SHARE_TEST_LAT + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': ['public-tag']
                }
            }
            index_feature_tags(FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1], 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            ))

        # Create collection
        features = FeatureStore.objects.filter(user=self.user)
        self.collection = create_owned_collection(self.user, 'Public Collection', features=features)

    def test_access_tag_share_public(self):
        """Test accessing public tag share."""
        share = create_tag_share(self.user, 'public-tag', token=str(uuid.uuid4()))

        response = self.client.get(
            f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)
        self.assertGreater(data['feature_count'], 0)

    def test_access_collection_share_public(self):
        """Test accessing public collection share."""
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()))

        response = self.client.get(
            f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('data', data)

    def test_access_feature_share_public(self):
        """Test accessing public feature share."""
        feature = FeatureStore.objects.filter(user=self.user).first()
        share = create_feature_share(self.user, feature, token=str(uuid.uuid4()))

        response = self.client.get(
            f'/api/shares/{share.token}/features/'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('type', data)
        self.assertEqual(data['type'], 'FeatureCollection')
        self.assertIn('features', data)
        self.assertEqual(len(data['features']), 1)
        properties = data['features'][0]['properties']
        self.assertIn('feature_ref', properties)
        self.assertEqual(properties['feature_ref'], feature.id)
        self.assertNotIn('database_id', properties)
        self.assertNotIn('geojson_hash', properties)
        self.assertNotIn('geojson_hash', data['features'][0])
        self.assertNotIn('allow_downloads', data)

    def test_get_public_share_info_all_types(self):
        """Test getting public share info for all types."""
        # Tag share
        tag_share = create_tag_share(self.user, 'public-tag', token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{tag_share.token}/info/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'tag')
        self.assertEqual(data['tag'], 'public-tag')

        # Collection share
        collection_share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{collection_share.token}/info/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'collection')
        self.assertIn('collection_name', data)

        # Feature share
        feature = FeatureStore.objects.filter(user=self.user).first()
        feature_share = create_feature_share(self.user, feature, token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{feature_share.token}/info/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['share_type'], 'feature')
        self.assertNotIn('feature_id', data)
        self.assertIn('feature_name', data)


class TestPublicShareTagStripping(TestCase):
    """
    Correctness tests for unauthenticated public share access: verifies `tags` and
    `system_tags` (which can carry private info) are stripped from GeoJSON properties
    unless the sharer explicitly opted in via `include_tags`, for all 3 share types.
    Also covers access_count incrementing and invalid share_id handling, since these
    endpoints are reached with no authentication at all.
    """

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        self.feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [SHARE_TEST_LON, SHARE_TEST_LAT, 0.0]},
            'properties': {
                'name': 'Sensitive Feature',
                'tags': ['user-tag'],
                'system_tags': ['internal-system-tag'],
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(SHARE_TEST_LON, SHARE_TEST_LAT, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )
        index_feature_tags(self.feature)
        self.collection = create_owned_collection(self.user, 'Sensitive Collection', features=[self.feature])

    def _assert_tags_absent(self, properties):
        self.assertNotIn('tags', properties)
        self.assertNotIn('system_tags', properties)

    def _assert_tags_present(self, properties):
        self.assertEqual(properties.get('tags'), ['user-tag'])
        self.assertNotIn('system_tags', properties)

    # --- Tag shares ---

    def test_tag_share_strips_tags_and_system_tags_by_default(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['data']['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_absent(features[0]['properties'])

    def test_tag_share_includes_tags_when_opted_in(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()), include_tags=True)
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['data']['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_present(features[0]['properties'])

    # --- Collection shares ---

    def test_collection_share_strips_tags_and_system_tags_by_default(self):
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['data']['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_absent(features[0]['properties'])

    def test_collection_share_includes_tags_when_opted_in(self):
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()), include_tags=True)
        response = self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['data']['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_present(features[0]['properties'])

    # --- Feature shares ---

    def test_feature_share_strips_tags_and_system_tags_by_default(self):
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))
        response = self.client.get(f'/api/shares/{share.token}/features/')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_absent(features[0]['properties'])

    def test_feature_share_includes_tags_when_opted_in(self):
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()), include_tags=True)
        response = self.client.get(f'/api/shares/{share.token}/features/')
        self.assertEqual(response.status_code, 200)
        features = json.loads(response.content)['features']
        self.assertEqual(len(features), 1)
        self._assert_tags_present(features[0]['properties'])

    # --- Access count ---

    def test_tag_share_access_count_increments_on_public_access(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()))
        self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        share.refresh_from_db()
        self.assertEqual(share.access_count, 2)

    def test_collection_share_access_count_increments_on_public_access(self):
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()))
        self.client.get(f'/api/shares/{share.token}/features/?bbox={SHARE_TEST_BBOX}')
        share.refresh_from_db()
        self.assertEqual(share.access_count, 1)

    def test_feature_share_access_count_increments_on_public_access(self):
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))
        self.client.get(f'/api/shares/{share.token}/features/')
        share.refresh_from_db()
        self.assertEqual(share.access_count, 1)

    def test_public_share_info_does_not_increment_access_count(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()))
        self.client.get(f'/api/shares/{share.token}/info/')
        share.refresh_from_db()
        self.assertEqual(share.access_count, 0)

    # --- Invalid / unknown share_id (all 4 public endpoints) ---

    def test_tag_share_bbox_invalid_share_id_returns_404(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 404)

    def test_tag_share_bbox_malformed_share_id_returns_404(self):
        response = self.client.get('/api/shares/not-a-uuid/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 404)

    def test_collection_share_bbox_invalid_share_id_returns_404(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/features/?bbox={SHARE_TEST_BBOX}')
        self.assertEqual(response.status_code, 404)

    def test_feature_share_invalid_share_id_returns_404(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/features/')
        self.assertEqual(response.status_code, 404)

    def test_public_share_info_invalid_share_id_returns_404(self):
        response = self.client.get(f'/api/shares/{uuid.uuid4()}/info/')
        self.assertEqual(response.status_code, 404)


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
                'coordinates': [SHARE_TEST_LON, SHARE_TEST_LAT, 0.0]
            },
            'properties': {
                'name': 'Downloadable Feature',
                'tags': ['download-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(SHARE_TEST_LON, SHARE_TEST_LAT, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )
        index_feature_tags(self.feature)

        # Create collection
        self.collection = create_owned_collection(self.user, 'Downloadable Collection', features=[self.feature])

    def test_download_from_tag_share_allowed(self):
        """Test downloading from tag share when allowed."""
        share = create_tag_share(self.user, 'download-tag', token=str(uuid.uuid4()), allow_downloads=True)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')
        self.assertGreater(len(response.content), 0)

    def test_download_from_tag_share_disallowed(self):
        """Test downloading from tag share when not allowed."""
        share = create_tag_share(self.user, 'download-tag', token=str(uuid.uuid4()), allow_downloads=False)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 403)

    def test_download_from_collection_share_allowed(self):
        """Test downloading from collection share when allowed."""
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()), allow_downloads=True)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')

    def test_download_from_collection_share_disallowed(self):
        """Test downloading from collection share when not allowed."""
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()), allow_downloads=False)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 403)

    def test_download_from_feature_share_allowed(self):
        """Test downloading from feature share when allowed."""
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()), allow_downloads=True)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'application/vnd.google-earth.kmz')

    def test_download_from_feature_share_disallowed(self):
        """Test downloading from feature share when not allowed."""
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()), allow_downloads=False)

        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 403)


class TestShareDownloadTagStripping(TestCase):
    """
    Correctness tests for the KMZ download path (`/api/export-kmz?share=...`), a second
    unauthenticated share touchpoint distinct from the map-view GeoJSON endpoints.
    `_apply_properties_to_placemark` writes `tags`/`system_tags` straight into the KML
    placemark description, so these must be stripped upstream before KMZ generation
    unless the sharer opted in via `include_tags` - mirroring the map view's behavior.
    """

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # 'user-tag' is the tag being shared, so it's inherently disclosed by the share
        # link itself (and becomes the KMZ document name) - the actual thing under test
        # is whether 'extra-secret-tag'/'internal-system-tag' leak into the placemark.
        self.feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [SHARE_TEST_LON, SHARE_TEST_LAT, 0.0]},
            'properties': {
                'name': 'Downloadable Sensitive Feature',
                'tags': ['user-tag', 'extra-secret-tag'],
                'system_tags': ['internal-system-tag'],
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(SHARE_TEST_LON, SHARE_TEST_LAT, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )
        index_feature_tags(self.feature)
        self.collection = create_owned_collection(self.user, 'Downloadable Sensitive Collection', features=[self.feature])

    @staticmethod
    def _extract_kml_text(kmz_bytes):
        import zipfile
        from io import BytesIO
        with zipfile.ZipFile(BytesIO(kmz_bytes)) as zf:
            kml_name = next(n for n in zf.namelist() if n.endswith('.kml'))
            return zf.read(kml_name).decode('utf-8')

    def test_tag_share_bulk_download_strips_tags_by_default(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()), allow_downloads=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        # 'user-tag' itself legitimately appears as the KMZ document name (it's the
        # share's own tag, already disclosed by the link); the other tags must not leak.
        self.assertNotIn('extra-secret-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_tag_share_bulk_download_includes_tags_when_opted_in(self):
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()), allow_downloads=True, include_tags=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertIn('extra-secret-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_collection_share_bulk_download_strips_tags_by_default(self):
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()), allow_downloads=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertNotIn('user-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_collection_share_bulk_download_includes_tags_when_opted_in(self):
        share = create_collection_share(self.user, self.collection, token=str(uuid.uuid4()), allow_downloads=True, include_tags=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertIn('user-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_feature_share_single_download_strips_tags_by_default(self):
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()), allow_downloads=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertNotIn('user-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_feature_share_single_download_includes_tags_when_opted_in(self):
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()), allow_downloads=True, include_tags=True)
        response = self.client.get(f'/api/export-kmz?share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertIn('user-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_single_feature_download_via_tag_share_strips_tags_by_default(self):
        """A specific `?feature=<id>&share=<tag_share_id>` request (single feature within
        a bulk tag share) goes through a different code path than the bulk download."""
        share = create_tag_share(self.user, 'user-tag', token=str(uuid.uuid4()), allow_downloads=True)
        response = self.client.get(f'/api/export-kmz?feature={self.feature.id}&share={share.token}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertNotIn('extra-secret-tag', kml_text)
        self.assertNotIn('internal-system-tag', kml_text)

    def test_authenticated_owner_download_still_includes_tags(self):
        """Regression check: a user downloading their own feature (no share involved)
        should still see their own tags - only public share downloads are stripped."""
        self.client.force_login(self.user)
        response = self.client.get(f'/api/export-kmz?feature={self.feature.id}')
        self.assertEqual(response.status_code, 200)
        kml_text = self._extract_kml_text(response.content)
        self.assertIn('user-tag', kml_text)
        self.assertIn('internal-system-tag', kml_text)


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
                    [SHARE_TEST_LON, SHARE_TEST_LAT, 100.0],
                    [SHARE_TEST_LON + 0.01, SHARE_TEST_LAT + 0.01, 150.0],
                    [SHARE_TEST_LON + 0.02, SHARE_TEST_LAT + 0.02, 200.0]
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
                (SHARE_TEST_LON, SHARE_TEST_LAT, 100.0),
                (SHARE_TEST_LON + 0.01, SHARE_TEST_LAT + 0.01, 150.0),
                (SHARE_TEST_LON + 0.02, SHARE_TEST_LAT + 0.02, 200.0)
            ),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )

        self.share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))

    def test_get_feature_share_elevations_internal(self):
        """Test getting internal elevations from feature share."""
        response = self.client.get(
            f'/api/shares/{self.share.token}/elevations/'
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
            f'/api/shares/{fake_share_id}/elevations/'
        )
        self.assertEqual(response.status_code, 404)

    def test_get_feature_share_elevations_point_geometry(self):
        """Test that Point geometry returns error for elevations."""
        # Create Point feature
        point_feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [SHARE_TEST_LON, SHARE_TEST_LAT, 0.0]
            },
            'properties': {
                'name': 'Point Feature'
            }
        }
        point_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=point_feature_data,
            geometry=Point(SHARE_TEST_LON, SHARE_TEST_LAT, 0.0),
            geojson_hash=generate_geojson_hash(point_feature_data)
        )
        point_share = create_feature_share(self.user, point_feature, token=str(uuid.uuid4()))

        response = self.client.get(
            f'/api/shares/{point_share.token}/elevations/'
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
                'coordinates': [SHARE_TEST_LON, SHARE_TEST_LAT, 0.0]
            },
            'properties': {
                'name': 'Test Feature',
                'tags': ['shared-tag']
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(SHARE_TEST_LON, SHARE_TEST_LAT, 0.0),
            geojson_hash=generate_geojson_hash(self.feature_data)
        )
        index_feature_tags(self.feature)

        # Create test collection
        self.collection = create_owned_collection(self.user, 'Test Collection', tags=['test'], features=[self.feature])

    def test_create_tag_share_url_format(self):
        """Test that creating a tag share returns URL in path format."""
        share_data = {
            'share_type': 'tag',
            'tag': 'shared-tag'
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertEqual(data['url'], f"/share/map/{data['share_id']}/")

    def test_create_collection_share_url_format(self):
        """Test that creating a collection share returns URL in path format."""
        share_data = {
            'share_type': 'collection',
            'collection_id': str(self.collection.id)
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertEqual(data['url'], f"/share/map/{data['share_id']}/")

    def test_create_feature_share_url_format(self):
        """Test that creating a feature share returns URL in path format."""
        share_data = {
            'share_type': 'feature',
            'feature_id': self.feature.id
        }
        response = self.client.post(
            '/api/shares/',
            data=json.dumps(share_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertEqual(data['url'], f"/share/map/{data['share_id']}/")

    def test_list_shares_url_format(self):
        """Test that listing shares returns URLs in path format."""
        # Create shares
        create_tag_share(self.user, 'shared-tag', token=str(uuid.uuid4()))
        create_collection_share(self.user, self.collection, token=str(uuid.uuid4()))
        create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))

        response = self.client.get('/api/shares/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('items', data)
        self.assertEqual(len(data['items']), 3)

        for share in data['items']:
            self.assertIn('url', share)
            self.assertEqual(share['url'], f"/share/map/{share['share_id']}/")

    def test_get_feature_share_url_format(self):
        """Test that getting feature share returns URL in path format."""
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))

        response = self.client.get(f'/api/shares/?feature_id={self.feature.id}')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)['items'][0]
        self.assertIn('url', data)
        self.assertEqual(data['url'], f"/share/map/{share.token}/")

    def test_update_feature_share_url_format(self):
        """Test that updating feature share returns URL in path format."""
        share = create_feature_share(self.user, self.feature, token=str(uuid.uuid4()))

        update_data = {'allow_downloads': True}
        response = self.client.patch(
            f'/api/shares/{share.token}/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIn('url', data)
        self.assertEqual(data['url'], f"/share/map/{share.token}/")

