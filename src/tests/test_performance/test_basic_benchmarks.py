"""
Basic performance benchmarks for regression testing.

These tests measure performance of key operations to detect regressions.
They use reasonable thresholds and log performance metrics.
"""
import pytest
import time
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import Client

from api.models import FeatureStore, Collection, ImportQueue
from geo_lib.feature_id import generate_feature_hash

User = get_user_model()


@pytest.mark.performance
@pytest.mark.slow
@pytest.mark.django_db
class TestBulkOperationsPerformance:
    """Performance tests for bulk operations."""
    
    def test_feature_creation_100_features(self, user):
        """Test creating 100 features - baseline performance."""
        features = []
        for i in range(100):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': ['performance', 'test']
                }
            }
            features.append(feature_data)
        
        start_time = time.perf_counter()
        
        for feature_data in features:
            FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\n100 feature creation took {elapsed:.2f}s ({elapsed/100*1000:.2f}ms per feature)")
        
        # Reasonable threshold: should complete in under 30 seconds
        assert elapsed < 30.0, f"Feature creation took too long: {elapsed:.2f}s"
        
        # Verify all were created
        count = FeatureStore.objects.filter(user=user).count()
        assert count == 100

    def test_feature_creation_1000_features(self, user):
        """Test creating 1000 features - stress test."""
        features_to_create = []
        for i in range(1000):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + (i % 100) * 0.001, 37.7749 + (i // 100) * 0.001, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': ['performance', 'stress']
                }
            }
            
            features_to_create.append(FeatureStore(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            ))
        
        start_time = time.perf_counter()
        
        # Use bulk_create for better performance
        FeatureStore.objects.bulk_create(features_to_create, batch_size=100)
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\n1000 feature bulk creation took {elapsed:.2f}s ({elapsed/1000*1000:.2f}ms per feature)")
        
        # Reasonable threshold: should complete in under 60 seconds
        assert elapsed < 60.0, f"Bulk feature creation took too long: {elapsed:.2f}s"
        
        # Verify all were created
        count = FeatureStore.objects.filter(user=user).count()
        assert count == 1000

    def test_bulk_metadata_update_100_features(self, user):
        """Test updating metadata for 100 features."""
        # Create 100 features
        features = []
        for i in range(100):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {
                    'name': f'Original Name {i}',
                    'tags': ['original']
                }
            }
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            )
            features.append(feature)
        
        # Update all features
        start_time = time.perf_counter()
        
        for i, feature in enumerate(features):
            feature.geojson['properties']['name'] = f'Updated Name {i}'
            feature.geojson['properties']['tags'] = ['updated']
            feature.save()
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\n100 feature metadata updates took {elapsed:.2f}s ({elapsed/100*1000:.2f}ms per feature)")
        
        # Reasonable threshold: should complete in under 20 seconds
        assert elapsed < 20.0, f"Bulk update took too long: {elapsed:.2f}s"

    def test_duplicate_detection_100_against_1000(self, user):
        """Test duplicate detection: 100 new features against 1000 existing."""
        # Create 1000 existing features
        existing_hashes = set()
        for i in range(1000):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {'name': f'Existing {i}'}
            }
            feature_hash = generate_feature_hash(feature_data)
            existing_hashes.add(feature_hash)
            FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=feature_hash
            )
        
        # Create 100 new features to check for duplicates
        new_features = []
        for i in range(100):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.3194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {'name': f'New {i}'}
            }
            new_features.append(feature_data)
        
        # Test duplicate detection performance
        start_time = time.perf_counter()
        
        duplicates_found = 0
        for feature_data in new_features:
            feature_hash = generate_feature_hash(feature_data)
            if feature_hash in existing_hashes:
                duplicates_found += 1
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nDuplicate detection (100 vs 1000) took {elapsed:.2f}s")
        
        # Should be very fast with set lookup
        assert elapsed < 1.0, f"Duplicate detection took too long: {elapsed:.2f}s"

    def test_collection_add_100_features(self, user):
        """Test adding 100 features to a collection."""
        # Create collection
        collection = Collection.objects.create(
            user=user,
            name='Performance Test Collection',
            tags=['test'],
            feature_ids=[]
        )
        
        # Create 100 features
        feature_ids = []
        for i in range(100):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            feature = FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            )
            feature_ids.append(feature.id)
        
        # Add all features to collection
        start_time = time.perf_counter()
        
        collection.feature_ids = feature_ids
        collection.save()
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nAdding 100 features to collection took {elapsed:.2f}s")
        
        # Should be very fast
        assert elapsed < 2.0, f"Collection update took too long: {elapsed:.2f}s"


