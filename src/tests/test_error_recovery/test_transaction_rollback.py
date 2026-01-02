"""
Tests for database transaction rollback and failure scenarios.

These tests verify that the application correctly handles transaction failures
and rolls back changes to maintain data integrity.
"""
import threading
import time

import pytest
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.db import DatabaseError, IntegrityError, transaction
from django.test import TestCase
from unittest.mock import MagicMock, patch

from api.models import FeatureStore, ImportQueue, Collection
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.utils.advisory_locks import advisory_lock

User = get_user_model()


@pytest.mark.django_db
class TestTransactionRollback:
    """Test database transaction rollback scenarios."""
    
    def test_feature_creation_rollback_on_error(self, user):
        """Test that feature creation rolls back if transaction fails."""
        initial_count = FeatureStore.objects.filter(user=user).count()
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        
        # Simulate a transaction that should roll back
        with pytest.raises(Exception):
            with transaction.atomic():
                # Create a feature
                FeatureStore.objects.create(
                    user=user,
                    geojson=feature_data,
                    geometry=Point(-122.4194, 37.7749, 0.0),
                    geojson_hash=generate_geojson_hash(feature_data)
                )
                
                # Force an error to trigger rollback
                raise Exception("Simulated error to trigger rollback")
        
        # Verify no feature was created (rollback worked)
        final_count = FeatureStore.objects.filter(user=user).count()
        assert final_count == initial_count

    def test_collection_update_rollback_on_invalid_feature_ids(self, user, collection):
        """Test that collection update rolls back if feature_ids are invalid."""
        original_feature_ids = collection.feature_ids.copy()
        
        # Create a valid feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        valid_feature = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data)
        )
        
        # Try to update with valid and invalid feature IDs
        with pytest.raises(Exception):
            with transaction.atomic():
                collection.feature_ids.append(valid_feature.id)
                collection.feature_ids.append(999999)  # Invalid ID
                collection.save()
                
                # Verify the invalid feature doesn't exist
                if not FeatureStore.objects.filter(id=999999).exists():
                    raise Exception("Invalid feature ID detected")
        
        # Verify collection was not modified (rollback worked)
        collection.refresh_from_db()
        assert collection.feature_ids == original_feature_ids

    def test_bulk_delete_atomic_behavior(self, user):
        """Test that bulk delete is atomic - all or nothing."""
        # Create 3 features
        features = []
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194 + i * 0.01, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            features.append(feature)
        
        initial_count = FeatureStore.objects.filter(user=user).count()
        
        # Try to delete all features but simulate error on the last one
        with pytest.raises(Exception):
            with transaction.atomic():
                for i, feature in enumerate(features):
                    feature.delete()
                    if i == len(features) - 1:
                        # Simulate error on last delete
                        raise Exception("Simulated error during bulk delete")
        
        # Verify no features were deleted (rollback worked)
        final_count = FeatureStore.objects.filter(user=user).count()
        assert final_count == initial_count

    def test_import_failure_with_partial_feature_creation(self, user):
        """Test that import rolls back if it fails partway through."""
        initial_feature_count = FeatureStore.objects.filter(user=user).count()
        
        # Create an import queue item
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='test.kml',
            raw_file='<kml>test</kml>',
            geofeatures=[]
        )
        
        # Simulate processing that creates some features then fails
        with pytest.raises(Exception):
            with transaction.atomic():
                # Create first feature successfully
                feature1_data = {
                    'type': 'Feature',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [-122.4194, 37.7749, 0.0]
                    },
                    'properties': {'name': 'Feature 1'}
                }
                FeatureStore.objects.create(
                    user=user,
                    geojson=feature1_data,
                    geometry=Point(-122.4194, 37.7749, 0.0),
                    geojson_hash=generate_geojson_hash(feature1_data)
                )
                
                # Create second feature successfully
                feature2_data = {
                    'type': 'Feature',
                    'geometry': {
                        'type': 'Point',
                        'coordinates': [-122.4094, 37.7749, 0.0]
                    },
                    'properties': {'name': 'Feature 2'}
                }
                FeatureStore.objects.create(
                    user=user,
                    geojson=feature2_data,
                    geometry=Point(-122.4094, 37.7749, 0.0),
                    geojson_hash=generate_geojson_hash(feature2_data)
                )
                
                # Mark import as imported
                import_item.imported = True
                import_item.save()
                
                # Simulate failure
                raise Exception("Import processing failed")
        
        # Verify no features were created (rollback worked)
        final_feature_count = FeatureStore.objects.filter(user=user).count()
        assert final_feature_count == initial_feature_count
        
        # Verify import was not marked as imported
        import_item.refresh_from_db()
        assert import_item.imported is False

    def test_nested_transaction_rollback(self, user, collection):
        """Test that nested transactions roll back correctly."""
        original_feature_ids = collection.feature_ids.copy()
        initial_feature_count = FeatureStore.objects.filter(user=user).count()
        
        # Outer transaction that should roll back
        with pytest.raises(Exception):
            with transaction.atomic():
                # Inner transaction: create a feature
                with transaction.atomic():
                    feature_data = {
                        'type': 'Feature',
                        'geometry': {
                            'type': 'Point',
                            'coordinates': [-122.4194, 37.7749, 0.0]
                        },
                        'properties': {'name': 'Test Feature'}
                    }
                    feature = FeatureStore.objects.create(
                        user=user,
                        geojson=feature_data,
                        geometry=Point(-122.4194, 37.7749, 0.0),
                        geojson_hash=generate_geojson_hash(feature_data)
                    )
                
                # Add feature to collection (outer transaction)
                collection.feature_ids.append(feature.id)
                collection.save()
                
                # Simulate error in outer transaction
                raise Exception("Outer transaction error")
        
        # Verify feature was not created
        final_feature_count = FeatureStore.objects.filter(user=user).count()
        assert final_feature_count == initial_feature_count
        
        # Verify collection was not modified
        collection.refresh_from_db()
        assert collection.feature_ids == original_feature_ids


