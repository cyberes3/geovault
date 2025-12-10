"""
Tests for import job duplicate handling (single and bulk imports).

Validates that:
1. Single imports via ImportJob respect skipped_feature_ids (user skip/restore choices)
2. Bulk imports via BulkImportJob auto-skip ALL geometry duplicates
3. Both jobs always block hash duplicates
4. Cross-queue and feature store duplicates are handled correctly
"""
from django.test import TestCase
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType, DuplicateSource
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.import_utils import process_features_for_import

User = get_user_model()


class TestSingleImportWithDuplicates(TestCase):
    """Test single import (ImportJob) behavior with duplicates."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Base feature for tests
        self.base_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'}
        }
        
        # Same coordinates, different properties (geometry duplicate)
        self.geom_duplicate = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'}
        }

    def test_single_import_auto_skips_geometry_duplicates(self):
        """Test that single import automatically skips ALL geometry duplicates."""
        # Create geometry duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with geometry duplicate
        geom_dup_hash = generate_geojson_hash(self.geom_duplicate)
        self.geom_duplicate['properties']['geojson_hash'] = geom_dup_hash
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.geom_duplicate],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.geom_duplicate,
                'existing_features': []
            }],
            imported=False
        )
        
        # Simulate single import processing (what ImportJob does)
        # Build geometry duplicate hashes set
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        # Process with geometry duplicate hashes (as ImportJob now does)
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Geometry duplicate should be automatically skipped
        self.assertEqual(len(features_to_create), 0, 
                        "Single import should auto-skip geometry duplicates")
        self.assertEqual(len(skipped.geometry), 1, "Should report 1 skipped geometry duplicate")
        
        print("✓ Test passed: Single import auto-skips geometry duplicates")
    
    def test_single_import_bypasses_skip_restore_state(self):
        """Test that single import bypasses user skip/restore state for geometry duplicates."""
        # Create geometry duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with geometry duplicate
        geom_dup_hash = generate_geojson_hash(self.geom_duplicate)
        self.geom_duplicate['properties']['geojson_hash'] = geom_dup_hash
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.geom_duplicate],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.geom_duplicate,
                'existing_features': []
            }],
            skipped_feature_ids=[],  # User clicked "Restore" - wants to import
            imported=False
        )
        
        # Simulate single import (should still skip despite user restore)
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Should still be skipped even though user "restored" it
        self.assertEqual(len(features_to_create), 0, 
                        "Single import should skip geometry duplicates regardless of restore state")
        
        print("✓ Test passed: Single import bypasses skip/restore state")

    def test_single_import_always_blocks_hash_duplicates(self):
        """Test that single import always blocks hash duplicates regardless of skipped_feature_ids."""
        # Create hash duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with hash duplicate
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.base_feature],
            duplicate_features=[],
            imported=False
        )
        
        # Test: Hash duplicate should be blocked even with empty skipped_feature_ids
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, set()
        )
        self.assertEqual(len(features_to_create), 0, 
                        "Hash duplicate should always be blocked")
        self.assertEqual(len(skipped.hash), 1, "Should report 1 skipped hash duplicate")
        
        # Test: Hash duplicate should also be blocked with feature in skipped_feature_ids
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, {base_hash}
        )
        self.assertEqual(len(features_to_create), 0, 
                        "Hash duplicate should be blocked regardless of skipped_feature_ids")
        
        print("✓ Test passed: Single import always blocks hash duplicates")


class TestBulkImportWithDuplicates(TestCase):
    """Test bulk import (BulkImportJob) behavior with duplicates."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='bulk@example.com',
            password='testpass123',
            username='bulkuser'
        )
        
        self.base_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'}
        }
        
        self.geom_duplicate = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Different Name', 'description': 'Different description'}
        }

    def test_bulk_import_auto_skips_geometry_duplicates(self):
        """Test that bulk import automatically skips ALL geometry duplicates."""
        # Create geometry duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with geometry duplicate
        geom_dup_hash = generate_geojson_hash(self.geom_duplicate)
        self.geom_duplicate['properties']['geojson_hash'] = geom_dup_hash
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.geom_duplicate],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.geom_duplicate,
                'existing_features': []
            }],
            skipped_feature_ids=[],  # User has NOT clicked skip
            imported=False
        )
        
        # Simulate bulk import processing (what BulkImportJob._import_single_item does)
        # Build geometry duplicate hashes set
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        # Process features with geometry duplicate hashes (as bulk import does)
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Geometry duplicate should be automatically skipped
        self.assertEqual(len(features_to_create), 0, 
                        "Bulk import should auto-skip geometry duplicates")
        self.assertEqual(len(skipped.geometry), 1, 
                        "Should report 1 skipped geometry duplicate")
        self.assertEqual(geom_dup_hash, geometry_duplicate_hashes.pop(),
                        "Geometry duplicate hash should be in the skip set")
        
        print("✓ Test passed: Bulk import auto-skips geometry duplicates")

    def test_bulk_import_bypasses_skip_restore_state(self):
        """Test that bulk import bypasses user skip/restore state for geometry duplicates."""
        # Create geometry duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with geometry duplicate
        geom_dup_hash = generate_geojson_hash(self.geom_duplicate)
        self.geom_duplicate['properties']['geojson_hash'] = geom_dup_hash
        
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.geom_duplicate],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': self.geom_duplicate,
                'existing_features': []
            }],
            skipped_feature_ids=[],  # User clicked "Restore" - wants to import
            imported=False
        )
        
        # Simulate bulk import (should still skip despite user restore)
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Should still be skipped even though user "restored" it
        self.assertEqual(len(features_to_create), 0, 
                        "Bulk import should skip geometry duplicates regardless of restore state")
        
        print("✓ Test passed: Bulk import bypasses skip/restore state")

    def test_bulk_import_always_blocks_hash_duplicates(self):
        """Test that bulk import always blocks hash duplicates."""
        # Create hash duplicate in feature store
        base_hash = generate_geojson_hash(self.base_feature)
        self.base_feature['properties']['geojson_hash'] = base_hash
        
        coords = self.base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=self.base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import queue item with hash duplicate
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_hash_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.base_feature],
            duplicate_features=[],
            imported=False
        )
        
        # Simulate bulk import processing (empty geometry set like before the fix)
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, set()
        )
        
        # Hash duplicate should still be blocked
        self.assertEqual(len(features_to_create), 0, 
                        "Bulk import should always block hash duplicates")
        self.assertEqual(len(skipped.hash), 1, "Should report 1 skipped hash duplicate")
        
        print("✓ Test passed: Bulk import always blocks hash duplicates")


