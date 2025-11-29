"""
Tests for database models.
"""
import uuid
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.utils import timezone

from api.models import (
    FeatureStore, ImportQueue, Collection, TagShare, CollectionShare,
    UserSettings, DatabaseLogging
)
from users.models import ApiKey, UserProfile
from geo_lib.feature_id import generate_feature_hash

User = get_user_model()


class TestFeatureStore(TestCase):
    """Test FeatureStore model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_feature_store(self):
        """Test creating a FeatureStore instance."""
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test Feature'
            }
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            file_hash=generate_feature_hash(feature_data)
        )
        self.assertIsNotNone(feature.id)
        self.assertEqual(feature.user, self.user)
        self.assertEqual(feature.geojson['properties']['name'], 'Test Feature')

    def test_feature_store_hash_generation(self):
        """Test that file_hash is generated correctly."""
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},  # 3D coordinates
            'properties': {'name': 'Test'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            file_hash=generate_feature_hash(feature_data)
        )
        self.assertIsNotNone(feature.file_hash)
        self.assertEqual(len(feature.file_hash), 64)  # SHA-256 hex

    def test_feature_store_geometry_storage(self):
        """Test that geometry is stored correctly."""
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},  # 3D coordinates
            'properties': {'name': 'Test'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            file_hash=generate_feature_hash(feature_data)
        )
        self.assertIsNotNone(feature.geometry)
        self.assertEqual(feature.geometry.x, -122.4194)
        self.assertEqual(feature.geometry.y, 37.7749)

    def test_feature_store_timestamp(self):
        """Test that timestamp is set automatically."""
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},  # 3D coordinates
            'properties': {'name': 'Test'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),  # 3D Point with Z=0.0
            file_hash=generate_feature_hash(feature_data)
        )
        self.assertIsNotNone(feature.timestamp)


class TestImportQueue(TestCase):
    """Test ImportQueue model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_import_queue(self):
        """Test creating an ImportQueue instance."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        self.assertIsNotNone(import_queue.id)
        self.assertEqual(import_queue.user, self.user)
        self.assertFalse(import_queue.imported)
        self.assertFalse(import_queue.unparsable)

    def test_import_queue_log_id(self):
        """Test that log_id is generated."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[]
        )
        self.assertIsNotNone(import_queue.log_id)
        self.assertIsInstance(import_queue.log_id, uuid.UUID)

    def test_import_queue_duplicate_features(self):
        """Test storing duplicate features."""
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            duplicate_features=[{'id': 1, 'name': 'Duplicate'}]
        )
        self.assertEqual(len(import_queue.duplicate_features), 1)

    def test_import_queue_bulk_operations(self):
        """Test storing bulk operations."""
        bulk_ops = {
            'tags': ['test'],
            'pointColor': '#ff0000'
        }
        import_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            bulk_operations=bulk_ops
        )
        self.assertEqual(import_queue.bulk_operations, bulk_ops)


class TestCollection(TestCase):
    """Test Collection model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_collection(self):
        """Test creating a Collection instance."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            description='A test collection',
            tags=['test'],
            feature_ids=[1, 2, 3]
        )
        self.assertIsNotNone(collection.id)
        self.assertIsInstance(collection.id, uuid.UUID)
        self.assertEqual(collection.name, 'Test Collection')
        self.assertEqual(collection.tags, ['test'])
        self.assertEqual(collection.feature_ids, [1, 2, 3])

    def test_collection_timestamp(self):
        """Test that timestamps are set automatically."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=[]
        )
        self.assertIsNotNone(collection.created_at)
        self.assertIsNotNone(collection.updated_at)

    def test_collection_update_timestamp(self):
        """Test that updated_at changes on update."""
        collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=[]
        )
        original_updated = collection.updated_at
        collection.name = 'Updated Name'
        collection.save()
        self.assertGreater(collection.updated_at, original_updated)


class TestTagShare(TestCase):
    """Test TagShare model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_tag_share(self):
        """Test creating a TagShare instance."""
        share_id = str(uuid.uuid4())
        share = TagShare.objects.create(
            share_id=share_id,
            tag='test-tag',
            user=self.user,
            allow_downloads=True
        )
        self.assertEqual(share.share_id, share_id)
        self.assertEqual(share.tag, 'test-tag')
        self.assertEqual(share.user, self.user)
        self.assertTrue(share.allow_downloads)
        self.assertEqual(share.access_count, 0)

    def test_tag_share_access_count(self):
        """Test incrementing access count."""
        share = TagShare.objects.create(
            share_id=str(uuid.uuid4()),
            tag='test-tag',
            user=self.user
        )
        initial_count = share.access_count
        share.access_count += 1
        share.save()
        share.refresh_from_db()
        self.assertEqual(share.access_count, initial_count + 1)


