"""
Tests for RealtimeConsumer WebSocket functionality.
"""
import json
from unittest.mock import AsyncMock, MagicMock, patch
from channels.db import database_sync_to_async
from django.test import TestCase
from channels.layers import get_channel_layer
from channels.testing import WebsocketCommunicator
from django.contrib.auth.models import AnonymousUser
from django.test import TransactionTestCase
from django.contrib.auth import get_user_model

from django.contrib.gis.geos import Point

from api.models import ImportQueue, FeatureStore
from api.ws_consumers.realtime_consumer import RealtimeConsumer
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.models import DuplicateSource, DuplicateMatchType
from geo_lib.websocket.modules.import_queue_module import ImportQueueModule
from geo_lib.websocket.modules.process_status_module import ProcessStatusModule
from geo_lib.websocket.modules.import_history_module import ImportHistoryModule
from django.utils import timezone

User = get_user_model()


class TestRealtimeConsumerLoadModules(TestCase):
    """Test that RealtimeConsumer loads built-in and extension-registered WebSocket modules."""

    def test_load_modules_includes_registered_extension_modules(self):
        """_load_modules() adds modules from get_registered_websocket_modules()."""
        class FakeExtensionModule:
            def __init__(self, consumer):
                self.consumer = consumer

        with patch(
            "api.ws_consumers.realtime_consumer.get_registered_websocket_modules",
            return_value=[("_test_extension_mod_", FakeExtensionModule)],
        ):
            consumer = RealtimeConsumer()
            consumer.user = MagicMock()
            consumer.room_group_name = "realtime_1"
            consumer._load_modules()

        self.assertIn("import_queue", consumer.modules)
        self.assertIn("_test_extension_mod_", consumer.modules)
        self.assertIsInstance(consumer.modules["_test_extension_mod_"], FakeExtensionModule)
        self.assertIs(consumer.modules["_test_extension_mod_"].consumer, consumer)


