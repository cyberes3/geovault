"""
Tests for concurrent updates and race conditions.
"""
import json
import threading
import time
from django.test import TransactionTestCase
from django.contrib.gis.geos import Point

from api.models import FeatureStore, Collection
from geo_lib.feature_id import generate_feature_hash


class TestConcurrentFeatureEdits(TransactionTestCase):
    """Test concurrent edits to the same feature."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create a test feature
        self.feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Original Name',
                'description': 'Original description'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=self.feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(self.feature_data)
        )

    def test_concurrent_metadata_updates(self):
        """Test concurrent metadata updates to same feature."""
        from django.test import Client
        
        client1 = Client()
        client2 = Client()
        client1.force_login(self.user)
        client2.force_login(self.user)
        
        results = {'client1': None, 'client2': None}
        errors = []
        
        def update_name_client1():
            try:
                response = client1.put(
                    f'/api/feature/{self.feature.id}/update-metadata/',
                    data=json.dumps({'name': 'Name from Client 1'}),
                    content_type='application/json'
                )
                results['client1'] = response.status_code
            except Exception as e:
                errors.append(('client1', e))
        
        def update_name_client2():
            try:
                response = client2.put(
                    f'/api/feature/{self.feature.id}/update-metadata/',
                    data=json.dumps({'name': 'Name from Client 2'}),
                    content_type='application/json'
                )
                results['client2'] = response.status_code
            except Exception as e:
                errors.append(('client2', e))
        
        # Start concurrent updates
        thread1 = threading.Thread(target=update_name_client1)
        thread2 = threading.Thread(target=update_name_client2)
        
        thread1.start()
        thread2.start()
        
        thread1.join()
        thread2.join()
        
        # Both updates should succeed (last write wins)
        self.assertEqual(results['client1'], 200)
        self.assertEqual(results['client2'], 200)
        self.assertEqual(len(errors), 0)
        
        # Feature should have one of the names
        self.feature.refresh_from_db()
        self.assertIn(self.feature.geojson['properties']['name'], 
                     ['Name from Client 1', 'Name from Client 2'])

    def test_concurrent_geometry_updates(self):
        """Test concurrent geometry updates to same feature."""
        from django.test import Client
        
        client1 = Client()
        client2 = Client()
        client1.force_login(self.user)
        client2.force_login(self.user)
        
        results = {'client1': None, 'client2': None}
        
        def update_geometry_client1():
            geometry1 = {
                'type': 'Point',
                'coordinates': [-122.4094, 37.7849, 0.0]
            }
            response = client1.put(
                f'/api/feature/{self.feature.id}/update/',
                data=json.dumps(geometry1),
                content_type='application/json'
            )
            results['client1'] = response.status_code
        
        def update_geometry_client2():
            geometry2 = {
                'type': 'Point',
                'coordinates': [-122.3994, 37.7949, 0.0]
            }
            response = client2.put(
                f'/api/feature/{self.feature.id}/update/',
                data=json.dumps(geometry2),
                content_type='application/json'
            )
            results['client2'] = response.status_code
        
        thread1 = threading.Thread(target=update_geometry_client1)
        thread2 = threading.Thread(target=update_geometry_client2)
        
        thread1.start()
        thread2.start()
        
        thread1.join()
        thread2.join()
        
        # Both should succeed
        self.assertEqual(results['client1'], 200)
        self.assertEqual(results['client2'], 200)
        
        # Feature should have one of the geometries
        self.feature.refresh_from_db()
        coords = self.feature.geojson['geometry']['coordinates']
        self.assertIn(coords, [[-122.4094, 37.7849, 0.0], [-122.3994, 37.7949, 0.0]])


class TestConcurrentCollectionUpdates(TransactionTestCase):
    """Test concurrent updates to collections."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test collection
        self.collection = Collection.objects.create(
            user=self.user,
            name='Test Collection',
            tags=['test']
        )

    def test_concurrent_collection_updates(self):
        """Test concurrent updates to same collection."""
        from django.test import Client
        
        client1 = Client()
        client2 = Client()
        client1.force_login(self.user)
        client2.force_login(self.user)
        
        results = {'client1': None, 'client2': None}
        
        def update_collection_client1():
            update_data = {
                'name': 'Updated by Client 1',
                'description': 'Description from Client 1'
            }
            response = client1.put(
                f'/api/collections/{self.collection.id}/update/',
                data=json.dumps(update_data),
                content_type='application/json'
            )
            results['client1'] = response.status_code
        
        def update_collection_client2():
            update_data = {
                'name': 'Updated by Client 2',
                'description': 'Description from Client 2'
            }
            response = client2.put(
                f'/api/collections/{self.collection.id}/update/',
                data=json.dumps(update_data),
                content_type='application/json'
            )
            results['client2'] = response.status_code
        
        thread1 = threading.Thread(target=update_collection_client1)
        thread2 = threading.Thread(target=update_collection_client2)
        
        thread1.start()
        thread2.start()
        
        thread1.join()
        thread2.join()
        
        # Both should succeed
        self.assertEqual(results['client1'], 200)
        self.assertEqual(results['client2'], 200)
        
        # Collection should have one of the names
        self.collection.refresh_from_db()
        self.assertIn(self.collection.name, 
                     ['Updated by Client 1', 'Updated by Client 2'])


