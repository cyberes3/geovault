"""
Tests for concurrent operations and race condition prevention.

These tests verify that the application correctly handles concurrent operations
using threading, advisory locks, and database transactions.
"""
import pytest
import threading
import time
from unittest.mock import patch, MagicMock
from django.test import Client
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.db import transaction, connection, close_old_connections

from api.models import FeatureStore, ImportQueue, Collection, CollectionShare
from geo_lib.feature_id import generate_feature_hash
from geo_lib.utils.advisory_locks import advisory_lock
from geo_lib.processing.status_tracker import status_tracker

User = get_user_model()


@pytest.mark.django_db(transaction=True)
class TestConcurrentFileUploads:
    """Test concurrent file upload operations."""
    
    def test_concurrent_identical_file_uploads(self, user):
        """Test that advisory locks prevent race conditions with identical file uploads."""
        test_hash = "concurrent_test_hash_identical_12345"
        results = []
        errors = []
        user_id = user.id  # Store user ID to avoid cross-thread issues
        
        def upload_with_lock(worker_id):
            """Simulate file upload with advisory lock."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with advisory_lock(test_hash):
                    # Check if hash already exists
                    existing = ImportQueue.objects.filter(
                        user_id=user_id,
                        geojson_hash=test_hash
                    ).first()
                    
                    if existing:
                        results.append(f"worker_{worker_id}_found_duplicate")
                    else:
                        # Small delay to increase chance of race condition without lock
                        time.sleep(0.01)
                        # Save new entry using user_id instead of user object
                        ImportQueue.objects.create(
                            user_id=user_id,
                            original_filename=f"test_file_{worker_id}.kml",
                            raw_file="<kml>test content</kml>",
                            geojson_hash=test_hash,
                            geofeatures=[]
                        )
                        results.append(f"worker_{worker_id}_saved_new")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 3 threads trying to save the same hash
        threads = []
        for i in range(1, 4):
            thread = threading.Thread(target=upload_with_lock, args=(i,))
            threads.append(thread)
            thread.start()
        
        # Wait for all threads to complete
        for thread in threads:
            thread.join()
        
        # Verify no errors occurred
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify exactly one worker saved, others found duplicate
        saved_count = len([r for r in results if "saved_new" in r])
        duplicate_count = len([r for r in results if "found_duplicate" in r])
        
        assert saved_count == 1, f"Expected 1 save, got {saved_count}: {results}"
        assert duplicate_count == 2, f"Expected 2 duplicates, got {duplicate_count}: {results}"
        
        # Verify only one entry exists in database
        entries = ImportQueue.objects.filter(user_id=user_id, geojson_hash=test_hash)
        assert entries.count() == 1

    def test_concurrent_different_file_uploads(self, user):
        """Test that different files can be uploaded concurrently without blocking."""
        results = []
        errors = []
        user_id = user.id  # Store user ID
        
        def upload_file(worker_id):
            """Upload a unique file."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                test_hash = f"concurrent_test_hash_different_{worker_id}"
                ImportQueue.objects.create(
                    user_id=user_id,
                    original_filename=f"test_file_{worker_id}.kml",
                    raw_file=f"<kml>test content {worker_id}</kml>",
                    geojson_hash=test_hash,
                    geofeatures=[]
                )
                results.append(f"worker_{worker_id}_success")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 5 threads uploading different files
        threads = []
        for i in range(1, 6):
            thread = threading.Thread(target=upload_file, args=(i,))
            threads.append(thread)
            thread.start()
        
        # Wait for all threads to complete
        for thread in threads:
            thread.join()
        
        # Verify no errors and all succeeded
        assert len(errors) == 0, f"Errors occurred: {errors}"
        assert len(results) == 5, f"Expected 5 successes, got {len(results)}"
        
        # Verify all 5 entries exist
        for i in range(1, 6):
            test_hash = f"concurrent_test_hash_different_{i}"
            assert ImportQueue.objects.filter(user_id=user_id, geojson_hash=test_hash).exists()