@pytest.mark.performance
@pytest.mark.slow
@pytest.mark.django_db
class TestQueryPerformance:
    """Performance tests for query operations."""
    
    def test_fetch_all_features_100(self, user):
        """Test fetching all features for user with 100 features."""
        # Create 100 features
        for i in range(100):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        # Test query performance
        start_time = time.perf_counter()
        
        features = list(FeatureStore.objects.filter(user=user))
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nFetching 100 features took {elapsed:.2f}s")
        
        assert len(features) == 100
        assert elapsed < 2.0, f"Query took too long: {elapsed:.2f}s"

    def test_fetch_all_features_1000(self, user):
        """Test fetching all features for user with 1000 features."""
        # Create 1000 features using bulk_create
        features_to_create = []
        for i in range(1000):
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + (i % 100) * 0.001, 37.7749 + (i // 100) * 0.001, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            features_to_create.append(FeatureStore(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            ))
        
        FeatureStore.objects.bulk_create(features_to_create, batch_size=100)
        
        # Test query performance
        start_time = time.perf_counter()
        
        features = list(FeatureStore.objects.filter(user=user))
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nFetching 1000 features took {elapsed:.2f}s")
        
        assert len(features) == 1000
        assert elapsed < 5.0, f"Query took too long: {elapsed:.2f}s"

    def test_bbox_query_with_100_results(self, user):
        """Test bounding box query returning ~100 features."""
        # Create 100 features in a bbox
        bbox_features = 0
        for i in range(200):
            lon = -122.5 + i * 0.001
            lat = 37.7 + (i % 10) * 0.01
            
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [lon, lat, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            }
            FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(lon, lat, 0.0),
                geojson_hash=generate_feature_hash(feature_data)
            )
            
            # Count features within target bbox
            if -122.45 <= lon <= -122.35 and 37.7 <= lat <= 37.8:
                bbox_features += 1
        
        # Test bbox query performance
        start_time = time.perf_counter()
        
        # Simplified bbox query (actual implementation may use PostGIS)
        features = FeatureStore.objects.filter(
            user=user,
            geometry__contained=Point(-122.40, 37.75, 0.0).buffer(0.1)
        )
        count = features.count()
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nBbox query (from 200 features) took {elapsed:.2f}s")
        
        assert elapsed < 2.0, f"Bbox query took too long: {elapsed:.2f}s"

    def test_feature_search_by_tags(self, user):
        """Test searching features by tags."""
        # Create features with various tags
        for i in range(100):
            tags = ['all']
            if i % 2 == 0:
                tags.append('even')
            if i % 3 == 0:
                tags.append('three')
            if i % 5 == 0:
                tags.append('five')
            
            feature_data = {
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.001, 37.7749, 0.0]
                },
                'properties': {
                    'name': f'Feature {i}',
                    'tags': tags
                }
            }
            FeatureStore.objects.create(
                user=user,
                geojson=feature_data,
                geometry=Point(
                    feature_data['geometry']['coordinates'][0],
                    feature_data['geometry']['coordinates'][1],
                    0.0
                ),
                geojson_hash=generate_feature_hash(feature_data)
            )
        
        # Test tag search performance
        start_time = time.perf_counter()
        
        # Search for features with 'even' tag
        # Note: Actual implementation may use JSONField lookups
        features = []
        for f in FeatureStore.objects.filter(user=user):
            if 'even' in f.geojson.get('properties', {}).get('tags', []):
                features.append(f)
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nTag search (from 100 features) took {elapsed:.2f}s, found {len(features)} results")
        
        assert len(features) == 50  # Half should be even
        assert elapsed < 2.0, f"Tag search took too long: {elapsed:.2f}s"


@pytest.mark.performance
@pytest.mark.slow
@pytest.mark.django_db
class TestImportPerformance:
    """Performance tests for import operations."""
    
    def test_import_small_file_10_features(self, user):
        """Test processing small import with 10 features."""
        # Create import queue item
        features = []
        for i in range(10):
            features.append({
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + i * 0.01, 37.7749, 0.0]
                },
                'properties': {'name': f'Feature {i}'}
            })
        
        start_time = time.perf_counter()
        
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='small.kml',
            raw_file='<kml>small file</kml>',
            geofeatures=features
        )
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nImport queue creation with 10 features took {elapsed:.2f}s")
        
        assert elapsed < 1.0, f"Small import took too long: {elapsed:.2f}s"
        assert len(import_item.geofeatures) == 10

    def test_import_medium_file_100_features(self, user):
        """Test processing medium import with 100 features."""
        # Create import queue item
        features = []
        for i in range(100):
            features.append({
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + (i % 10) * 0.01, 37.7749 + (i // 10) * 0.01, 0.0]
                },
                'properties': {'name': f'Feature {i}', 'tags': ['import', 'test']}
            })
        
        start_time = time.perf_counter()
        
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='medium.kml',
            raw_file='<kml>medium file</kml>',
            geofeatures=features
        )
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nImport queue creation with 100 features took {elapsed:.2f}s")
        
        assert elapsed < 5.0, f"Medium import took too long: {elapsed:.2f}s"
        assert len(import_item.geofeatures) == 100

    def test_import_large_file_1000_features(self, user):
        """Test processing large import with 1000 features."""
        # Create import queue item
        features = []
        for i in range(1000):
            features.append({
                'type': 'Feature',
                'geometry': {
                    'type': 'Point',
                    'coordinates': [-122.4194 + (i % 100) * 0.001, 37.7749 + (i // 100) * 0.01, 0.0]
                },
                'properties': {'name': f'Feature {i}', 'tags': ['import', 'large']}
            })
        
        start_time = time.perf_counter()
        
        import_item = ImportQueue.objects.create(
            user=user,
            original_filename='large.kml',
            raw_file='<kml>large file</kml>',
            geofeatures=features
        )
        
        elapsed = time.perf_counter() - start_time
        
        print(f"\nImport queue creation with 1000 features took {elapsed:.2f}s")
        
        assert elapsed < 30.0, f"Large import took too long: {elapsed:.2f}s"
        assert len(import_item.geofeatures) == 1000


