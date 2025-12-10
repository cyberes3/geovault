"""
Unit tests for import utility functions.

Tests the new utility functions build_features_to_skip and filter_features_to_process
that were extracted from ImportJob and BulkImportJob.
"""
from django.test import TestCase
from django.contrib.auth import get_user_model

from api.models import ImportQueue
from geo_lib.processing.import_operations.skip_logic import build_features_to_skip, filter_features_to_process
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


class TestBuildFeaturesToSkip(TestCase):
    """Test build_features_to_skip utility function."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='utils@example.com',
            password='testpass123',
            username='utilsuser'
        )
        
        self.feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        self.hash1 = generate_geojson_hash(self.feature1)
        self.feature1['properties']['geojson_hash'] = self.hash1
        
        self.feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'}
        }
        self.hash2 = generate_geojson_hash(self.feature2)
        self.feature2['properties']['geojson_hash'] = self.hash2
        
        self.feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Feature 3'}
        }
        self.hash3 = generate_geojson_hash(self.feature3)
        self.feature3['properties']['geojson_hash'] = self.hash3

    def test_build_features_to_skip_with_geometry_duplicates(self):
        """Test building skip sets with only geometry duplicates."""
        # Create import item with geometry duplicate
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.feature1,
                'existing_features': []
            }],
            skipped_feature_ids=[],
            imported=False
        )
        
        geom_dups, manual_skips, all_skips = build_features_to_skip(import_item, None)
        
        # Should have geometry duplicate
        self.assertEqual(len(geom_dups), 1)
        self.assertIn(self.hash1, geom_dups)
        
        # No manual skips
        self.assertEqual(len(manual_skips), 0)
        
        # All skips should equal geometry duplicates
        self.assertEqual(all_skips, geom_dups)
        
        print("✓ Test passed: build_features_to_skip with geometry duplicates")

    def test_build_features_to_skip_with_manual_skips(self):
        """Test building skip sets with only manual skips."""
        # Create import item with manual skip (no duplicates)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2, self.feature3],
            duplicate_features=[],
            skipped_feature_ids=[self.hash2],  # User manually skipped feature2
            imported=False
        )
        
        geom_dups, manual_skips, all_skips = build_features_to_skip(import_item, None)
        
        # No geometry duplicates
        self.assertEqual(len(geom_dups), 0)
        
        # Should have manual skip
        self.assertEqual(len(manual_skips), 1)
        self.assertIn(self.hash2, manual_skips)
        
        # All skips should equal manual skips
        self.assertEqual(all_skips, manual_skips)
        
        print("✓ Test passed: build_features_to_skip with manual skips")

    def test_build_features_to_skip_mixed(self):
        """Test building skip sets with both geometry duplicates and manual skips."""
        # Create import item with both geometry duplicate and manual skip
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test3.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2, self.feature3],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.feature1,
                'existing_features': []
            }],
            skipped_feature_ids=[self.hash2],  # User manually skipped feature2
            imported=False
        )
        
        geom_dups, manual_skips, all_skips = build_features_to_skip(import_item, None)
        
        # Should have geometry duplicate
        self.assertEqual(len(geom_dups), 1)
        self.assertIn(self.hash1, geom_dups)
        
        # Should have manual skip
        self.assertEqual(len(manual_skips), 1)
        self.assertIn(self.hash2, manual_skips)
        
        # All skips should be union of both
        self.assertEqual(len(all_skips), 2)
        self.assertIn(self.hash1, all_skips)
        self.assertIn(self.hash2, all_skips)
        
        print("✓ Test passed: build_features_to_skip with mixed duplicates and manual skips")

    def test_build_features_to_skip_bypasses_restore(self):
        """Test that geometry duplicate restore is bypassed (not in skipped_feature_ids)."""
        # Create import item where geometry duplicate was "restored" by user
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test4.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.feature1,
                'existing_features': []
            }],
            skipped_feature_ids=[],  # User clicked "Restore" - empty list
            imported=False
        )
        
        geom_dups, manual_skips, all_skips = build_features_to_skip(import_item, None)
        
        # Geometry duplicate should still be in skip set despite restore
        self.assertEqual(len(geom_dups), 1)
        self.assertIn(self.hash1, geom_dups)
        self.assertIn(self.hash1, all_skips)
        
        print("✓ Test passed: build_features_to_skip bypasses restore")

    def test_build_features_to_skip_with_user_skipped_ids(self):
        """Test passing user_skipped_feature_ids parameter (single import case)."""
        # Create import item
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test5.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature1, self.feature2, self.feature3],
            duplicate_features=[],
            skipped_feature_ids=[self.hash1],  # Saved skip
            imported=False
        )
        
        # Pass additional skip via parameter (simulates single import request)
        geom_dups, manual_skips, all_skips = build_features_to_skip(
            import_item, 
            user_skipped_feature_ids=[self.hash2]
        )
        
        # Should merge both saved and parameter skips
        self.assertEqual(len(manual_skips), 2)
        self.assertIn(self.hash1, manual_skips)
        self.assertIn(self.hash2, manual_skips)
        
        print("✓ Test passed: build_features_to_skip with user_skipped_ids parameter")


class TestFilterFeaturesToProcess(TestCase):
    """Test filter_features_to_process utility function."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='filter@example.com',
            password='testpass123',
            username='filteruser'
        )
        
        self.features = []
        self.hashes = []
        for i in range(5):
            feature = {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.0 + i*0.1, 37.7 + i*0.1]},
                'properties': {'name': f'Feature {i}'}
            }
            hash_val = generate_geojson_hash(feature)
            feature['properties']['geojson_hash'] = hash_val
            self.features.append(feature)
            self.hashes.append(hash_val)

    def test_filter_features_to_process_no_skips(self):
        """Test filtering with empty skip set."""
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='filter1.kml',
            raw_file='<kml></kml>',
            geofeatures=self.features,
            duplicate_features=[],
            imported=False
        )
        
        features_to_process, skipped_count = filter_features_to_process(import_item, set())
        
        # All features should be included
        self.assertEqual(len(features_to_process), 5)
        self.assertEqual(skipped_count, 0)
        
        print("✓ Test passed: filter_features_to_process with no skips")

    def test_filter_features_to_process_some_skips(self):
        """Test filtering with some features skipped."""
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='filter2.kml',
            raw_file='<kml></kml>',
            geofeatures=self.features,
            duplicate_features=[],
            imported=False
        )
        
        # Skip features 1 and 3
        skip_set = {self.hashes[1], self.hashes[3]}
        features_to_process, skipped_count = filter_features_to_process(import_item, skip_set)
        
        # Should have 3 features remaining
        self.assertEqual(len(features_to_process), 3)
        self.assertEqual(skipped_count, 2)
        
        # Verify correct features remain
        remaining_names = [f['properties']['name'] for f in features_to_process]
        self.assertIn('Feature 0', remaining_names)
        self.assertIn('Feature 2', remaining_names)
        self.assertIn('Feature 4', remaining_names)
        self.assertNotIn('Feature 1', remaining_names)
        self.assertNotIn('Feature 3', remaining_names)
        
        print("✓ Test passed: filter_features_to_process with some skips")

    def test_filter_features_to_process_all_skips(self):
        """Test filtering when all features are skipped."""
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='filter3.kml',
            raw_file='<kml></kml>',
            geofeatures=self.features,
            duplicate_features=[],
            imported=False
        )
        
        # Skip all features
        skip_set = set(self.hashes)
        features_to_process, skipped_count = filter_features_to_process(import_item, skip_set)
        
        # No features should remain
        self.assertEqual(len(features_to_process), 0)
        self.assertEqual(skipped_count, 5)
        
        print("✓ Test passed: filter_features_to_process with all skips")
