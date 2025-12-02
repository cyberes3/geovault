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
from django.test import TestCase
from django.contrib.auth import get_user_model

from api.models import FeatureStore, ImportQueue
from geo_lib.processing.duplicate_detection import (
    find_duplicates_for_source,
    find_hash_duplicates,
    find_geometry_duplicates
)
from geo_lib.processing.duplicate_models import DuplicateSource, DuplicateMatchType
from geo_lib.feature_id import generate_feature_hash


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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
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
        feature_hash1 = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash1
        
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
        feature_hash2 = generate_feature_hash(older_feature)
        older_feature['properties']['feature_hash'] = feature_hash2
        
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
        feature_hash = generate_feature_hash(self.point_feature)
        self.point_feature['properties']['feature_hash'] = feature_hash
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.point_feature,
            geojson_hash=feature_hash
        )
        
        # Cross-queue has geometry match (same coordinates, different properties)
        queue_feature = self.same_coords_different_props.copy()
        queue_feature['properties']['feature_hash'] = generate_feature_hash(queue_feature)
        
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
        fs_hash = generate_feature_hash(fs_feature)
        fs_feature['properties']['feature_hash'] = fs_hash
        
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
        queue_hash = generate_feature_hash(queue_feature)
        queue_feature['properties']['feature_hash'] = queue_hash
        
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
        hash1 = generate_feature_hash(feature1)
        feature1['properties']['feature_hash'] = hash1
        
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
        hash2_store = generate_feature_hash(feature2_in_store)
        
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
        hash3 = generate_feature_hash(feature3)
        feature3['properties']['feature_hash'] = hash3
        
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

