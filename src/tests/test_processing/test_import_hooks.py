"""
Tests for post-import hook execution (extension hook registry).
"""
from django.contrib.auth import get_user_model
from django.test import TestCase

import website.extensions.extension_hooks as extension_hooks_module
from api.models import ImportQueue, FeatureStore
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.hooks import execute_import_hooks
from website.extensions.extension_hooks import (
    clear_extension_context,
    register_hook,
    set_extension_context,
)

User = get_user_model()

_EXT = "import_hooks_test"


class TestImportHooks(TestCase):
    """execute_import_hooks runs import hooks registered via register_hook."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="test@example.com",
            password="testpass123",
            username="testuser",
        )
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()

    def tearDown(self):
        clear_extension_context()
        extension_hooks_module._hook_registry.clear()

    def _register_import_hook(self, hook_id: str, callback):
        set_extension_context(_EXT)
        register_hook("import", hook_id, callback)
        clear_extension_context()

    def test_register_hook_executes_via_execute_import_hooks(self):
        hook_called = []

        def test_hook(import_item, user_id, created_features):
            hook_called.append((import_item, user_id, created_features))

        self._register_import_hook("test_hook", test_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(hook_called), 1)
        self.assertEqual(hook_called[0][0], import_item)
        self.assertEqual(hook_called[0][1], self.user.id)
        self.assertEqual(hook_called[0][2], [feature])

    def test_register_hook_rejects_non_callable_callbacks(self):
        set_extension_context(_EXT)
        with self.assertRaises(TypeError):
            register_hook("import", "bad", "not a callable")
        clear_extension_context()

    def test_register_hook_replaces_same_id(self):
        hook1_called = []
        hook2_called = []

        def hook1(import_item, user_id, created_features):
            hook1_called.append(True)

        def hook2(import_item, user_id, created_features):
            hook2_called.append(True)

        self._register_import_hook("same_id", hook1)
        self._register_import_hook("same_id", hook2)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(hook1_called), 0)
        self.assertEqual(len(hook2_called), 1)

    def test_execute_import_hooks_calls_multiple_hooks(self):
        hook1_called = []
        hook2_called = []

        def hook1(import_item, user_id, created_features):
            hook1_called.append(True)

        def hook2(import_item, user_id, created_features):
            hook2_called.append(True)

        self._register_import_hook("hook1", hook1)
        self._register_import_hook("hook2", hook2)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(hook1_called), 1)
        self.assertEqual(len(hook2_called), 1)

    def test_execute_import_hooks_passes_correct_arguments(self):
        received_args = []

        def test_hook(import_item, user_id, created_features):
            received_args.append(
                {
                    "import_item": import_item,
                    "user_id": user_id,
                    "created_features": created_features,
                }
            )

        self._register_import_hook("arg_hook", test_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(received_args), 1)
        self.assertEqual(received_args[0]["import_item"], import_item)
        self.assertEqual(received_args[0]["user_id"], self.user.id)
        self.assertEqual(received_args[0]["created_features"], [feature])

    def test_execute_import_hooks_handles_hook_exceptions_gracefully(self):
        def failing_hook(import_item, user_id, created_features):
            raise Exception("Hook failed!")

        def working_hook(import_item, user_id, created_features):
            pass

        self._register_import_hook("failing_hook", failing_hook)
        self._register_import_hook("working_hook", working_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        try:
            execute_import_hooks(import_item, self.user.id, [feature])
        except Exception:
            self.fail("execute_import_hooks() should handle hook exceptions gracefully")

    def test_hooks_receive_correct_importqueue_item(self):
        received_items = []

        def test_hook(import_item, user_id, created_features):
            received_items.append(import_item)

        self._register_import_hook("item_hook", test_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Test Feature"},
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(received_items), 1)
        self.assertEqual(received_items[0].id, import_item.id)
        self.assertEqual(received_items[0].original_filename, "test.kml")

    def test_hooks_receive_correct_list_of_created_featurestore_objects(self):
        received_features = []

        def test_hook(import_item, user_id, created_features):
            received_features.extend(created_features)

        self._register_import_hook("features_hook", test_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature1_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4194, 37.7749, 0.0]},
            "properties": {"name": "Feature 1"},
        }
        feature1 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature1_data,
            geojson_hash=generate_geojson_hash(feature1_data),
            source=import_item,
        )
        feature2_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-122.4094, 37.7849, 0.0]},
            "properties": {"name": "Feature 2"},
        }
        feature2 = FeatureStore.objects.create(
            user=self.user,
            geojson=feature2_data,
            geojson_hash=generate_geojson_hash(feature2_data),
            source=import_item,
        )

        execute_import_hooks(import_item, self.user.id, [feature1, feature2])

        self.assertEqual(len(received_features), 2)
        self.assertIn(feature1, received_features)
        self.assertIn(feature2, received_features)