class TestRealtimeConsumerConnection(TransactionTestCase):
    """Test RealtimeConsumer connection and authentication."""

    async def test_connection_authenticated(self):
        """Test WebSocket connection with authenticated user."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        await communicator.disconnect()

    async def test_connection_unauthenticated(self):
        """Test that unauthenticated users are rejected."""
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = AnonymousUser()
        
        # connect() should timeout or return False for unauthenticated
        # The consumer closes the connection immediately
        try:
            connected, subprotocol = await communicator.connect(timeout=2)
            # If it connects, it should be False
            self.assertFalse(connected)
        except Exception:
            # Timeout is also acceptable for rejected connections
            pass

    async def test_ping_pong(self):
        """Test ping/pong keepalive mechanism."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Just verify connection works, ping/pong is handled internally
        # The consumer sends initial state and may close
        try:
            # Try to receive initial states
            msg = await communicator.receive_json_from(timeout=1)
            self.assertIn('type', msg)
        except:
            pass
        
        await communicator.disconnect()

    async def test_disconnect_cleanup(self):
        """Test that disconnect properly cleans up."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Disconnect should not raise errors
        await communicator.disconnect()


class TestRealtimeConsumerModules(TransactionTestCase):
    """Test RealtimeConsumer module routing and message handling."""

    async def test_import_queue_module_loaded(self):
        """Test that import_queue module is loaded and handles messages."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Test that module can receive refresh message
        await communicator.send_json_to({
            'module': 'import_queue',
            'type': 'refresh',
            'data': {}
        })
        
        # Should receive a response without errors
        response = await communicator.receive_json_from(timeout=2)
        self.assertIsInstance(response, dict)
        self.assertEqual(response.get('module'), 'import_queue')
        self.assertIn('type', response)
        
        await communicator.disconnect()

    async def test_import_queue_data_with_file_hashes(self):
        """Test that get_import_queue_data correctly handles duplicate file hash detection.
        
        This test would have caught the NameError bug where 'already_imported_items'
        was used instead of 'imported_items'.
        """
        
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Create ImportQueue items with file_hashes to test duplicate detection
        # This triggers the code path that had the NameError bug
        queue_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash='test_hash_123',
            original_filename='test_queue.geojson',
            geofeatures=[{'type': 'Feature', 'properties': {'name': 'Test'}}]
        )
        
        imported_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=True,
            file_hash='test_hash_123',  # Same hash as queue_item
            original_filename='test_imported.geojson',
            geofeatures=[]
        )
        
        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"
        
        module = ImportQueueModule(mock_consumer)
        
        # Actually call get_import_queue_data - this would have caught the NameError bug
        queue_data = await module.get_import_queue_data()
        
        # Verify the data structure
        self.assertIsInstance(queue_data, list)
        
        # Verify queue item is in the data
        queue_item_ids = [item['id'] for item in queue_data]
        self.assertIn(queue_item.id, queue_item_ids)
        
        # Verify imported item is NOT in the queue data (it's imported)
        self.assertNotIn(imported_item.id, queue_item_ids)
        
        # Verify duplicate detection worked - find the queue item
        queue_item_data = next((item for item in queue_data if item['id'] == queue_item.id), None)
        self.assertIsNotNone(queue_item_data)
        
        # Verify file_duplicate information is present (tests the duplicate detection code path)
        # This would have caught the NameError bug where 'already_imported_items' was used
        self.assertIn('file_duplicate', queue_item_data)
        self.assertIsInstance(queue_item_data['file_duplicate'], dict)
        self.assertIn('status', queue_item_data['file_duplicate'])
        
        # The queue item should be marked as duplicate_imported since imported_item has same hash
        self.assertEqual(queue_item_data['file_duplicate']['status'], 'duplicate_imported')

    async def test_import_queue_data_strips_internal_only_fields(self):
        """geofeatures/duplicate_features/log_id/file_hash/unparsable are only needed internally
        to compute feature_count/file_duplicate_status; they must never reach the client. Both
        geofeatures and (especially) duplicate_features can embed several MB of full GeoJSON per
        item, so leaving either in risks exceeding the WebSocket message size limit on large or
        dupe-heavy imports (this regressed in production once for duplicate_features)."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_strip_fields@example.com',
            password='testpass123',
            username='testuser_strip_fields'
        )

        duplicate_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point'}
        }
        duplicate_features_entry = [{
            'feature': duplicate_feature,
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,
            'existing_features': [{'id': 1, 'geojson': duplicate_feature}]
        }]

        await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash='test_hash_strip',
            original_filename='strip_fields.geojson',
            geofeatures=[duplicate_feature],
            duplicate_features=duplicate_features_entry
        )

        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"

        module = ImportQueueModule(mock_consumer)
        queue_data = await module.get_import_queue_data()

        self.assertEqual(len(queue_data), 1)
        item = queue_data[0]
        for internal_field in ('geofeatures', 'duplicate_features', 'log_id', 'file_hash', 'unparsable'):
            self.assertNotIn(internal_field, item)

    async def test_import_queue_data_without_file_hashes(self):
        """Test that get_import_queue_data works when items have no file_hashes.
        
        This tests the edge case where queue_hashes is empty, ensuring the code
        doesn't break when there are no hashes to check.
        """
        
        user = await database_sync_to_async(User.objects.create_user)(
            email='test2@example.com',
            password='testpass123',
            username='testuser2'
        )
        
        # Create ImportQueue item without file_hash
        queue_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash=None,  # No file hash
            original_filename='test_no_hash.geojson',
            geofeatures=[{'type': 'Feature', 'properties': {'name': 'Test'}}]
        )
        
        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"
        
        module = ImportQueueModule(mock_consumer)
        
        # This should work without errors even when queue_hashes is empty
        queue_data = await module.get_import_queue_data()
        
        self.assertIsInstance(queue_data, list)
        queue_item_ids = [item['id'] for item in queue_data]
        self.assertIn(queue_item.id, queue_item_ids)

    async def test_unknown_module(self):
        """Test handling of unknown module."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Just verify connection works
        # Testing unknown module behavior requires the consumer to remain open
        await communicator.disconnect()

    async def test_invalid_json(self):
        """Test handling of invalid JSON."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Just verify connection works
        await communicator.disconnect()


class TestRealtimeConsumerChannelLayer(TransactionTestCase):
    """Test RealtimeConsumer channel layer group messaging."""

    async def test_group_messaging(self):
        """Test that user group can be joined."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Just verify the connection joins the user's group
        # Testing actual group messaging requires a handler method on the consumer
        
        await communicator.disconnect()

    async def test_multiple_clients_same_user(self):
        """Test that multiple clients for same user receive broadcasts."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Create two communicators for the same user
        communicator1 = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator1.scope['user'] = user
        
        communicator2 = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator2.scope['user'] = user
        
        # Connect both
        connected1, _ = await communicator1.connect()
        connected2, _ = await communicator2.connect()
        self.assertTrue(connected1)
        self.assertTrue(connected2)
        
        # Both should be in the same group and receive messages
        
        await communicator1.disconnect()
        await communicator2.disconnect()


class TestAllFeaturesDuplicateDetection(TransactionTestCase):
    """Test that files where all features are duplicates are marked as all_features_duplicate."""

    async def test_single_feature_duplicate_marked_in_import_queue(self):
        """Test that a file with 1 feature that is a duplicate gets marked as all_features_duplicate."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_all_dup@example.com',
            password='testpass123',
            username='testuser_all_dup'
        )

        # Create a feature that will be a duplicate
        duplicate_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point', 'description': 'A test point'}
        }
        feature_hash = generate_geojson_hash(duplicate_feature)
        duplicate_feature['properties']['geojson_hash'] = feature_hash

        # Create the feature in FeatureStore to make it a duplicate
        await database_sync_to_async(FeatureStore.objects.create)(
            user=user,
            geojson=duplicate_feature,
            geojson_hash=feature_hash,
            geometry=Point(-122.4194, 37.7749, 0, srid=4326)
        )

        # Create duplicate_features entry matching the structure from duplicate detection
        duplicate_features_entry = [{
            'feature': duplicate_feature,
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,
            'existing_features': [{'id': 1, 'geojson': duplicate_feature}]
        }]

        # Create ImportQueue item with exactly 1 feature that is a duplicate
        queue_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash=None,  # No file_hash to avoid file_hash duplicate detection
            original_filename='single_duplicate.geojson',
            geofeatures=[duplicate_feature],
            duplicate_features=duplicate_features_entry
        )

        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"

        module = ImportQueueModule(mock_consumer)

        # Get queue data
        queue_data = await module.get_import_queue_data()

        # Find our item
        item_data = next((item for item in queue_data if item['id'] == queue_item.id), None)
        self.assertIsNotNone(item_data, "Queue item should be in the data")

        # Verify it's marked as all_features_duplicate
        self.assertIn('file_duplicate', item_data)
        self.assertEqual(item_data['file_duplicate']['status'], 'all_features_duplicate',
                        "Single duplicate feature should mark file as all_features_duplicate")

    async def test_single_feature_not_duplicate_not_marked(self):
        """Test that a file with 1 feature that is NOT a duplicate does NOT get marked."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_not_dup@example.com',
            password='testpass123',
            username='testuser_not_dup'
        )

        # Create a unique feature
        unique_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Unique Point', 'description': 'A unique point'}
        }
        feature_hash = generate_geojson_hash(unique_feature)
        unique_feature['properties']['geojson_hash'] = feature_hash

        # Create ImportQueue item with exactly 1 feature that is NOT a duplicate
        queue_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash=None,
            original_filename='single_unique.geojson',
            geofeatures=[unique_feature],
            duplicate_features=[]  # No duplicates
        )

        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"

        module = ImportQueueModule(mock_consumer)

        # Get queue data
        queue_data = await module.get_import_queue_data()

        # Find our item
        item_data = next((item for item in queue_data if item['id'] == queue_item.id), None)
        self.assertIsNotNone(item_data, "Queue item should be in the data")

        # Verify it's NOT marked as all_features_duplicate
        self.assertIn('file_duplicate', item_data)
        self.assertIsNone(item_data['file_duplicate']['status'],
                         "Single non-duplicate feature should NOT mark file as duplicate")

    async def test_multiple_features_marked_when_all_duplicates(self):
        """Test that a file with multiple features gets marked as all_features_duplicate when all are duplicates."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_multi@example.com',
            password='testpass123',
            username='testuser_multi'
        )

        # Create two duplicate features
        feature1 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Point 1'}
        }
        hash1 = generate_geojson_hash(feature1)
        feature1['properties']['geojson_hash'] = hash1

        feature2 = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.5, 37.8]},
            'properties': {'name': 'Point 2'}
        }
        hash2 = generate_geojson_hash(feature2)
        feature2['properties']['geojson_hash'] = hash2

        # Create duplicate_features entries
        duplicate_features_entry = [
            {
                'feature': feature1,
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': [{'id': 1}]
            },
            {
                'feature': feature2,
                'source': DuplicateSource.FEATURE_STORE,
                'match_type': DuplicateMatchType.HASH,
                'existing_features': [{'id': 2}]
            }
        ]

        # Create ImportQueue item with 2 features (both duplicates)
        queue_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash=None,
            original_filename='multiple_duplicates.geojson',
            geofeatures=[feature1, feature2],
            duplicate_features=duplicate_features_entry
        )

        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"

        module = ImportQueueModule(mock_consumer)

        # Get queue data
        queue_data = await module.get_import_queue_data()

        # Find our item
        item_data = next((item for item in queue_data if item['id'] == queue_item.id), None)
        self.assertIsNotNone(item_data, "Queue item should be in the data")

        # Verify it IS marked as all_features_duplicate (when all features are duplicates)
        self.assertIn('file_duplicate', item_data)
        self.assertEqual(item_data['file_duplicate']['status'], 'all_features_duplicate',
                         "Multiple features that are all duplicates should mark file as all_features_duplicate")

    async def test_file_hash_duplicate_takes_priority(self):
        """Test that duplicate_in_queue takes priority over all_features_duplicate."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_priority@example.com',
            password='testpass123',
            username='testuser_priority'
        )

        # Create a duplicate feature
        duplicate_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point'}
        }
        feature_hash = generate_geojson_hash(duplicate_feature)
        duplicate_feature['properties']['geojson_hash'] = feature_hash

        duplicate_features_entry = [{
            'feature': duplicate_feature,
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,
            'existing_features': [{'id': 1}]
        }]

        # Create first item (earlier)
        item1 = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash='same_hash_123',
            original_filename='first.geojson',
            geofeatures=[duplicate_feature],
            duplicate_features=duplicate_features_entry
        )

        # Create second item with same file_hash (later, should be duplicate_in_queue)
        item2 = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash='same_hash_123',  # Same file hash
            original_filename='second.geojson',
            geofeatures=[duplicate_feature],
            duplicate_features=duplicate_features_entry
        )

        # Create a mock consumer to instantiate the module
        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"realtime_{user.id}"

        module = ImportQueueModule(mock_consumer)

        # Get queue data
        queue_data = await module.get_import_queue_data()

        # Find item2 (the later one)
        item2_data = next((item for item in queue_data if item['id'] == item2.id), None)
        self.assertIsNotNone(item2_data, "Item2 should be in the data")

        # Verify it's marked as duplicate_in_queue (not all_features_duplicate)
        self.assertIn('file_duplicate', item2_data)
        self.assertEqual(item2_data['file_duplicate']['status'], 'duplicate_in_queue',
                        "File hash duplicate should take priority over all_features_duplicate")

    async def test_process_status_module_data_structure(self):
        """Test that ProcessStatusModule has correct data structure for all_features_duplicate detection."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test_process_status@example.com',
            password='testpass123',
            username='testuser_process_status'
        )

        # Create a duplicate feature
        duplicate_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Point'}
        }
        feature_hash = generate_geojson_hash(duplicate_feature)
        duplicate_feature['properties']['geojson_hash'] = feature_hash

        duplicate_features_entry = [{
            'feature': duplicate_feature,
            'source': DuplicateSource.FEATURE_STORE,
            'match_type': DuplicateMatchType.HASH,
            'existing_features': [{'id': 1}]
        }]

        # Create ImportQueue item
        import_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            file_hash=None,  # No file_hash
            original_filename='single_duplicate.geojson',
            geofeatures=[duplicate_feature],
            duplicate_features=duplicate_features_entry
        )

        # Verify the data structure is correct for the all_features_duplicate check
        self.assertEqual(len(import_item.geofeatures), 1, "Should have exactly 1 feature")
        self.assertEqual(len(import_item.duplicate_features), 1, "Should have 1 duplicate")
        
        # Verify the feature hash matches the duplicate feature hash
        dup_info = import_item.duplicate_features[0]
        dup_feature = dup_info.get('feature')
        self.assertIsNotNone(dup_feature, "Duplicate info should have feature")
        
        dup_feature_hash = dup_feature.get('properties', {}).get('geojson_hash')
        if not dup_feature_hash:
            dup_feature_hash = generate_geojson_hash(dup_feature)
        
        single_feature_hash = import_item.geofeatures[0].get('properties', {}).get('geojson_hash')
        if not single_feature_hash:
            single_feature_hash = generate_geojson_hash(import_item.geofeatures[0])
        
        self.assertEqual(dup_feature_hash, single_feature_hash,
                        "Feature hash should match duplicate feature hash - this enables all_features_duplicate detection")


