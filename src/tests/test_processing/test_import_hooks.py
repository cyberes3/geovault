"""
Tests for import hooks system.
"""
from unittest.mock import MagicMock, patch
from django.test import TestCase
from django.contrib.auth import get_user_model

from api.models import ImportQueue, FeatureStore
from geo_lib.processing.hooks import register_import_hook, execute_import_hooks
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


class TestImportHooks(TestCase):
    """Test import hooks system."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_register_import_hook_registers_successfully(self):
        """Test register_import_hook() registers hook successfully."""
        hook_called = []
        
        def test_hook(import_item, user_id, created_features):
            hook_called.append((import_item, user_id, created_features))
        
        register_import_hook('test_hook', test_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify hook was called
        self.assertEqual(len(hook_called), 1)
        self.assertEqual(hook_called[0][0], import_item)
        self.assertEqual(hook_called[0][1], self.user.id)
        self.assertEqual(hook_called[0][2], [feature])
    
    def test_register_import_hook_rejects_non_callable_callbacks(self):
        """Test register_import_hook() rejects non-callable callbacks."""
        with self.assertRaises(TypeError):
            register_import_hook('test_hook', 'not a callable')
    
    def test_register_import_hook_replaces_existing_hook_with_same_id(self):
        """Test register_import_hook() replaces existing hook with same ID."""
        hook1_called = []
        hook2_called = []
        
        def hook1(import_item, user_id, created_features):
            hook1_called.append(True)
        
        def hook2(import_item, user_id, created_features):
            hook2_called.append(True)
        
        # Register first hook
        register_import_hook('test_hook', hook1)
        
        # Register second hook with same ID (should replace first)
        register_import_hook('test_hook', hook2)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify only second hook was called
        self.assertEqual(len(hook1_called), 0)
        self.assertEqual(len(hook2_called), 1)
    
    def test_execute_import_hooks_calls_all_registered_hooks(self):
        """Test execute_import_hooks() calls all registered hooks."""
        hook1_called = []
        hook2_called = []
        
        def hook1(import_item, user_id, created_features):
            hook1_called.append(True)
        
        def hook2(import_item, user_id, created_features):
            hook2_called.append(True)
        
        # Register multiple hooks
        register_import_hook('hook1', hook1)
        register_import_hook('hook2', hook2)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify both hooks were called
        self.assertEqual(len(hook1_called), 1)
        self.assertEqual(len(hook2_called), 1)
    
    def test_execute_import_hooks_passes_correct_arguments(self):
        """Test execute_import_hooks() passes correct arguments (import_item, user_id, created_features)."""
        received_args = []
        
        def test_hook(import_item, user_id, created_features):
            received_args.append({
                'import_item': import_item,
                'user_id': user_id,
                'created_features': created_features
            })
        
        register_import_hook('test_hook', test_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify arguments
        self.assertEqual(len(received_args), 1)
        self.assertEqual(received_args[0]['import_item'], import_item)
        self.assertEqual(received_args[0]['user_id'], self.user.id)
        self.assertEqual(received_args[0]['created_features'], [feature])
    
    def test_execute_import_hooks_handles_hook_exceptions_gracefully(self):
        """Test execute_import_hooks() handles hook exceptions gracefully (logs error, doesn't fail import)."""
        def failing_hook(import_item, user_id, created_features):
            raise Exception("Hook failed!")
        
        def working_hook(import_item, user_id, created_features):
            pass
        
        register_import_hook('failing_hook', failing_hook)
        register_import_hook('working_hook', working_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks (should not raise exception)
        try:
            execute_import_hooks(import_item, self.user.id, [feature])
        except Exception:
            self.fail("execute_import_hooks() should handle hook exceptions gracefully")
    
    def test_hooks_are_executed_after_successful_feature_import(self):
        """Test hooks are executed after successful feature import."""
        hook_called = []
        
        def test_hook(import_item, user_id, created_features):
            hook_called.append(True)
        
        register_import_hook('test_hook', test_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks (simulating what BaseProcessor does)
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify hook was called
        self.assertEqual(len(hook_called), 1)
    
    def test_hooks_receive_correct_importqueue_item(self):
        """Test hooks receive correct ImportQueue item."""
        received_items = []
        
        def test_hook(import_item, user_id, created_features):
            received_items.append(import_item)
        
        register_import_hook('test_hook', test_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        feature_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature])
        
        # Verify correct ImportQueue item was passed
        self.assertEqual(len(received_items), 1)
        self.assertEqual(received_items[0].id, import_item.id)
        self.assertEqual(received_items[0].original_filename, 'test.kml')
    
    def test_hooks_receive_correct_list_of_created_featurestore_objects(self):
        """Test hooks receive correct list of created FeatureStore objects."""
        received_features = []
        
        def test_hook(import_item, user_id, created_features):
            received_features.extend(created_features)
        
        register_import_hook('test_hook', test_hook)
        
        # Create test data
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename='test.kml',
            imported=True
        )
        
        # Create multiple features
        feature1_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            'properties': {'name': 'Feature 1'}
        }
        feature1 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1_data,
            geojson_hash=generate_geojson_hash(feature1_data),
            source=import_item
        )
        
        feature2_data = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849, 0.0]},
            'properties': {'name': 'Feature 2'}
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geojson_hash=generate_geojson_hash(feature2_data),
            source=import_item
        )
        
        # Execute hooks
        execute_import_hooks(import_item, self.user.id, [feature1, feature2])
        
        # Verify correct features were passed
        self.assertEqual(len(received_features), 2)
        self.assertIn(feature1, received_features)
        self.assertIn(feature2, received_features)