@pytest.mark.django_db(transaction=True)
class TestConcurrentFeatureOperations:
    """Test concurrent feature store operations."""
    
    def test_concurrent_feature_creation_with_duplicate_detection(self, user):
        """Test that concurrent feature creation correctly detects duplicates."""
        # Create a test feature that will be duplicated
        base_feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Duplicate Test Point',
                'tags': ['test']
            }
        }
        base_hash = generate_feature_hash(base_feature)
        user_id = user.id  # Store user ID
        
        results = []
        errors = []
        
        def create_feature(worker_id):
            """Try to create a feature with the same hash."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                # Check if already exists
                existing = FeatureStore.objects.filter(
                    user_id=user_id,
                    geojson_hash=base_hash
                ).first()
                
                if existing:
                    results.append(f"worker_{worker_id}_found_existing")
                else:
                    # Create feature
                    feature = FeatureStore.objects.create(
                        user_id=user_id,
                        geojson=base_feature,
                        geometry=Point(-122.4194, 37.7749, 0.0),
                        geojson_hash=base_hash
                    )
                    results.append(f"worker_{worker_id}_created_{feature.id}")
            except Exception as e:
                # Race condition: another thread created it between our check and create
                # This is expected behavior without advisory locks
                error_msg = str(e)
                if "duplicate key value violates unique constraint" in error_msg:
                    results.append(f"worker_{worker_id}_duplicate_prevented")
                else:
                    errors.append(f"worker_{worker_id}: {error_msg}")
        
        # Start 4 threads trying to create the same feature
        threads = []
        for i in range(1, 5):
            thread = threading.Thread(target=create_feature, args=(i,))
            threads.append(thread)
            thread.start()
            # Small stagger to increase race condition likelihood
            time.sleep(0.001)
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no unexpected errors occurred
        assert len(errors) == 0, f"Unexpected errors occurred: {errors}"
        
        # Verify exactly one feature was created (either one succeeded or all hit duplicates)
        features = FeatureStore.objects.filter(user_id=user_id, geojson_hash=base_hash)
        assert features.count() == 1, f"Expected exactly 1 feature, found {features.count()}"
        
        # Verify that all threads either created, found existing, or were prevented by duplicate constraint
        assert len(results) == 4, f"Expected 4 results, got {len(results)}: {results}"

    def test_concurrent_feature_updates(self, user, feature_store):
        """Test concurrent updates to the same feature."""
        feature_id = feature_store.id
        results = []
        errors = []
        
        def update_feature(worker_id, new_name):
            """Update feature name."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with transaction.atomic():
                    feature = FeatureStore.objects.select_for_update().get(id=feature_id)
                    feature.geojson['properties']['name'] = new_name
                    feature.save()
                    results.append(f"worker_{worker_id}_updated_to_{new_name}")
            except FeatureStore.DoesNotExist:
                errors.append(f"worker_{worker_id}: FeatureStore matching query does not exist.")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 3 threads updating the same feature
        threads = []
        names = ["Name_A", "Name_B", "Name_C"]
        for i, name in enumerate(names, 1):
            thread = threading.Thread(target=update_feature, args=(i, name))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        assert len(results) == 3
        
        # Verify feature has one of the names
        feature = FeatureStore.objects.get(id=feature_id)
        assert feature.geojson['properties']['name'] in names

    def test_concurrent_feature_deletion(self, user):
        """Test concurrent deletion of features."""
        user_id = user.id  # Store user ID
        # Create 5 features
        features = []
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            feature = FeatureStore.objects.create(
                user_id=user_id,
                geojson=feature_data,
                geometry=Point(-122.4194 + i * 0.01, 37.7749, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            features.append(feature.id)
        
        results = []
        errors = []
        
        def delete_feature(worker_id, feature_id):
            """Delete a feature."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with transaction.atomic():
                    FeatureStore.objects.filter(id=feature_id, user_id=user_id).delete()
                    results.append(f"worker_{worker_id}_deleted_{feature_id}")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 5 threads deleting different features
        threads = []
        for i, feature_id in enumerate(features, 1):
            thread = threading.Thread(target=delete_feature, args=(i, feature_id))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        assert len(results) == 5
        
        # Verify all features are deleted
        remaining = FeatureStore.objects.filter(id__in=features).count()
        assert remaining == 0


@pytest.mark.django_db(transaction=True)
class TestConcurrentCollectionOperations:
    """Test concurrent collection operations."""
    
    def test_concurrent_collection_updates_add_features(self, user, collection):
        """Test concurrent addition of features to a collection."""
        user_id = user.id  # Store user ID
        # Create features to add
        feature_ids = []
        for i in range(6):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            feature = FeatureStore.objects.create(
                user_id=user_id,
                geojson=feature_data,
                geometry=Point(-122.4194 + i * 0.01, 37.7749, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            feature_ids.append(feature.id)
        
        collection_id = collection.id
        results = []
        errors = []
        
        def add_feature_to_collection(worker_id, feature_id):
            """Add a feature to the collection."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with transaction.atomic():
                    coll = Collection.objects.select_for_update().get(id=collection_id)
                    if feature_id not in coll.feature_ids:
                        coll.feature_ids.append(feature_id)
                        coll.save()
                    results.append(f"worker_{worker_id}_added_{feature_id}")
            except Collection.DoesNotExist:
                errors.append(f"worker_{worker_id}: Collection matching query does not exist.")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 6 threads adding different features
        threads = []
        for i, feature_id in enumerate(feature_ids, 1):
            thread = threading.Thread(target=add_feature_to_collection, args=(i, feature_id))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify all features were added
        collection.refresh_from_db()
        assert len(collection.feature_ids) == 6
        for feature_id in feature_ids:
            assert feature_id in collection.feature_ids

    def test_concurrent_collection_updates_same_feature(self, user, collection):
        """Test concurrent attempts to add the same feature to a collection."""
        user_id = user.id  # Store user ID
        # Create a feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        feature = FeatureStore.objects.create(
            user_id=user_id,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )
        
        collection_id = collection.id
        feature_id = feature.id
        results = []
        errors = []
        
        def add_feature_to_collection(worker_id):
            """Try to add the same feature to the collection."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with transaction.atomic():
                    coll = Collection.objects.select_for_update().get(id=collection_id)
                    if feature_id not in coll.feature_ids:
                        coll.feature_ids.append(feature_id)
                        coll.save()
                        results.append(f"worker_{worker_id}_added")
                    else:
                        results.append(f"worker_{worker_id}_already_present")
            except Collection.DoesNotExist:
                errors.append(f"worker_{worker_id}: Collection matching query does not exist.")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 4 threads trying to add the same feature
        threads = []
        for i in range(1, 5):
            thread = threading.Thread(target=add_feature_to_collection, args=(i,))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify feature appears only once in collection
        collection.refresh_from_db()
        assert collection.feature_ids.count(feature_id) == 1

    def test_concurrent_sharing_operations(self, concurrent_users, collection):
        """Test concurrent sharing of collections."""
        owner = collection.user
        collection_id = collection.id
        results = []
        errors = []
        
        def share_collection(worker_id, share_user):
            """Share collection with a user."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                # Check if already shared (using 'user' field, not 'shared_with')
                existing = CollectionShare.objects.filter(
                    collection_id=collection_id,
                    user=share_user
                ).first()
                
                if not existing:
                    CollectionShare.objects.create(
                        share_id=f"share_{collection_id}_{share_user.id}",
                        collection_id=collection_id,
                        user=share_user,
                        include_tags=False,
                        allow_downloads=False
                    )
                    results.append(f"worker_{worker_id}_shared")
                else:
                    results.append(f"worker_{worker_id}_already_shared")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start threads sharing with different users
        threads = []
        for i, share_user in enumerate(concurrent_users, 1):
            thread = threading.Thread(target=share_collection, args=(i, share_user))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify shares were created
        shares = CollectionShare.objects.filter(collection_id=collection_id)
        assert shares.count() == len(concurrent_users)


@pytest.mark.django_db(transaction=True)
class TestConcurrentImportProcessing:
    """Test concurrent import queue processing."""
    
    def test_concurrent_duplicate_detection(self, user):
        """Test that duplicate detection works correctly across concurrent imports."""
        user_id = user.id  # Store user ID
        # Create base features that will be imported
        base_features = []
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Import Feature {i}'}
            }
            base_features.append(feature_data)
        
        results = []
        errors = []
        lock = threading.Lock()
        existing_hashes = set()
        
        def process_import(worker_id):
            """Simulate processing an import with duplicate detection."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                for feature_data in base_features:
                    feature_hash = generate_feature_hash(feature_data)
                    
                    with lock:
                        if feature_hash in existing_hashes:
                            results.append(f"worker_{worker_id}_found_duplicate_{feature_hash[:8]}")
                            continue
                        else:
                            existing_hashes.add(feature_hash)
                    
                    # Simulate feature creation
                    FeatureStore.objects.create(
                        user_id=user_id,
                        geojson=feature_data,
                        geometry=Point(
                            feature_data['geometry']['coordinates'][0],
                            feature_data['geometry']['coordinates'][1],
                            0.0
                        ),
                        geojson_hash=feature_hash
                    )
                    results.append(f"worker_{worker_id}_created_{feature_hash[:8]}")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 3 threads processing imports
        threads = []
        for i in range(1, 4):
            thread = threading.Thread(target=process_import, args=(i,))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify each feature was created only once
        for feature_data in base_features:
            feature_hash = generate_feature_hash(feature_data)
            count = FeatureStore.objects.filter(user_id=user_id, geojson_hash=feature_hash).count()
            assert count == 1, f"Expected 1 feature with hash {feature_hash[:8]}, got {count}"

    def test_concurrent_import_queue_processing(self, user):
        """Test concurrent processing of multiple import queue items."""
        user_id = user.id  # Store user ID
        # Create 3 import queue items
        import_items = []
        for i in range(3):
            item = ImportQueue.objects.create(
                user_id=user_id,
                original_filename=f"test_file_{i}.kml",
                raw_file=f"<kml>content {i}</kml>",
                geojson_hash=f"hash_{i}",
                geofeatures=[]
            )
            import_items.append(item.id)
        
        results = []
        errors = []
        
        def process_item(worker_id, item_id):
            """Simulate processing an import queue item."""
            try:
                # Ensure database connection is available in this thread
                close_old_connections()
                with transaction.atomic():
                    item = ImportQueue.objects.select_for_update().get(id=item_id)
                    if not item.imported:
                        # Simulate processing
                        time.sleep(0.01)
                        item.imported = True
                        item.save()
                        results.append(f"worker_{worker_id}_processed_{item_id}")
                    else:
                        results.append(f"worker_{worker_id}_already_processed_{item_id}")
            except ImportQueue.DoesNotExist:
                errors.append(f"worker_{worker_id}: ImportQueue matching query does not exist.")
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 3 threads processing different items
        threads = []
        for i, item_id in enumerate(import_items, 1):
            thread = threading.Thread(target=process_item, args=(i, item_id))
            threads.append(thread)
            thread.start()
        
        # Wait for completion
        for thread in threads:
            thread.join()
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        assert len(results) == 3
        
        # Verify all items are marked as imported
        for item_id in import_items:
            item = ImportQueue.objects.get(id=item_id)
            assert item.imported is True


@pytest.mark.django_db(transaction=True)
class TestRedisProcessingLock:
    """Test RedisProcessingLock for sequential file processing."""
    
    def test_redis_lock_prevents_concurrent_processing(self, user):
        """Test that RedisProcessingLock serializes processing for same user."""
        from geo_lib.utils.redis_lock import RedisProcessingLock
        from geo_lib.processing.status_tracker import status_tracker
        import time
        
        user_id = user.id
        results = []
        errors = []
        
        def process_with_lock(worker_id):
            """Simulate file processing with Redis lock."""
            try:
                close_old_connections()
                job_id = f"test-job-{worker_id}"
                
                # Acquire lock
                with RedisProcessingLock(user_id, job_id, status_tracker):
                    # Record entry time
                    entry_time = time.time()
                    results.append({
                        'worker': worker_id,
                        'event': 'entered',
                        'time': entry_time
                    })
                    
                    # Simulate processing
                    time.sleep(0.5)
                    
                    # Record exit time
                    exit_time = time.time()
                    results.append({
                        'worker': worker_id,
                        'event': 'exited',
                        'time': exit_time
                    })
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start 3 threads trying to process simultaneously
        threads = []
        for i in range(1, 4):
            thread = threading.Thread(target=process_with_lock, args=(i,))
            threads.append(thread)
            thread.start()
        
        # Wait for all threads
        for thread in threads:
            thread.join(timeout=30)  # Allow time for sequential processing
        
        # Verify no errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Verify we have 6 events (3 enters, 3 exits)
        assert len(results) == 6, f"Expected 6 events, got {len(results)}"
        
        # Verify sequential processing: each worker should fully complete
        # before the next one starts (no overlapping)
        enters = [r for r in results if r['event'] == 'entered']
        exits = [r for r in results if r['event'] == 'exited']
        
        # Sort by time
        enters.sort(key=lambda x: x['time'])
        exits.sort(key=lambda x: x['time'])
        
        # Check that there's no overlap: each exit should come before next enter
        for i in range(len(exits) - 1):
            # Exit time of worker i should be before enter time of worker i+1
            # This proves sequential execution
            assert exits[i]['time'] <= enters[i+1]['time'], \
                f"Worker {exits[i]['worker']} overlapped with worker {enters[i+1]['worker']}"
    
    def test_redis_lock_released_after_processing(self, user):
        """Test that Redis lock is properly released after processing."""
        from geo_lib.utils.redis_lock import RedisProcessingLock
        from geo_lib.utils.redis_connection import get_redis_connection
        from geo_lib.processing.status_tracker import status_tracker
        
        user_id = user.id
        job_id = "test-release-job"
        lock_key = f"processing_lock:user:{user_id}"
        
        # Acquire and release lock
        with RedisProcessingLock(user_id, job_id, status_tracker):
            # Lock should be held
            redis_client = get_redis_connection()
            lock_value = redis_client.get(lock_key)
            assert lock_value is not None, "Lock should be held inside context"
        
        # Lock should be released
        redis_client = get_redis_connection()
        lock_value = redis_client.get(lock_key)
        assert lock_value is None, "Lock should be released after context exit"
    
    def test_redis_lock_timeout_handling(self, user):
        """Test that Redis lock handles timeout gracefully."""
        from geo_lib.utils.redis_lock import RedisProcessingLock
        from geo_lib.processing.status_tracker import status_tracker
        import time
        
        user_id = user.id
        errors = []
        
        def hold_lock_indefinitely():
            """Hold lock for a very long time."""
            try:
                close_old_connections()
                with RedisProcessingLock(user_id, "blocker-job", status_tracker):
                    # Hold lock for longer than second job's wait timeout
                    time.sleep(2.0)
            except Exception as e:
                errors.append(f"blocker: {str(e)}")
        
        def try_acquire_with_short_timeout():
            """Try to acquire lock with short timeout."""
            try:
                close_old_connections()
                # Patch the wait timeout to be very short for testing
                from geo_lib.utils import redis_lock
                original_timeout = redis_lock.RedisProcessingLock.WAIT_TIMEOUT
                redis_lock.RedisProcessingLock.WAIT_TIMEOUT = 1.0
                
                try:
                    with RedisProcessingLock(user_id, "waiter-job", status_tracker):
                        pass  # Should timeout before reaching here
                except TimeoutError as e:
                    errors.append(f"waiter: timeout as expected: {str(e)}")
                finally:
                    # Restore original timeout
                    redis_lock.RedisProcessingLock.WAIT_TIMEOUT = original_timeout
            except Exception as e:
                errors.append(f"waiter: unexpected error: {str(e)}")
        
        # Start blocker thread
        blocker_thread = threading.Thread(target=hold_lock_indefinitely)
        blocker_thread.start()
        
        # Give blocker time to acquire lock
        time.sleep(0.2)
        
        # Try to acquire lock with second thread (should timeout)
        waiter_thread = threading.Thread(target=try_acquire_with_short_timeout)
        waiter_thread.start()
        
        # Wait for both threads
        blocker_thread.join(timeout=5)
        waiter_thread.join(timeout=5)
        
        # Should have exactly 2 messages (1 from each thread)
        assert len(errors) == 2, f"Expected 2 messages, got {len(errors)}: {errors}"
        
        # Verify waiter got timeout
        waiter_messages = [e for e in errors if 'waiter' in e]
        assert len(waiter_messages) == 1
        assert 'timeout as expected' in waiter_messages[0].lower()
    
    def test_redis_lock_different_users_can_process_concurrently(self, user):
        """Test that different users can process files concurrently."""
        from django.contrib.auth import get_user_model
        from geo_lib.utils.redis_lock import RedisProcessingLock
        from geo_lib.processing.status_tracker import status_tracker
        import time
        
        User = get_user_model()
        user2 = User.objects.create_user(
            email='user2@example.com',
            password='testpass',
            username='user2'
        )
        
        results = []
        errors = []
        
        def process_for_user(user_id, worker_id):
            """Process with lock for specific user."""
            try:
                close_old_connections()
                job_id = f"test-job-{worker_id}"
                
                with RedisProcessingLock(user_id, job_id, status_tracker):
                    entry_time = time.time()
                    results.append({
                        'user': user_id,
                        'worker': worker_id,
                        'event': 'entered',
                        'time': entry_time
                    })
                    
                    time.sleep(0.3)
                    
                    exit_time = time.time()
                    results.append({
                        'user': user_id,
                        'worker': worker_id,
                        'event': 'exited',
                        'time': exit_time
                    })
            except Exception as e:
                errors.append(f"worker_{worker_id}: {str(e)}")
        
        # Start threads for both users
        thread1 = threading.Thread(target=process_for_user, args=(user.id, 1))
        thread2 = threading.Thread(target=process_for_user, args=(user2.id, 2))
        
        thread1.start()
        thread2.start()
        
        thread1.join(timeout=10)
        thread2.join(timeout=10)
        
        # No errors
        assert len(errors) == 0, f"Errors occurred: {errors}"
        
        # Both should have completed
        assert len(results) == 4
        
        # Check that both users' processing overlapped (concurrent)
        user1_entries = [r for r in results if r['user'] == user.id and r['event'] == 'entered']
        user1_exits = [r for r in results if r['user'] == user.id and r['event'] == 'exited']
        user2_entries = [r for r in results if r['user'] == user2.id and r['event'] == 'entered']
        user2_exits = [r for r in results if r['user'] == user2.id and r['event'] == 'exited']
        
        # At least one should have started before the other finished (proving concurrency)
        user1_enter = user1_entries[0]['time']
        user1_exit = user1_exits[0]['time']
        user2_enter = user2_entries[0]['time']
        user2_exit = user2_exits[0]['time']
        
        # Check for overlap: one user should enter before the other exits
        overlap = (user1_enter < user2_exit and user2_enter < user1_exit)
        assert overlap, "Different users should be able to process concurrently"