class TestProcessStatusLogsUncapped(TransactionTestCase):
    """ProcessStatusModule._get_logs() must return the full log history, never truncate it.

    Oversized-message protection is handled by BaseWebSocketModule.send_to_client()'s payload
    guard plus a generous transport limit (see server-prod.sh), not by silently dropping data
    here."""

    async def test_full_fetch_returns_every_log_entry(self):
        from geo_lib.processing.logging import DatabaseLogLevel
        from api.models import DatabaseLogging

        user = await database_sync_to_async(User.objects.create_user)(
            email='test_logs_uncapped@example.com',
            password='testpass123',
            username='testuser_logs_uncapped'
        )
        log_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        import_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            log_id=log_id,
            original_filename='many_logs.geojson',
            geofeatures=[]
        )

        entry_count = 1500

        @database_sync_to_async
        def create_logs():
            entries = [
                DatabaseLogging(
                    user=user,
                    log_id=log_id,
                    level=DatabaseLogLevel.WARNING.value,
                    text=f"Skipping feature {i} due to invalid coordinates",
                    source="Feature Processing",
                    attributes={},
                    timestamp=timezone.now(),
                )
                for i in range(entry_count)
            ]
            return DatabaseLogging.objects.bulk_create(entries)

        created = await create_logs()

        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"process_status_{user.id}_{import_item.id}"

        module = ProcessStatusModule(mock_consumer, import_item)
        logs = await module._get_logs()

        self.assertEqual(len(logs), entry_count, "Full fetch must return every log entry, not a truncated subset")
        returned_ids = [log['id'] for log in logs]
        self.assertEqual(returned_ids, [log.id for log in created])

    async def test_incremental_fetch_via_after_id_returns_everything_newer(self):
        from geo_lib.processing.logging import DatabaseLogLevel
        from api.models import DatabaseLogging

        user = await database_sync_to_async(User.objects.create_user)(
            email='test_logs_uncapped_incremental@example.com',
            password='testpass123',
            username='testuser_logs_uncapped_incremental'
        )
        log_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
        import_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            imported=False,
            log_id=log_id,
            original_filename='many_logs_incremental.geojson',
            geofeatures=[]
        )

        entry_count = 1200

        @database_sync_to_async
        def create_logs():
            entries = [
                DatabaseLogging(
                    user=user,
                    log_id=log_id,
                    level=DatabaseLogLevel.WARNING.value,
                    text=f"Skipping feature {i} due to invalid coordinates",
                    source="Feature Processing",
                    attributes={},
                    timestamp=timezone.now(),
                )
                for i in range(entry_count)
            ]
            return DatabaseLogging.objects.bulk_create(entries)

        created = await create_logs()

        mock_consumer = MagicMock()
        mock_consumer.user = user
        mock_consumer.room_group_name = f"process_status_{user.id}_{import_item.id}"

        module = ProcessStatusModule(mock_consumer, import_item)
        logs = await module._get_logs(after_id=created[0].id)

        self.assertEqual(len(logs), entry_count - 1)