class TestConcurrentBulkOperations(TransactionTestCase):
    """Test concurrent bulk operations."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create multiple test features
        self.features = []
        for i in range(5):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749 + i * 0.01, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': ['bulk-test']
                }
            }
            feature = FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(feature_data['geometry']['coordinates'][0],
                             feature_data['geometry']['coordinates'][1],
                             0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            self.features.append(feature)

    def test_concurrent_bulk_metadata_updates(self):
        """Test concurrent bulk metadata updates."""
        from django.test import Client
        
        client1 = Client()
        client2 = Client()
        client1.force_login(self.user)
        client2.force_login(self.user)
        
        results = {'client1': None, 'client2': None}
        
        def bulk_update_client1():
            updates = [
                {'feature_id': f.id, 'tags': ['client1-tag']}
                for f in self.features
            ]
            response = client1.post(
                '/api/features/bulk-update-metadata/',
                data=json.dumps({'updates': updates}),
                content_type='application/json'
            )
            results['client1'] = response.status_code
        
        def bulk_update_client2():
            updates = [
                {'feature_id': f.id, 'tags': ['client2-tag']}
                for f in self.features
            ]
            response = client2.post(
                '/api/features/bulk-update-metadata/',
                data=json.dumps({'updates': updates}),
                content_type='application/json'
            )
            results['client2'] = response.status_code
        
        thread1 = threading.Thread(target=bulk_update_client1)
        thread2 = threading.Thread(target=bulk_update_client2)
        
        thread1.start()
        thread2.start()
        
        thread1.join()
        thread2.join()
        
        # Both should succeed
        self.assertEqual(results['client1'], 200)
        self.assertEqual(results['client2'], 200)
        
        # Features should have tags from one of the clients
        for feature in self.features:
            feature.refresh_from_db()
            tags = feature.geojson['properties'].get('tags', [])
            # Should have tags from either client1 or client2
            self.assertTrue(
                'client1-tag' in tags or 'client2-tag' in tags
            )


class TestConcurrentFeatureCreation(TransactionTestCase):
    """Test concurrent feature creation (duplicate detection)."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_concurrent_identical_feature_creation(self):
        """Test creating identical features concurrently."""
        from django.test import Client
        from api.models import ImportQueue
        
        # Create import queue items with identical features
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Duplicate Point'
            }
        }
        
        item1 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test1.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature_data]
        )
        
        item2 = ImportQueue.objects.create(
            user=self.user,
            original_filename='test2.kml',
            raw_file='<kml></kml>',
            geofeatures=[feature_data]
        )
        
        # Note: Actual concurrent import would require async job system
        # This test verifies the database constraint works
        
        # Try to create the same feature twice
        from django.db import IntegrityError
        
        try:
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            
            # Try to create duplicate
            FeatureStore.objects.create(
                user=self.user,
                geojson=feature_data,
                geometry=Point(-122.4194, 37.7749, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            
            # Should not reach here
            self.fail("Should have raised IntegrityError for duplicate")
        except IntegrityError:
            # Expected - unique constraint should prevent duplicates
            pass


class TestConcurrentDelete(TransactionTestCase):
    """Test concurrent delete operations."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'To Delete'
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

    def test_concurrent_feature_delete(self):
        """Test deleting same feature from multiple clients."""
        from django.test import Client
        
        client1 = Client()
        client2 = Client()
        client1.force_login(self.user)
        client2.force_login(self.user)
        
        results = {'client1': None, 'client2': None}
        
        def delete_client1():
            response = client1.delete(f'/api/feature/{self.feature.id}/delete/')
            results['client1'] = response.status_code
        
        def delete_client2():
            # Small delay to ensure client1 goes first
            time.sleep(0.01)
            response = client2.delete(f'/api/feature/{self.feature.id}/delete/')
            results['client2'] = response.status_code
        
        thread1 = threading.Thread(target=delete_client1)
        thread2 = threading.Thread(target=delete_client2)
        
        thread1.start()
        thread2.start()
        
        thread1.join()
        thread2.join()
        
        # First delete should succeed, second should fail (404)
        self.assertIn(results['client1'], [200, 404])
        self.assertIn(results['client2'], [200, 404])
        
        # At least one should succeed
        self.assertTrue(results['client1'] == 200 or results['client2'] == 200)
        
        # Feature should be deleted
        self.assertFalse(FeatureStore.objects.filter(id=self.feature.id).exists())


class TestReadWriteConsistency(TransactionTestCase):
    """Test read-write consistency."""

    def setUp(self):
        """Set up test fixtures."""
        from django.contrib.auth import get_user_model
        User = get_user_model()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

        # Create test feature
        feature_data = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Consistency Test',
                'counter': 0
            }
        }
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_feature_hash(feature_data)
        )

    def test_read_after_write_consistency(self):
        """Test that reads reflect writes immediately."""
        from django.test import Client
        
        client = Client()
        client.force_login(self.user)
        
        # Update feature
        update_data = {
            'name': 'Updated Name'
        }
        response = client.put(
            f'/api/feature/{self.feature.id}/update-metadata/',
            data=json.dumps(update_data),
            content_type='application/json'
        )
        self.assertEqual(response.status_code, 200)
        
        # Immediately read feature
        response = client.get(f'/api/feature/{self.feature.id}/')
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        
        # Should see the update
        self.assertEqual(data['feature']['geojson']['properties']['name'], 'Updated Name')