class TestCrossQueueDuplicatesInImport(TestCase):
    """Test handling of cross-queue duplicates during import."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='crossqueue@example.com',
            password='testpass123',
            username='crossqueueuser'
        )
        
        self.feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Cross Queue Test', 'description': 'Test'}
        }

    def test_bulk_import_skips_cross_queue_geometry_duplicates(self):
        """Test that bulk import skips cross-queue geometry duplicates."""
        # Create older queue item with a feature
        older_feature = self.feature.copy()
        older_hash = generate_geojson_hash(older_feature)
        older_feature['properties']['geojson_hash'] = older_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[older_feature],
            imported=False
        )
        
        # Create newer queue item with geometry duplicate (different name)
        newer_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Different Name', 'description': 'Different'}
        }
        newer_hash = generate_geojson_hash(newer_feature)
        newer_feature['properties']['geojson_hash'] = newer_hash
        
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[newer_feature],
            duplicate_features=[{
                'source': DuplicateSource.CROSS_QUEUE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': newer_feature,
                'existing_features': [{'id': older_queue.id}]
            }],
            imported=False
        )
        
        # Simulate bulk import for newer queue
        geometry_duplicate_hashes = set()
        if newer_queue.duplicate_features:
            for dup_info in newer_queue.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        features_to_create, skipped = process_features_for_import(
            newer_queue, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Cross-queue geometry duplicate should be skipped
        self.assertEqual(len(features_to_create), 0, 
                        "Bulk import should skip cross-queue geometry duplicates")
        self.assertEqual(len(skipped.geometry), 1, 
                        "Should report 1 skipped cross-queue geometry duplicate")
        
        print("✓ Test passed: Bulk import skips cross-queue geometry duplicates")

    def test_bulk_import_blocks_cross_queue_hash_duplicates(self):
        """Test that bulk import blocks cross-queue hash duplicates."""
        # Create older queue item with a feature
        feature_hash = generate_geojson_hash(self.feature)
        self.feature['properties']['geojson_hash'] = feature_hash
        
        older_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='older.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature],
            imported=False
        )
        
        # Create newer queue item with exact same feature (hash duplicate)
        newer_queue = ImportQueue.objects.create(
            user=self.user,
            original_filename='newer.kml',
            raw_file='<kml></kml>',
            geofeatures=[self.feature],
            duplicate_features=[],
            imported=False
        )
        
        # Simulate bulk import for newer queue
        features_to_create, skipped = process_features_for_import(
            newer_queue, self.user.id, True, None, set()
        )
        
        # Cross-queue hash duplicate should be blocked
        self.assertEqual(len(features_to_create), 0, 
                        "Bulk import should block cross-queue hash duplicates")
        self.assertEqual(len(skipped.hash), 1, 
                        "Should report 1 skipped cross-queue hash duplicate")
        
        print("✓ Test passed: Bulk import blocks cross-queue hash duplicates")


class TestManualSkipBehavior(TestCase):
    """Test manual skip behavior (user clicking Skip on non-duplicates)."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='skiptest@example.com',
            password='testpass123',
            username='skipuser'
        )
    
    def test_single_import_respects_manual_skips(self):
        """Test that single import respects manually skipped non-duplicate features."""
        # Create 3 unique features (no duplicates)
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Feature 1'}
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Feature 2'}
        }
        hash2 = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = hash2
        
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Feature 3'}
        }
        hash3 = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = hash3
        
        # Create import queue item with user manually skipping feature2
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='skip_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2, feature3],
            duplicate_features=[],
            skipped_feature_ids=[hash2],  # User manually skipped feature2
            imported=False
        )
        
        # Simulate single import logic - filter features before processing
        saved_skipped_ids = set(import_item.skipped_feature_ids)
        manually_skipped_non_duplicates = saved_skipped_ids  # No geometry duplicates to remove
        all_features_to_skip = manually_skipped_non_duplicates
        
        # Filter features like ImportJob does
        features_to_process = []
        for feature in import_item.geofeatures:
            feature_id = feature['properties']['geojson_hash']
            if feature_id not in all_features_to_skip:
                features_to_process.append(feature)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, features_to_process, manually_skipped_non_duplicates
        )
        
        # Should create 2 features (1 and 3), skipping feature2
        self.assertEqual(len(features_to_create), 2, "Should create 2 features (skipping manual skip)")
        created_names = [f.geojson['properties']['name'] for f in features_to_create]
        self.assertIn('Feature 1', created_names)
        self.assertIn('Feature 3', created_names)
        self.assertNotIn('Feature 2', created_names, "Feature 2 should be skipped")
        
        print("✓ Test passed: Single import respects manual skips")
    
    def test_bulk_import_respects_manual_skips(self):
        """Test that bulk import respects manually skipped non-duplicate features."""
        # Create 3 unique features
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4, 38.0]},
            'properties': {'name': 'Bulk Feature 1'}
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 38.1]},
            'properties': {'name': 'Bulk Feature 2'}
        }
        hash2 = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = hash2
        
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.6, 38.2]},
            'properties': {'name': 'Bulk Feature 3'}
        }
        hash3 = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = hash3
        
        # Create import queue item with user manually skipping feature2
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='bulk_skip_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2, feature3],
            duplicate_features=[],
            skipped_feature_ids=[hash2],  # User manually skipped feature2
            imported=False
        )
        
        # Simulate bulk import logic - filter features before processing
        saved_skipped_ids = set(import_item.skipped_feature_ids)
        manually_skipped_non_duplicates = saved_skipped_ids  # No geometry duplicates
        all_features_to_skip = manually_skipped_non_duplicates
        
        # Filter features like BulkImportJob does
        features_to_process = []
        for feature in import_item.geofeatures:
            feature_id = feature['properties']['geojson_hash']
            if feature_id not in all_features_to_skip:
                features_to_process.append(feature)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, features_to_process, set()  # Empty set for geometry duplicates
        )
        
        # Should create 2 features (1 and 3), skipping feature2
        self.assertEqual(len(features_to_create), 2, "Should create 2 features (skipping manual skip)")
        created_names = [f.geojson['properties']['name'] for f in features_to_create]
        self.assertIn('Bulk Feature 1', created_names)
        self.assertIn('Bulk Feature 3', created_names)
        self.assertNotIn('Bulk Feature 2', created_names, "Bulk Feature 2 should be skipped")
        
        print("✓ Test passed: Bulk import respects manual skips")
    
    def test_geometry_duplicate_restore_is_ignored(self):
        """Test that restoring a geometry duplicate is ignored - it's still skipped."""
        # Create geometry duplicate in feature store
        base_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7, 38.3]},
            'properties': {'name': 'Base Feature'}
        }
        base_hash = generate_geojson_hash(base_feature)
        base_feature['properties']['geojson_hash'] = base_hash
        
        coords = base_feature['geometry']['coordinates']
        FeatureStore.objects.create(
            user=self.user,
            geojson=base_feature,
            geojson_hash=base_hash,
            geometry=Point(coords[0], coords[1], 0, srid=4326)
        )
        
        # Create import with geometry duplicate
        geom_dup = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.7, 38.3]},
            'properties': {'name': 'Geometry Duplicate'}
        }
        geom_dup_hash = generate_geojson_hash(geom_dup)
        geom_dup['properties']['geojson_hash'] = geom_dup_hash
        
        # User tries to "restore" the geometry duplicate (skipped_feature_ids is empty)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='restore_test.kml',
            raw_file='<kml></kml>',
            geofeatures=[geom_dup],
            duplicate_features=[{
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.GEOMETRY,
                'feature': geom_dup,
                'existing_features': []
            }],
            skipped_feature_ids=[],  # User clicked "Restore" - wants to import
            imported=False
        )
        
        # Simulate import logic
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        saved_skipped_ids = set(import_item.skipped_feature_ids)
        manually_skipped_non_duplicates = saved_skipped_ids - geometry_duplicate_hashes
        all_features_to_skip = geometry_duplicate_hashes.union(manually_skipped_non_duplicates)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, all_features_to_skip
        )
        
        # Geometry duplicate should still be skipped despite restore
        self.assertEqual(len(features_to_create), 0, "Geometry duplicate should be skipped despite restore")
        self.assertEqual(len(skipped.geometry), 1, "Should report 1 skipped geometry duplicate")
        
        print("✓ Test passed: Geometry duplicate restore is ignored")