class TestCollectionShare(TestCase):
    """Test CollectionShare model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=[]
        )

    def test_create_collection_share(self):
        """Test creating a CollectionShare instance."""
        share_id = str(uuid.uuid4())
        share = CollectionShare.objects.create(
            share_id=share_id,
            collection=self.collection,
            user=self.user,
            include_tags=True,
            allow_downloads=False
        )
        self.assertEqual(share.share_id, share_id)
        self.assertEqual(share.collection, self.collection)
        self.assertTrue(share.include_tags)
        self.assertFalse(share.allow_downloads)


class TestUserSettings(TestCase):
    """Test UserSettings model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_user_settings(self):
        """Test creating UserSettings instance."""
        settings = UserSettings.objects.create(
            user=self.user,
            settings={'map': {'elevation_profile_source': 'api'}},
            hidden_features=[1, 2, 3]
        )
        self.assertEqual(settings.user, self.user)
        self.assertEqual(settings.settings['map']['elevation_profile_source'], 'api')
        self.assertEqual(settings.hidden_features, [1, 2, 3])

    def test_user_settings_one_to_one(self):
        """Test that UserSettings has one-to-one relationship with User."""
        settings1 = UserSettings.objects.create(
            user=self.user,
            settings={},
            hidden_features=[]
        )
        # Should not be able to create another settings for same user
        with self.assertRaises(Exception):
            UserSettings.objects.create(
                user=self.user,
                settings={},
                hidden_features=[]
            )


class TestApiKey(TestCase):
    """Test ApiKey model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_api_key(self):
        """Test creating an ApiKey instance."""
        from users.api_keys import create_user_api_key
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        self.assertEqual(key_obj.user, self.user)
        self.assertEqual(key_obj.name, 'Test Key')
        self.assertTrue(key_obj.is_active)
        self.assertIsNotNone(key_obj.key_prefix)
        self.assertIsNotNone(key_obj.key_hash)
        self.assertEqual(len(key_obj.key_prefix), 8)
        self.assertEqual(len(key_obj.key_hash), 64)  # SHA-256

    def test_api_key_last_used_at(self):
        """Test updating last_used_at."""
        from users.api_keys import create_user_api_key, validate_api_key
        key_obj, raw_key = create_user_api_key(self.user, 'Test Key')
        self.assertIsNone(key_obj.last_used_at)
        
        validate_api_key(raw_key)
        key_obj.refresh_from_db()
        self.assertIsNotNone(key_obj.last_used_at)


class TestUserProfile(TestCase):
    """Test UserProfile model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_user_profile(self):
        """Test creating a UserProfile instance."""
        profile = UserProfile.objects.create(user=self.user)
        self.assertEqual(profile.user, self.user)
        self.assertIsNone(profile.last_activity)

    def test_get_or_create_profile(self):
        """Test get_or_create_profile method."""
        profile, created = UserProfile.get_or_create_profile(self.user)
        self.assertTrue(created)
        self.assertEqual(profile.user, self.user)

        # Second call should get existing profile
        profile2, created2 = UserProfile.get_or_create_profile(self.user)
        self.assertFalse(created2)
        self.assertEqual(profile.user, profile2.user)

    def test_update_activity(self):
        """Test update_activity method."""
        profile = UserProfile.objects.create(user=self.user)
        profile.update_activity()
        profile.refresh_from_db()
        self.assertIsNotNone(profile.last_activity)
        self.assertIsInstance(profile.last_activity, timezone.datetime)


class TestDatabaseLogging(TestCase):
    """Test DatabaseLogging model."""

    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_create_database_logging(self):
        """Test creating a DatabaseLogging instance."""
        import logging
        log_id = uuid.uuid4()
        log_entry = DatabaseLogging.objects.create(
            user=self.user,
            log_id=log_id,
            level=logging.INFO,
            text='Test log message',
            source='test',
            attributes={'key': 'value'},
            timestamp=timezone.now()
        )
        self.assertEqual(log_entry.user, self.user)
        self.assertEqual(log_entry.log_id, log_id)
        self.assertEqual(log_entry.level, logging.INFO)
        self.assertEqual(log_entry.text, 'Test log message')
        self.assertEqual(log_entry.source, 'test')
        self.assertEqual(log_entry.attributes, {'key': 'value'})

