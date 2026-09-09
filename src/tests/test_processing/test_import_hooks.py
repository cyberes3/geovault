"""Tests for post-import ImportProvider execution."""
from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import FeatureStore, ImportQueue
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.hooks import execute_import_hooks
from website.extensions.capabilities import clear_extension_context, set_extension_context
from website.extensions.import_provider import (
    ExternalIds,
    ImportHookPayload,
    clear_import_providers,
    register_import_provider,
)

User = get_user_model()

_EXT = "import_hooks_test"


class _CallbackProvider:
    def __init__(self, callback):
        self.callback = callback

    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        return ExternalIds()

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        self.callback(payload.import_item, payload.user_id, payload.created_features)


class TestImportHooks(TestCase):
    """execute_import_hooks delivers ImportHookPayload to registered providers."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="test@example.com",
            password="testpass123",
            username="testuser",
        )
        clear_extension_context()
        clear_import_providers()

    def tearDown(self):
        clear_extension_context()
        clear_import_providers()

    def _register(self, callback):
        set_extension_context(_EXT)
        register_import_provider(_CallbackProvider(callback))
        clear_extension_context()

    def _feature(self, import_item, name="Test Feature"):
        feature_data = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [10.0, 20.0, 0.0]},
            "properties": {"name": name},
        }
        return FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geojson_hash=generate_geojson_hash(feature_data),
            source=import_item,
        )

    def test_register_provider_executes_via_execute_import_hooks(self):
        hook_called = []

        def test_hook(import_item, user_id, created_features):
            hook_called.append((import_item, user_id, created_features))

        self._register(test_hook)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature = self._feature(import_item)
        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(hook_called), 1)
        self.assertEqual(hook_called[0][0], import_item)
        self.assertEqual(hook_called[0][1], self.user.id)
        self.assertEqual(hook_called[0][2], [feature])

    def test_register_provider_rejects_non_callable_methods(self):
        set_extension_context(_EXT)
        with self.assertRaises(TypeError):
            register_import_provider(object())
        clear_extension_context()

    def test_execute_import_hooks_calls_multiple_providers(self):
        hook1_called = []
        hook2_called = []
        self._register(lambda *args: hook1_called.append(True))
        self._register(lambda *args: hook2_called.append(True))

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature = self._feature(import_item)
        execute_import_hooks(import_item, self.user.id, [feature])

        self.assertEqual(len(hook1_called), 1)
        self.assertEqual(len(hook2_called), 1)

    def test_execute_import_hooks_handles_exceptions_gracefully(self):
        def failing_hook(import_item, user_id, created_features):
            raise Exception("Hook failed!")

        def working_hook(import_item, user_id, created_features):
            pass

        self._register(failing_hook)
        self._register(working_hook)

        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature = self._feature(import_item)
        try:
            execute_import_hooks(import_item, self.user.id, [feature])
        except Exception:
            self.fail("execute_import_hooks() should handle provider exceptions gracefully")

    def test_hooks_receive_correct_list_of_created_featurestore_objects(self):
        received_features = []

        def test_hook(import_item, user_id, created_features):
            received_features.extend(created_features)

        self._register(test_hook)
        import_item = ImportQueue.objects.create(
            user=self.user,
            original_filename="test.kml",
            imported=True,
        )
        feature1 = self._feature(import_item, "Feature 1")
        feature2 = self._feature(import_item, "Feature 2")
        execute_import_hooks(import_item, self.user.id, [feature1, feature2])

        self.assertEqual(len(received_features), 2)
        self.assertIn(feature1, received_features)
        self.assertIn(feature2, received_features)