@pytest.mark.django_db
class TestAdvisoryLockFailureRecovery:
    """Test advisory lock failure and recovery scenarios."""
    
    def test_advisory_lock_acquisition_failure(self, user):
        """Test behavior when advisory lock acquisition fails."""
        test_hash = "lock_failure_test_hash"
        
        # Mock the cursor.execute to simulate lock failure
        # Patch connection where it's used in the advisory_locks module
        with patch('geo_lib.utils.advisory_locks.connection') as mock_connection:
            mock_cursor = MagicMock()
            mock_cursor.execute.side_effect = DatabaseError("Lock acquisition failed")
            mock_connection.cursor.return_value = mock_cursor
            
            # Verify that lock acquisition failure raises an exception
            with pytest.raises(DatabaseError):
                with advisory_lock(test_hash):
                    pass  # Should not reach here

    def test_lock_held_too_long_timeout(self, user):
        """Test behavior when a lock is held for an extended period."""
        
        test_hash = "long_lock_test_hash"
        results = []
        
        def hold_lock_long():
            """Hold a lock for a long time."""
            try:
                with advisory_lock(test_hash):
                    time.sleep(2)  # Hold for 2 seconds
                    results.append("holder_released")
            except Exception as e:
                results.append(f"holder_error: {str(e)}")
        
        def wait_for_lock():
            """Try to acquire the same lock."""
            try:
                # Small delay to ensure first thread acquires lock first
                time.sleep(0.1)
                start_time = time.time()
                with advisory_lock(test_hash):
                    elapsed = time.time() - start_time
                    results.append(f"waiter_acquired_after_{elapsed:.2f}s")
            except Exception as e:
                results.append(f"waiter_error: {str(e)}")
        
        # Start both threads
        holder = threading.Thread(target=hold_lock_long)
        waiter = threading.Thread(target=wait_for_lock)
        
        holder.start()
        waiter.start()
        
        # Wait for both to complete (with timeout)
        holder.join(timeout=3)
        waiter.join(timeout=3)
        
        # Verify both completed
        assert "holder_released" in results
        assert any("waiter_acquired" in r for r in results), f"Waiter should have acquired lock: {results}"


@pytest.mark.django_db
class TestDatabaseConstraintViolations:
    """Test handling of database constraint violations."""
    
    @pytest.mark.django_db(transaction=True)
    def test_unique_constraint_violation_handling(self, user):
        """Test handling of unique constraint violations."""
        # Create a feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        feature_hash = generate_geojson_hash(feature_data)
        
        feature1 = FeatureStore.objects.create(
            user=user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=feature_hash
        )
        
        # Note: The FeatureStore model DOES have a unique constraint on (user, geojson_hash)
        # This test documents that attempting to create a duplicate raises IntegrityError
        
        with pytest.raises(IntegrityError):
            with transaction.atomic():
                FeatureStore.objects.create(
                    user=user,
                    geojson=feature_data,
                    geometry=Point(-122.4194, 37.7749, 0.0),
                    geojson_hash=feature_hash
                )
        
        # Verify only one feature exists
        features = FeatureStore.objects.filter(user=user, geojson_hash=feature_hash)
        assert features.count() == 1

    @pytest.mark.django_db(transaction=True)
    def test_foreign_key_constraint_violation(self):
        """Test handling of foreign key constraint violations."""
        # Try to create a feature for a non-existent user
        # This should raise an IntegrityError
        
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {'name': 'Test Feature'}
        }
        
        # Use an invalid user_id - this should raise IntegrityError
        with pytest.raises(IntegrityError):
            with transaction.atomic():
                FeatureStore.objects.create(
                    user_id=999999,  # Non-existent user
                    geojson=feature_data,
                    geometry=Point(-122.4194, 37.7749, 0.0),
                    geojson_hash=generate_geojson_hash(feature_data)
                )


@pytest.mark.django_db
class TestPartialUpdateRollback:
    """Test rollback of partial updates in batch operations."""
    
    def test_bulk_metadata_update_rollback(self, user):
        """Test that bulk metadata updates roll back on error."""
        # Create 3 features
        features = []
        for i in range(3):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Original Name {i}', 'tags': ['original']}
            }
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(-122.4194 + i * 0.01, 37.7749, 0.0),
                geojson_hash=generate_geojson_hash(feature_data)
            )
            features.append(feature)
        
        # Store original names
        original_names = [f.geojson['properties']['name'] for f in features]
        
        # Try to update all features but fail on the last one
        with pytest.raises(Exception):
            with transaction.atomic():
                for i, feature in enumerate(features):
                    feature.geojson['properties']['name'] = f'Updated Name {i}'
                    feature.geojson['properties']['tags'] = ['updated']
                    feature.save()
                    
                    if i == len(features) - 1:
                        raise Exception("Simulated error during batch update")
        
        # Verify all features retained original names (rollback worked)
        for i, feature in enumerate(features):
            feature.refresh_from_db()
            assert feature.geojson['properties']['name'] == original_names[i]
            assert 'original' in feature.geojson['properties']['tags']
            assert 'updated' not in feature.geojson['properties']['tags']