class TestMixedDuplicatesInImport(TestCase):
    """Test import with mix of hash and geometry duplicates."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='mixed@example.com',
            password='testpass123',
            username='mixeduser'
        )

    def test_bulk_import_handles_mixed_duplicates_correctly(self):
        """Test bulk import with both hash and geometry duplicates."""
        # Feature 1: Hash duplicate in feature store
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.1, 37.7]},
            'properties': {'name': 'Hash Dup', 'description': 'Exact match'}
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature1,
            geojson_hash=hash1,
            geometry=Point(-122.1, 37.7, 0, srid=4326)
        )
        
        # Feature 2: Geometry duplicate in feature store
        feature2_in_store = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Store Feature', 'description': 'Store'}
        }
        hash2_store = generate_geojson_hash(feature2_in_store)
        
        FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_in_store,
            geojson_hash=hash2_store,
            geometry=Point(-122.2, 37.8, 0, srid=4326)
        )
        
        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.2, 37.8]},
            'properties': {'name': 'Geom Dup', 'description': 'Different'}
        }
        hash2 = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = hash2
        
        # Feature 3: Unique (should be imported)
        feature3 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.3, 37.9]},
            'properties': {'name': 'Unique', 'description': 'Not duplicate'}
        }
        hash3 = generate_geojson_hash(feature3)
        feature3['properties']['geojson_hash'] = hash3
        
        # Create import queue item with all three features
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='mixed.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature1, feature2, feature3],
            duplicate_features=[
                {
                    'source': DuplicateSource.FEATURE_STORE,
                    'match_type': DuplicateMatchType.HASH,
                    'feature': feature1,
                    'existing_features': []
                },
                {
                    'source': DuplicateSource.FEATURE_STORE,
                    'match_type': DuplicateMatchType.GEOMETRY,
                    'feature': feature2,
                    'existing_features': []
                }
            ],
            imported=False
        )
        
        # Simulate bulk import
        geometry_duplicate_hashes = set()
        if import_item.duplicate_features:
            for dup_info in import_item.duplicate_features:
                if dup_info.get('match_type') == DuplicateMatchType.GEOMETRY:
                    dup_feature = dup_info.get('feature')
                    if dup_feature:
                        geojson_hash = dup_feature['properties'].get('geojson_hash')
                        if geojson_hash:
                            geometry_duplicate_hashes.add(geojson_hash)
        
        features_to_create, skipped = process_features_for_import(
            import_item, self.user.id, True, None, geometry_duplicate_hashes
        )
        
        # Should have 1 feature to create (the unique one)
        self.assertEqual(len(features_to_create), 1, 
                        "Should create only the unique feature")
        self.assertEqual(features_to_create[0].geojson['properties']['name'], 'Unique',
                        "Should be the unique feature")
        
        # Should have 1 hash duplicate and 1 geometry duplicate skipped
        self.assertEqual(len(skipped.hash), 1, "Should have 1 hash duplicate")
        self.assertEqual(len(skipped.geometry), 1, "Should have 1 geometry duplicate")
        
        print("✓ Test passed: Bulk import handles mixed duplicates correctly")
