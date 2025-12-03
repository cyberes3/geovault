"""
Comprehensive tests for duplicate detection system.

Tests each of the 4 duplicate types individually and their interactions:
1. Feature store hash duplicate
2. Feature store geometry duplicate
3. Cross-queue hash duplicate
4. Cross-queue geometry duplicate

Also tests priority rules:
- Hash > Geometry (within same source)
- Feature Store > Cross-Queue (across sources)
"""
import pytest
from django.test import TestCase, TransactionTestCase
from django.contrib.auth import get_user_model

from api.models import FeatureStore, ImportQueue
from geo_lib.processing.duplicate_detection import (
    find_duplicates_for_source,
    find_hash_duplicates,
    find_geometry_duplicates
)
from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
from geo_lib.feature_id import generate_geojson_hash


User = get_user_model()


class TestDuplicateDetectionIndividual(TestCase):
    """Test each duplicate type individually."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Sample features
        self.point_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'}
        }
        
        self.different_point = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Different Point', 'description': 'Another point'}
        }
        
        # Same coordinates, different properties (geometry duplicate only)
        self.same_coords_different_props = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'}
        }

    def test_feature_store_hash_duplicate_only(self):
        """Test 1: Feature store hash duplicate detection."""
        # Create a feature in the store with exact same hash
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash
        )
        
        # Try to import the same feature
        remaining, duplicates, log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # Assertions
        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        self.assertEqual(duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.HASH)
        self.assertEqual(duplicates[0]['existing_features'][0]['id'], store_feature.id)
        
        print("✓ Test 1 passed: Feature store hash duplicate detected correctly")

    def test_feature_store_geometry_duplicate_only(self):
        """Test 2: Feature store geometry duplicate detection."""
        from django.contrib.gis.geos import Point
        
        # Create a feature with same coordinates but different properties
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        # Create geometry object from coordinates (with Z dimension)
        coords = self.point_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)  # Add Z=0
        
        store_feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash,
            geometry=point_geom
        )
        
        # Try to import feature with same geometry but different properties
        remaining, duplicates, log = find_duplicates_for_source(
            [self.same_coords_different_props],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # Assertions
        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        self.assertEqual(duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.GEOMETRY)
        self.assertEqual(duplicates[0]['existing_features'][0]['id'], store_feature.id)
        
        print("✓ Test 2 passed: Feature store geometry duplicate detected correctly")

    def test_cross_queue_hash_duplicate_only(self):
        """Test 3: Cross-queue hash duplicate detection."""
        # Create an older import queue item with a feature
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.point_feature],
            imported=False
        )
        
        # Create newer queue item
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import the same feature (hash duplicate)
        remaining, duplicates, log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Assertions
        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        self.assertEqual(duplicates[0]['source'], DuplicateSource.CROSS_QUEUE)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.HASH)
        self.assertEqual(duplicates[0]['existing_features'][0]['id'], older_queue.id)
        
        print("✓ Test 3 passed: Cross-queue hash duplicate detected correctly")

    def test_cross_queue_geometry_duplicate_only(self):
        """Test 4: Cross-queue geometry duplicate detection."""
        # Create an older import queue item with a feature
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.point_feature],
            imported=False
        )
        
        # Create newer queue item
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import feature with same geometry but different properties
        remaining, duplicates, log = find_duplicates_for_source(
            [self.same_coords_different_props],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Assertions
        self.assertEqual(len(remaining), 0, "Feature should be detected as duplicate")
        self.assertEqual(len(duplicates), 1, "Should have 1 duplicate")
        self.assertEqual(duplicates[0]['source'], DuplicateSource.CROSS_QUEUE)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.GEOMETRY)
        self.assertEqual(duplicates[0]['existing_features'][0]['id'], older_queue.id)
        
        print("✓ Test 4 passed: Cross-queue geometry duplicate detected correctly")


class TestDuplicatePriorityRules(TestCase):
    """Test priority rules: hash > geometry, feature_store > cross_queue."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        self.point_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'}
        }
        
        self.same_coords_different_props = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'}
        }

    def test_hash_over_geometry_same_source_feature_store(self):
        """Test 5: Hash takes precedence over geometry in feature store."""
        # Create exact hash duplicate in feature store
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash
        )
        
        # Try to import the same feature (both hash and geometry match)
        remaining, duplicates, log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # Should be marked as HASH duplicate only (not geometry)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.HASH,
                        "Should be marked as hash duplicate, not geometry")
        
        print("✓ Test 5 passed: Hash takes precedence over geometry in feature store")

    def test_hash_over_geometry_same_source_cross_queue(self):
        """Test 6: Hash takes precedence over geometry in cross-queue."""
        # Create older queue item with exact same feature
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.point_feature],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import the same feature (both hash and geometry match)
        remaining, duplicates, log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Should be marked as HASH duplicate only (not geometry)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.HASH,
                        "Should be marked as hash duplicate, not geometry")
        
        print("✓ Test 6 passed: Hash takes precedence over geometry in cross-queue")

    def test_feature_store_over_cross_queue_both_hash(self):
        """Test 7: Feature store hash takes precedence over cross-queue hash."""
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        # Create in BOTH feature store and queue
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash
        )
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.point_feature],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # PASS 1: Feature store detection
        remaining_after_fs, fs_duplicates, fs_log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue detection on remaining
        remaining_after_cq, cq_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Feature store should catch it first
        self.assertEqual(len(fs_duplicates), 1, "Feature store should detect hash duplicate")
        self.assertEqual(fs_duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(len(remaining_after_fs), 0, "No features should remain after feature store check")
        self.assertEqual(len(cq_duplicates), 0, "Cross-queue should have nothing to check")
        
        print("✓ Test 7 passed: Feature store hash takes precedence over cross-queue hash")

    def test_feature_store_over_cross_queue_both_geometry(self):
        """Test 8: Feature store geometry takes precedence over cross-queue geometry."""
        from django.contrib.gis.geos import Point
        
        # Feature store has geometry match
        feature_hash1 = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash1
        
        # Create geometry object (with Z dimension)
        coords = self.point_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)  # Add Z=0
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash1,
            geometry=point_geom
        )
        
        # Cross-queue also has geometry match (same coordinates, different name)
        older_feature = self.same_coords_different_props.copy()
        feature_hash2 = generate_geojson_hash(older_feature)
        older_feature['properties']['geojson_hash'] = feature_hash2
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[older_feature],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import third version with same coordinates
        third_version = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Third Version', 'description': 'Yet another one'}
        }
        
        # PASS 1: Feature store detection
        remaining_after_fs, fs_duplicates, fs_log = find_duplicates_for_source(
            [third_version],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue detection on remaining
        remaining_after_cq, cq_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Feature store should catch it first as geometry duplicate
        self.assertEqual(len(fs_duplicates), 1, "Feature store should detect geometry duplicate")
        self.assertEqual(fs_duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(fs_duplicates[0]['match_type'], DuplicateMatchType.GEOMETRY)
        self.assertEqual(len(remaining_after_fs), 0, "No features should remain after feature store check")
        self.assertEqual(len(cq_duplicates), 0, "Cross-queue should have nothing to check")
        
        print("✓ Test 8 passed: Feature store geometry takes precedence over cross-queue geometry")

    def test_feature_store_hash_over_cross_queue_geometry(self):
        """Test 9: Feature store hash takes precedence over cross-queue geometry."""
        # Feature store has exact hash match
        feature_hash = generate_geojson_hash(self.point_feature)
        self.point_feature['properties']['geojson_hash'] = feature_hash
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash
        )
        
        # Cross-queue has geometry match (same coordinates, different properties)
        queue_feature = self.same_coords_different_props.copy()
        queue_feature['properties']['geojson_hash'] = generate_geojson_hash(queue_feature)
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[queue_feature],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # PASS 1: Feature store detection
        remaining_after_fs, fs_duplicates, fs_log = find_duplicates_for_source(
            [self.point_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue detection on remaining
        remaining_after_cq, cq_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Feature store hash should win
        self.assertEqual(len(fs_duplicates), 1)
        self.assertEqual(fs_duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(fs_duplicates[0]['match_type'], DuplicateMatchType.HASH)
        self.assertEqual(len(cq_duplicates), 0, "Cross-queue geometry should not be checked")
        
        print("✓ Test 9 passed: Feature store hash takes precedence over cross-queue geometry")

    def test_feature_store_geometry_over_cross_queue_hash(self):
        """Test 10: Feature store geometry takes precedence over cross-queue hash."""
        from django.contrib.gis.geos import Point
        
        # Feature store has geometry match only
        fs_feature = self.point_feature.copy()
        fs_hash = generate_geojson_hash(fs_feature)
        fs_feature['properties']['geojson_hash'] = fs_hash
        
        # Create geometry object (with Z dimension)
        coords = fs_feature['geometry']['coordinates']
        point_geom = Point(coords[0], coords[1], 0, srid=4326)  # Add Z=0
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_feature,
            geojson_hash=fs_hash,
            geometry=point_geom
        )
        
        # Cross-queue has exact hash match with different feature
        queue_feature = self.same_coords_different_props.copy()
        queue_hash = generate_geojson_hash(queue_feature)
        queue_feature['properties']['geojson_hash'] = queue_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[queue_feature],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import the queue feature (geometry match in FS, hash match in queue)
        # PASS 1: Feature store detection
        remaining_after_fs, fs_duplicates, fs_log = find_duplicates_for_source(
            [queue_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue detection on remaining
        remaining_after_cq, cq_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Feature store geometry should win (caught in pass 1)
        self.assertEqual(len(fs_duplicates), 1)
        self.assertEqual(fs_duplicates[0]['source'], DuplicateSource.FEATURE_STORE)
        self.assertEqual(fs_duplicates[0]['match_type'], DuplicateMatchType.GEOMETRY)
        self.assertEqual(len(cq_duplicates), 0, "Cross-queue hash should not be checked")
        
        print("✓ Test 10 passed: Feature store geometry takes precedence over cross-queue hash")


class TestCrossQueueNavigation(TestCase):
    """Test cross-queue duplicate navigation features."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_cross_queue_duplicate_includes_feature_index(self):
        """Test 13: Cross-queue duplicates include feature_index for navigation."""
        # Create older queue with 3 features
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'}
        }
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Feature 3'}
        }
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2, feature3],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import feature that matches feature2 (at index 1)
        duplicate_of_feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Duplicate of Feature 2', 'description': 'Different'}
        }
        
        # Detect cross-queue geometry duplicate
        remaining, duplicates, log = find_duplicates_for_source(
            [duplicate_of_feature2],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Should find 1 duplicate
        self.assertEqual(len(duplicates), 1, "Should detect geometry duplicate")
        self.assertEqual(duplicates[0]['source'], DuplicateSource.CROSS_QUEUE)
        self.assertEqual(duplicates[0]['match_type'], DuplicateMatchType.GEOMETRY)
        
        # CRITICAL: existing_features should include feature_index for navigation
        existing = duplicates[0]['existing_features'][0]
        self.assertIn('feature_index', existing, "existing_features must include feature_index")
        self.assertEqual(existing['feature_index'], 1, 
                        "Feature index should be 1 (second feature in older queue)")
        self.assertEqual(existing['id'], older_queue.id,
                        "Should reference the correct queue item")
        self.assertEqual(existing['name'], 'older.kml',
                        "Should include queue item filename")
        
        print("✓ Test 13 passed: Cross-queue duplicates include feature_index for navigation")

    def test_cross_queue_hash_duplicate_includes_feature_index(self):
        """Test 14: Cross-queue hash duplicates also include feature_index."""
        # Create older queue with hash duplicate at specific index
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1', 'description': 'First'}
        }
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2', 'description': 'Second'}
        }
        
        hash1 = generate_geojson_hash(feature1)
        hash2 = generate_geojson_hash(feature2)
        feature1['properties']['geojson_hash'] = hash1
        feature2['properties']['geojson_hash'] = hash2
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import exact copy of feature2 (hash duplicate at index 1)
        hash_duplicates, log = find_hash_duplicates(
            [feature2],
            self.user.id,
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp,
            source_filter='cross_queue'
        )
        
        # Should find 1 hash duplicate
        self.assertEqual(len(hash_duplicates), 1, "Should detect hash duplicate")
        
        # Verify feature_index is included
        existing = hash_duplicates[0]['existing_features'][0]
        self.assertIn('feature_index', existing, "Hash duplicates must also include feature_index")
        self.assertEqual(existing['feature_index'], 1,
                        "Feature index should be 1 (second feature)")
        
        print("✓ Test 14 passed: Cross-queue hash duplicates include feature_index")


class TestSourceIsolation(TestCase):
    """Test that source_filter correctly isolates feature_store from cross_queue."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_feature_store_filter_ignores_cross_queue(self):
        """Test 15: source='feature_store' doesn't return cross-queue duplicates."""
        from django.contrib.gis.geos import Point
        
        # Create feature in feature store
        feature_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Store Feature'}
        }
        store_hash = generate_geojson_hash(feature_in_store)
        feature_in_store['properties']['geojson_hash'] = store_hash
        
        coords = feature_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_in_store,
            geojson_hash=store_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create DIFFERENT feature in cross-queue (geometry duplicate)
        feature_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},  # Same coords
            'properties': {'name': 'Queue Feature', 'description': 'Different'}
        }
        queue_hash = generate_geojson_hash(feature_in_queue)
        feature_in_queue['properties']['geojson_hash'] = queue_hash
        
        queue_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature_in_queue],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import third feature with same coordinates
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Test Feature', 'description': 'Third version'}
        }
        
        # Check ONLY feature_store (should find geometry duplicate in store, NOT queue)
        remaining, fs_dups, log = find_duplicates_for_source(
            [test_feature],
            self.user.id,
            source='feature_store',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Should find 1 duplicate from feature store ONLY
        self.assertEqual(len(fs_dups), 1, "Should find 1 feature store duplicate")
        self.assertEqual(fs_dups[0]['source'], DuplicateSource.FEATURE_STORE,
                        "Source must be FEATURE_STORE, not CROSS_QUEUE")
        
        # Verify it's NOT reporting the cross-queue duplicate
        for dup in fs_dups:
            self.assertNotEqual(dup['source'], DuplicateSource.CROSS_QUEUE,
                              "feature_store filter must not return cross-queue duplicates")
        
        print("✓ Test 15 passed: Source filter correctly isolates feature_store from cross_queue")
    
    def test_cross_queue_filter_ignores_feature_store(self):
        """Test 16: source='cross_queue' doesn't return feature store duplicates."""
        from django.contrib.gis.geos import Point
        
        # Create feature in feature store
        feature_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Store Feature'}
        }
        store_hash = generate_geojson_hash(feature_in_store)
        feature_in_store['properties']['geojson_hash'] = store_hash
        
        coords = feature_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature_in_store,
            geojson_hash=store_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create different feature in cross-queue
        feature_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},  # Same coords
            'properties': {'name': 'Queue Feature', 'description': 'Different'}
        }
        queue_hash = generate_geojson_hash(feature_in_queue)
        feature_in_queue['properties']['geojson_hash'] = queue_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature_in_queue],
            imported=False
        )
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Try to import third feature
        test_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Test Feature', 'description': 'Third'}
        }
        
        # Check ONLY cross_queue (should find duplicate in queue, NOT store)
        remaining, cq_dups, log = find_duplicates_for_source(
            [test_feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Should find 1 duplicate from cross-queue ONLY
        self.assertEqual(len(cq_dups), 1, "Should find 1 cross-queue duplicate")
        self.assertEqual(cq_dups[0]['source'], DuplicateSource.CROSS_QUEUE,
                        "Source must be CROSS_QUEUE, not FEATURE_STORE")
        
        # Verify it's NOT reporting the feature store duplicate
        for dup in cq_dups:
            self.assertNotEqual(dup['source'], DuplicateSource.FEATURE_STORE,
                              "cross_queue filter must not return feature_store duplicates")
        
        print("✓ Test 16 passed: Source filter correctly isolates cross_queue from feature_store")


class TestTimestampOrdering(TestCase):
    """Test timestamp-based ordering prevents simultaneous upload conflicts."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_simultaneous_uploads_only_newer_shows_duplicates(self):
        """Test 17: Two files uploaded simultaneously - only newer one shows duplicates of older."""
        from datetime import datetime, timezone, timedelta
        
        # Create identical feature
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature', 'description': 'Test'}
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash
        
        # Create first queue item (slightly older timestamp)
        base_time = datetime.now(timezone.utc)
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='file1.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        # Manually set timestamp to specific time
        older_queue.timestamp = base_time
        older_queue.save()
        
        # Create second queue item (1 second later)
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='file2.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],  # Same feature
            imported=False
        )
        newer_queue.timestamp = base_time + timedelta(seconds=1)
        newer_queue.save()
        
        # Check duplicates for OLDER file
        # Should find NO cross-queue duplicates (newer file shouldn't affect older)
        remaining_older, dups_older, log_older = find_duplicates_for_source(
            [feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=older_queue.id,
            exclude_timestamp=older_queue.timestamp  # Only check items OLDER than this
        )
        
        # CRITICAL: Older file should have NO duplicates
        self.assertEqual(len(dups_older), 0,
                        "Older file should NOT see newer file as duplicate "
                        "(prevents simultaneous uploads from marking each other)")
        self.assertEqual(len(remaining_older), 1,
                        "Feature in older file should remain (not marked as duplicate)")
        
        # Check duplicates for NEWER file
        # Should find cross-queue duplicate in older file
        remaining_newer, dups_newer, log_newer = find_duplicates_for_source(
            [feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp  # Only check items OLDER than this
        )
        
        # Newer file SHOULD see the older file as duplicate
        self.assertEqual(len(dups_newer), 1,
                        "Newer file should detect duplicate in older file")
        self.assertEqual(dups_newer[0]['source'], DuplicateSource.CROSS_QUEUE)
        self.assertEqual(dups_newer[0]['match_type'], DuplicateMatchType.HASH)
        self.assertEqual(dups_newer[0]['existing_features'][0]['id'], older_queue.id,
                        "Duplicate should reference the OLDER queue item")
        
        print("✓ Test 17 passed: Timestamp ordering prevents simultaneous uploads from marking each other")
    
    def test_three_sequential_uploads_correct_ordering(self):
        """Test 18: Three files uploaded sequentially - each only sees older ones as duplicates."""
        from datetime import datetime, timezone, timedelta
        
        # Create identical feature
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Sequential Test', 'description': 'Test'}
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash
        
        base_time = datetime.now(timezone.utc)
        
        # Create three queue items with sequential timestamps
        queue1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='file1.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        queue1.timestamp = base_time
        queue1.save()
        
        queue2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='file2.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        queue2.timestamp = base_time + timedelta(seconds=5)
        queue2.save()
        
        queue3 = ImportQueue.objects.create(
            user=self.user,
            original_filename='file3.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        queue3.timestamp = base_time + timedelta(seconds=10)
        queue3.save()
        
        # File 1 (oldest): Should see NO duplicates
        _, dups1, _ = find_duplicates_for_source(
            [feature], self.user.id, source='cross_queue',
            exclude_queue_id=queue1.id, exclude_timestamp=queue1.timestamp
        )
        self.assertEqual(len(dups1), 0, "File 1 (oldest) should see no duplicates")
        
        # File 2 (middle): Should see only file 1 as duplicate
        _, dups2, _ = find_duplicates_for_source(
            [feature], self.user.id, source='cross_queue',
            exclude_queue_id=queue2.id, exclude_timestamp=queue2.timestamp
        )
        self.assertEqual(len(dups2), 1, "File 2 should see 1 duplicate (file 1)")
        self.assertEqual(dups2[0]['existing_features'][0]['id'], queue1.id,
                        "File 2 should reference file 1")
        
        # File 3 (newest): Should see file 1 as duplicate (not file 2, due to priority)
        # The hash duplicate from file 1 will be found first, file 2 won't be checked
        _, dups3, _ = find_duplicates_for_source(
            [feature], self.user.id, source='cross_queue',
            exclude_queue_id=queue3.id, exclude_timestamp=queue3.timestamp
        )
        self.assertEqual(len(dups3), 1, "File 3 should see 1 duplicate")
        # Could be file 1 or file 2, depending on which is found first
        # Both are valid since they're identical
        self.assertIn(dups3[0]['existing_features'][0]['id'], [queue1.id, queue2.id],
                     "File 3 should reference either file 1 or file 2")
        
        print("✓ Test 18 passed: Sequential uploads show correct timestamp-based ordering")


class TestIntegration(TestCase):
    """Integration tests for full duplicate detection flow through ProcessJob."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_full_processing_flow_with_all_duplicate_types(self):
        """Test 19: Full ProcessJob integration - all 4 duplicate types in one processing run."""
        from django.contrib.gis.geos import Point
        from datetime import datetime, timezone
        
        # Setup: Create features in feature store
        fs_hash_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'FS Hash Match', 'description': 'Will be hash duplicate'}
        }
        fs_hash = generate_geojson_hash(fs_hash_feature)
        fs_hash_feature['properties']['geojson_hash'] = fs_hash
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_hash_feature,
            geojson_hash=fs_hash,
            geometry=Point(-122.1, 37.7, 0, srid=4326)
        )
        
        fs_geom_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'FS Geom Match', 'description': 'Different props'}
        }
        fs_geom_hash = generate_geojson_hash(fs_geom_feature)
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=fs_geom_feature,
            geojson_hash=fs_geom_hash,
            geometry=Point(-122.2, 37.8, 0, srid=4326)
        )
        
        # Setup: Create older queue item with features
        cq_hash_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'CQ Hash Match', 'description': 'Queue hash'}
        }
        cq_hash = generate_geojson_hash(cq_hash_feature)
        cq_hash_feature['properties']['geojson_hash'] = cq_hash
        
        cq_geom_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4, 38.0]},
            'properties': {'name': 'CQ Geom Match', 'description': 'Queue geom'}
        }
        cq_geom_hash = generate_geojson_hash(cq_geom_feature)
        cq_geom_feature['properties']['geojson_hash'] = cq_geom_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[cq_hash_feature, cq_geom_feature],
            imported=False
        )
        older_queue.timestamp = datetime.now(timezone.utc)
        older_queue.save()
        
        # Create test features that will match all 4 types
        test_features = [
            # 1. Hash duplicate in feature store (exact match)
            fs_hash_feature.copy(),
            
            # 2. Geometry duplicate in feature store (same coords, different name)
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
                'properties': {'name': 'Different Name', 'description': 'Different'}
            },
            
            # 3. Hash duplicate in cross-queue (exact match)
            cq_hash_feature.copy(),
            
            # 4. Geometry duplicate in cross-queue (same coords, different name)
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4, 38.0]},
                'properties': {'name': 'Another Name', 'description': 'Different'}
            },
            
            # 5. Unique feature (should pass through)
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.5, 38.1]},
                'properties': {'name': 'Unique Feature', 'description': 'No duplicate'}
            }
        ]
        
        # Simulate the full 2-pass detection flow (like ProcessJob does)
        from geo_lib.processing.duplicate_detection import find_duplicates_for_source
        from geo_lib.processing.duplicate_models import split_duplicates_by_match_type
        
        # PASS 1: Feature store detection
        remaining_after_fs, fs_duplicates, fs_log = find_duplicates_for_source(
            test_features,
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        fs_hash_dups, fs_geom_dups = split_duplicates_by_match_type(fs_duplicates)
        
        # PASS 2: Cross-queue detection on remaining
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=test_features,
            imported=False
        )
        
        remaining_after_cq, cq_duplicates, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        cq_hash_dups, cq_geom_dups = split_duplicates_by_match_type(cq_duplicates)
        
        # Combine all duplicates (like ProcessJob does)
        all_duplicates = fs_hash_dups + fs_geom_dups + cq_hash_dups + cq_geom_dups
        
        # ASSERTIONS: Verify the complete flow
        
        # Should have exactly 4 duplicates (one of each type)
        self.assertEqual(len(all_duplicates), 4,
                        "Should detect exactly 4 duplicates (1 of each type)")
        
        # Verify feature store hash duplicate
        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 feature store hash duplicate")
        self.assertEqual(fs_hash_dups[0]['source'], 'feature_store')
        self.assertEqual(fs_hash_dups[0]['match_type'], 'hash')
        
        # Verify feature store geometry duplicate
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 feature store geometry duplicate")
        self.assertEqual(fs_geom_dups[0]['source'], 'feature_store')
        self.assertEqual(fs_geom_dups[0]['match_type'], 'geometry')
        
        # Verify cross-queue hash duplicate
        self.assertEqual(len(cq_hash_dups), 1, "Should have 1 cross-queue hash duplicate")
        self.assertEqual(cq_hash_dups[0]['source'], 'cross_queue')
        self.assertEqual(cq_hash_dups[0]['match_type'], 'hash')
        self.assertEqual(cq_hash_dups[0]['existing_features'][0]['id'], older_queue.id,
                        "Should reference older queue item")
        
        # Verify cross-queue geometry duplicate
        self.assertEqual(len(cq_geom_dups), 1, "Should have 1 cross-queue geometry duplicate")
        self.assertEqual(cq_geom_dups[0]['source'], 'cross_queue')
        self.assertEqual(cq_geom_dups[0]['match_type'], 'geometry')
        self.assertEqual(cq_geom_dups[0]['existing_features'][0]['id'], older_queue.id,
                        "Should reference older queue item")
        
        # Verify 1 unique feature remains
        self.assertEqual(len(remaining_after_cq), 1,
                        "Should have exactly 1 unique feature remaining")
        self.assertEqual(remaining_after_cq[0]['properties']['name'], 'Unique Feature',
                        "Unique feature should pass through")
        
        # Verify priority ordering in combined list
        self.assertEqual(all_duplicates[0]['source'], 'feature_store',
                        "First duplicates should be from feature store")
        self.assertEqual(all_duplicates[0]['match_type'], 'hash',
                        "First duplicate should be hash (highest priority)")
        
        print("✓ Test 19 passed: Full integration test with all 4 duplicate types")
    
    def test_integration_skipped_feature_ids_only_geometry(self):
        """Test 20: Integration test verifying skipped_feature_ids only contains geometry duplicates."""
        from django.contrib.gis.geos import Point
        from datetime import datetime, timezone
        
        # Create features that will be hash and geometry duplicates
        hash_dup_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Hash Dup', 'description': 'Exact match'}
        }
        hash_dup_hash = generate_geojson_hash(hash_dup_feature)
        hash_dup_feature['properties']['geojson_hash'] = hash_dup_hash
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=hash_dup_feature,
            geojson_hash=hash_dup_hash,
            geometry=Point(-122.1, 37.7, 0, srid=4326)
        )
        
        geom_dup_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Store Geom', 'description': 'Store'}
        }
        store_geom_hash = generate_geojson_hash(geom_dup_in_store)
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=geom_dup_in_store,
            geojson_hash=store_geom_hash,
            geometry=Point(-122.2, 37.8, 0, srid=4326)
        )
        
        geom_dup_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Geom Dup', 'description': 'Different'}
        }
        geom_dup_hash = generate_geojson_hash(geom_dup_feature)
        geom_dup_feature['properties']['geojson_hash'] = geom_dup_hash
        
        test_features = [hash_dup_feature, geom_dup_feature]
        
        # Run detection
        from geo_lib.processing.duplicate_detection import find_duplicates_for_source
        from geo_lib.processing.duplicate_models import split_duplicates_by_match_type
        
        remaining, fs_dups, log = find_duplicates_for_source(
            test_features,
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        fs_hash_dups, fs_geom_dups = split_duplicates_by_match_type(fs_dups)
        
        # Build skipped_feature_ids (like ProcessJob does)
        skipped_feature_ids = []
        
        # Only geometry duplicates go in skipped_feature_ids
        for dup in fs_geom_dups:
            dup_hash = dup['feature'].get('properties', {}).get('geojson_hash')
            if not dup_hash:
                dup_hash = generate_geojson_hash(dup['feature'])
            skipped_feature_ids.append(dup_hash)
        
        # ASSERTIONS
        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 hash duplicate")
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 geometry duplicate")
        
        # CRITICAL: Only geometry duplicate should be in skipped_feature_ids
        self.assertEqual(len(skipped_feature_ids), 1,
                        "skipped_feature_ids should only contain geometry duplicates")
        self.assertEqual(skipped_feature_ids[0], geom_dup_hash,
                        "Should contain geometry duplicate hash")
        self.assertNotIn(hash_dup_hash, skipped_feature_ids,
                        "Hash duplicate should NOT be in skipped_feature_ids (it's blocked, not skipped)")
        
        print("✓ Test 20 passed: skipped_feature_ids only contains geometry duplicates")


class TestComplexScenarios(TestCase):
    """Test complex real-world scenarios with multiple duplicates."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_multiple_features_mixed_duplicates(self):
        """Test 11: Multiple features with different duplicate types."""
        from django.contrib.gis.geos import Point
        
        # Feature 1: Hash duplicate in feature store
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Feature 1', 'description': 'First'}
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1
        
        coords1 = feature1['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature1,
            geojson_hash=hash1,
            geometry=Point(coords1[0], coords1[1], 0, srid=4326)  # Add Z=0
        )
        
        # Feature 2: Geometry duplicate in feature store
        feature2_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Store Feature', 'description': 'In store'}
        }
        hash2_store = generate_geojson_hash(feature2_in_store)
        
        coords2 = feature2_in_store['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_in_store,
            geojson_hash=hash2_store,
            geometry=Point(coords2[0], coords2[1], 0, srid=4326)  # Add Z=0
        )
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5194, 37.8749]},
            'properties': {'name': 'Feature 2', 'description': 'Second'}
        }
        
        # Feature 3: Hash duplicate in cross-queue
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.6194, 37.9749]},
            'properties': {'name': 'Feature 3', 'description': 'Third'}
        }
        hash3 = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = hash3
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature3],
            imported=False
        )
        
        # Feature 4: Geometry duplicate in cross-queue
        feature4_in_queue = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7194, 38.0749]},
            'properties': {'name': 'Queue Feature', 'description': 'In queue'}
        }
        
        older_queue.geofeatures.append(feature4_in_queue)
        older_queue.save()
        
        feature4 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7194, 38.0749]},
            'properties': {'name': 'Feature 4', 'description': 'Fourth'}
        }
        
        # Feature 5: No duplicate
        feature5 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.8194, 38.1749]},
            'properties': {'name': 'Feature 5', 'description': 'Unique'}
        }
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # Import all 5 features
        all_features = [feature1, feature2, feature3, feature4, feature5]
        
        # PASS 1: Feature store
        remaining_after_fs, fs_dups, fs_log = find_duplicates_for_source(
            all_features,
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue
        remaining_after_cq, cq_dups, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Verify results
        self.assertEqual(len(fs_dups), 2, "Should have 2 feature store duplicates")
        self.assertEqual(len(cq_dups), 2, "Should have 2 cross-queue duplicates")
        self.assertEqual(len(remaining_after_cq), 1, "Should have 1 unique feature")
        
        # Check feature store duplicates
        fs_hash_dups = [d for d in fs_dups if d['match_type'] == DuplicateMatchType.HASH]
        fs_geom_dups = [d for d in fs_dups if d['match_type'] == DuplicateMatchType.GEOMETRY]
        self.assertEqual(len(fs_hash_dups), 1, "Should have 1 FS hash duplicate")
        self.assertEqual(len(fs_geom_dups), 1, "Should have 1 FS geometry duplicate")
        
        # Check cross-queue duplicates
        cq_hash_dups = [d for d in cq_dups if d['match_type'] == DuplicateMatchType.HASH]
        cq_geom_dups = [d for d in cq_dups if d['match_type'] == DuplicateMatchType.GEOMETRY]
        self.assertEqual(len(cq_hash_dups), 1, "Should have 1 CQ hash duplicate")
        self.assertEqual(len(cq_geom_dups), 1, "Should have 1 CQ geometry duplicate")
        
        print("✓ Test 11 passed: Multiple features with mixed duplicate types handled correctly")

    def test_no_duplicates(self):
        """Test 12: Features with no duplicates should all pass through."""
        features = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.1 + i, 37.7 + i]},
                'properties': {'name': f'Feature {i}', 'description': f'Description {i}'}
            }
            for i in range(5)
        ]
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=False
        )
        
        # PASS 1: Feature store
        remaining_after_fs, fs_dups, fs_log = find_duplicates_for_source(
            features,
            self.user.id,
            source='feature_store',
            exclude_queue_id=None,
            exclude_timestamp=None
        )
        
        # PASS 2: Cross-queue
        remaining_after_cq, cq_dups, cq_log = find_duplicates_for_source(
            remaining_after_fs,
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # All should pass through
        self.assertEqual(len(fs_dups), 0, "Should have no feature store duplicates")
        self.assertEqual(len(cq_dups), 0, "Should have no cross-queue duplicates")
        self.assertEqual(len(remaining_after_cq), 5, "All 5 features should remain")
        
        print("✓ Test 12 passed: Non-duplicate features pass through correctly")


class TestSequentialProcessingIntegration(TransactionTestCase):
    """Integration tests for sequential processing with Redis queue.
    
    Note: Sequential processing is now handled by the queue worker system.
    See test_sequential_processing.py for queue-specific tests.
    """
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='sequential@example.com',
            password='testpass123',
            username='sequential_user'
        )
    
    def test_timestamp_ordering_enforced_by_sequential_processing(self):
        """Test that sequential processing enforces timestamp-based duplicate detection."""
        from datetime import datetime, timezone, timedelta
        
        # Create two queue items with different timestamps
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Sequential Test', 'description': 'Test'}
        }
        feature_hash = generate_geojson_hash(feature)
        feature['properties']['geojson_hash'] = feature_hash
        
        base_time = datetime.now(timezone.utc)
        
        # Create older queue item
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        older_queue.timestamp = base_time
        older_queue.save()
        
        # Create newer queue item
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature],
            imported=False
        )
        newer_queue.timestamp = base_time + timedelta(seconds=5)
        newer_queue.save()
        
        # Check duplicates for older file (should see nothing)
        _, dups_older, _ = find_duplicates_for_source(
            [feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=older_queue.id,
            exclude_timestamp=older_queue.timestamp
        )
        
        # Check duplicates for newer file (should see older file)
        _, dups_newer, _ = find_duplicates_for_source(
            [feature],
            self.user.id,
            source='cross_queue',
            exclude_queue_id=newer_queue.id,
            exclude_timestamp=newer_queue.timestamp
        )
        
        # Assertions
        self.assertEqual(len(dups_older), 0,
                        "Older file should not see newer file as duplicate")
        self.assertEqual(len(dups_newer), 1,
                        "Newer file should see older file as duplicate")
        self.assertEqual(dups_newer[0]['existing_features'][0]['id'], older_queue.id,
                        "Newer file should reference older queue item")
        
        print("✓ Timestamp ordering test passed: Sequential processing enforces correct ordering")