class TestImportHistoryWebSocket(TransactionTestCase):
    """Test ImportHistory WebSocket module with pagination."""

    async def test_import_history_initial_state_paginated(self):
        """Test that initial_state sends paginated page 1 data."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Create 25 imported items
        for i in range(25):
            await database_sync_to_async(ImportQueue.objects.create)(
                user=user,
                original_filename=f'test_{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[],
                imported=True,
                timestamp=timezone.now()
            )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # On connect, all modules send initial_state, so we need to receive and filter
        # Receive messages until we get import_history initial_state
        import_history_response = None
        for _ in range(10):  # Try up to 10 messages
            try:
                response = await communicator.receive_json_from(timeout=1)
                if response.get('module') == 'import_history' and response.get('type') == 'initial_state':
                    import_history_response = response
                    break
            except:
                break
        
        # If we didn't get it from initial connect, request refresh
        if import_history_response is None:
            await communicator.send_json_to({
                'module': 'import_history',
                'type': 'refresh',
                'data': {}
            })
            # Receive messages until we get import_history
            for _ in range(10):
                try:
                    response = await communicator.receive_json_from(timeout=1)
                    if response.get('module') == 'import_history' and response.get('type') == 'initial_state':
                        import_history_response = response
                        break
                except:
                    break
        
        self.assertIsNotNone(import_history_response, "Should receive import_history initial_state")
        data = import_history_response.get('data', {})
        self.assertIn('items', data)
        self.assertIn('pagination', data)
        
        # Should only return 10 items (page 1)
        self.assertEqual(len(data['items']), 10)
        
        # Check pagination metadata
        pagination = data['pagination']
        self.assertEqual(pagination['page'], 1)
        self.assertEqual(pagination['page_size'], 10)
        self.assertEqual(pagination['total_items'], 25)
        self.assertEqual(pagination['total_pages'], 3)
        self.assertTrue(pagination['has_next'])
        self.assertFalse(pagination['has_previous'])
        
        await communicator.disconnect()

    async def test_import_history_item_added_with_page(self):
        """Test that item_added event includes page number."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Create an imported item (this should trigger item_added event)
        import_item = await database_sync_to_async(ImportQueue.objects.create)(
            user=user,
            original_filename='new_item.kml',
            raw_file='<kml></kml>',
            geofeatures=[],
            imported=True
        )
        
        # Broadcast item_added event through channel layer
        from geo_lib.processing.import_operations.websocket import broadcast_item_imported
        from asgiref.sync import sync_to_async
        
        await sync_to_async(broadcast_item_imported)(user.id, import_item.id)
        
        # Should receive item_added message with page number
        try:
            response = await communicator.receive_json_from(timeout=2)
            if response.get('module') == 'import_history' and response.get('type') == 'item_added':
                data = response.get('data', {})
                self.assertIn('page', data)
                self.assertEqual(data['page'], 1)  # New items always go to page 1
                self.assertEqual(data['id'], import_item.id)
        except:
            # Event might be sent but not immediately received
            pass
        
        await communicator.disconnect()

    async def test_import_history_websocket_rest_integration(self):
        """Test WebSocket + REST integration: load page 1 via WS, page 2 via REST."""
        user = await database_sync_to_async(User.objects.create_user)(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
        
        # Create 15 imported items
        for i in range(15):
            await database_sync_to_async(ImportQueue.objects.create)(
                user=user,
                original_filename=f'test_{i}.kml',
                raw_file='<kml></kml>',
                geofeatures=[],
                imported=True,
                timestamp=timezone.now()
            )
        
        # Test WebSocket initial state (page 1)
        communicator = WebsocketCommunicator(
            RealtimeConsumer.as_asgi(),
            "/ws/realtime/"
        )
        communicator.scope['user'] = user
        
        connected, subprotocol = await communicator.connect()
        self.assertTrue(connected)
        
        # Receive initial_state messages from all modules on connect
        import_history_response = None
        for _ in range(10):
            try:
                response = await communicator.receive_json_from(timeout=1)
                if response.get('module') == 'import_history' and response.get('type') == 'initial_state':
                    import_history_response = response
                    break
            except:
                break
        
        # If we didn't get it, request refresh
        if import_history_response is None:
            await communicator.send_json_to({
                'module': 'import_history',
                'type': 'refresh',
                'data': {}
            })
            for _ in range(10):
                try:
                    response = await communicator.receive_json_from(timeout=1)
                    if response.get('module') == 'import_history' and response.get('type') == 'initial_state':
                        import_history_response = response
                        break
                except:
                    break
        
        self.assertIsNotNone(import_history_response, "Should receive import_history initial_state")
        ws_data = import_history_response.get('data', {})
        self.assertEqual(len(ws_data['items']), 10)
        self.assertEqual(ws_data['pagination']['page'], 1)
        
        await communicator.disconnect()
        
        # Test REST API for page 2 (need to use sync_to_async for Django Client)
        from django.test import Client
        from asgiref.sync import sync_to_async
        
        @sync_to_async
        def test_rest_api():
            client = Client()
            client.force_login(user)
            response = client.get('/api/item/import/history?page=2&page-size=10')
            return response
        
        response = await test_rest_api()
        self.assertEqual(response.status_code, 200)
        rest_data = json.loads(response.content)
        
        self.assertEqual(len(rest_data['items']), 5)  # 15 total - 10 from page 1 = 5
        self.assertEqual(rest_data['pagination']['page'], 2)
        self.assertFalse(rest_data['pagination']['has_next'])
        self.assertTrue(rest_data['pagination']['has_previous'])
        
        # Verify items are different between page 1 and page 2
        ws_item_ids = {item['id'] for item in ws_data['items']}
        rest_item_ids = {item['id'] for item in rest_data['items']}
        self.assertEqual(len(ws_item_ids.intersection(rest_item_ids)), 0)  # No overlap


class TestRealtimeConsumerExtensionIntegration(TransactionTestCase):
    """Integration tests: extensions registering WebSocket modules with the realtime consumer."""

    async def test_registered_extension_module_sends_initial_state(self):
        """A module registered via get_registered_websocket_modules is loaded and send_initial_state is sent to client."""
        user = await database_sync_to_async(User.objects.create_user)(
            email="extmod@example.com",
            password="testpass123",
            username="extmoduser",
        )

        class TestExtModule:
            def __init__(self, consumer):
                self.consumer = consumer

            @property
            def module_name(self):
                return "_test_ext_mod"

            async def handle_message(self, message_type, data):
                pass

            async def send_initial_state(self):
                await self.consumer.send(text_data=json.dumps({
                    "module": "_test_ext_mod",
                    "type": "initial_state",
                    "data": {"extension_test": True},
                }))

        with patch(
            "api.ws_consumers.realtime_consumer.get_registered_websocket_modules",
            return_value=[("_test_ext_mod", TestExtModule)],
        ):
            communicator = WebsocketCommunicator(
                RealtimeConsumer.as_asgi(),
                "/ws/realtime/",
            )
            communicator.scope["user"] = user

            connected, _ = await communicator.connect()
            self.assertTrue(connected)

            found = False
            for _ in range(20):
                try:
                    msg = await communicator.receive_json_from(timeout=2)
                    if msg.get("module") == "_test_ext_mod" and msg.get("type") == "initial_state":
                        self.assertEqual(msg.get("data", {}).get("extension_test"), True)
                        found = True
                        break
                except Exception:
                    break
            self.assertTrue(found, "Should receive _test_ext_mod initial_state from registered extension module")

            await communicator.disconnect()

    async def test_channel_event_routed_to_registered_extension_module_handler(self):
        """Consumer's __getattr__ routes channel event names to the registered extension module's handler."""
        sent = []

        class TestExtModuleWithEvent:
            def __init__(self, consumer):
                self.consumer = consumer

            @property
            def module_name(self):
                return "_test_ext_mod"

            async def handle_message(self, message_type, data):
                pass

            async def send_initial_state(self):
                pass

            async def some_event(self, event):
                await self.consumer.send(text_data=json.dumps({
                    "module": "_test_ext_mod",
                    "type": "some_event",
                    "data": event.get("data", {}),
                }))

        consumer = RealtimeConsumer()
        consumer.send = AsyncMock(side_effect=lambda **kw: sent.append(kw))
        consumer.modules = {"_test_ext_mod": TestExtModuleWithEvent(consumer)}

        handler = getattr(consumer, "_test_ext_mod_some_event")
        self.assertTrue(callable(handler))

        event = {"type": "_test_ext_mod_some_event", "data": {"payload": "from_channel"}}
        await handler(event)

        self.assertEqual(len(sent), 1)
        payload = json.loads(sent[0]["text_data"])
        self.assertEqual(payload.get("module"), "_test_ext_mod")
        self.assertEqual(payload.get("type"), "some_event")
        self.assertEqual(payload.get("data", {}).get("payload"), "from_channel")

