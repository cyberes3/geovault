"""
Tests for background geocoding functionality.
"""
import time
from unittest.mock import patch, MagicMock

import pytest
from django.test import TestCase, TransactionTestCase
from django.contrib.gis.geos import Point
from django.contrib.auth import get_user_model

from api.models import FeatureStore
from geo_lib.geolocation.background_geocoding import geocode_feature_async
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator
from geo_lib.types.feature import PointFeature
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


@pytest.mark.django_db(transaction=True)
class TestBackgroundGeocoding(TransactionTestCase):
    """Test background geocoding functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='geocoding@example.com',
            password='testpass123',
            username='geocodinguser'
        )
    
    def _create_test_feature(self, with_geocoding_tags=False):
        """Create a test feature for geocoding."""
        geojson = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749, 0.0]
            },
            'properties': {
                'name': 'Test Point',
                'geojson_hash': 'test_hash',
                'system_tags': ['type:point', 'quick-point'] + (['geo-city:San Francisco'] if with_geocoding_tags else [])
            }
        }
        
        feature_store = FeatureStore.objects.create(
            user=self.user,
            geojson=geojson,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(geojson)
        )
        return feature_store
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    def test_background_geocoding_adds_tags(self, mock_get_service, mock_setting):
        """Test that background geocoding adds tags to a feature."""
        # Enable geocoding
        mock_setting.return_value = True
        
        # Mock geocoding service to return tags
        mock_service = MagicMock()
        mock_service.get_location_tags.return_value = [
            'geo-city:San Francisco',
            'geo-state:California',
            'geo-country:United States'
        ]
        mock_get_service.return_value = mock_service
        
        # Create feature without geocoding tags
        feature_store = self._create_test_feature(with_geocoding_tags=False)
        
        # Start background geocoding
        geocode_feature_async(feature_store.id)
        
        # Wait for background thread to complete (with timeout)
        time.sleep(0.5)
        
        # Refresh from database
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        system_tags = geojson.get('properties', {}).get('system_tags', [])
        
        # Verify geocoding tags were added
        self.assertIn('geo-city:San Francisco', system_tags)
        self.assertIn('geo-state:California', system_tags)
        self.assertIn('geo-country:United States', system_tags)
        
        # Verify existing tags are preserved
        self.assertIn('type:point', system_tags)
        self.assertIn('quick-point', system_tags)
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    def test_background_geocoding_handles_errors_gracefully(self, mock_get_service, mock_setting):
        """Test that background geocoding handles errors without affecting the feature."""
        # Enable geocoding
        mock_setting.return_value = True
        
        # Mock geocoding service to raise an error
        mock_service = MagicMock()
        mock_service.get_location_tags.side_effect = Exception("Geocoding API error")
        mock_get_service.return_value = mock_service
        
        # Create feature
        feature_store = self._create_test_feature(with_geocoding_tags=False)
        original_geojson = feature_store.geojson.copy()
        
        # Start background geocoding
        geocode_feature_async(feature_store.id)
        
        # Wait for background thread to complete
        time.sleep(0.5)
        
        # Refresh from database
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        
        # Verify feature was not modified (error was handled gracefully)
        self.assertEqual(geojson, original_geojson)
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_background_geocoding_skips_when_disabled(self, mock_setting):
        """Test that background geocoding does nothing when geocoding is disabled."""
        # Disable geocoding
        mock_setting.return_value = False
        
        # Create feature
        feature_store = self._create_test_feature(with_geocoding_tags=False)
        original_geojson = feature_store.geojson.copy()
        
        # Start background geocoding
        geocode_feature_async(feature_store.id)
        
        # Wait for background thread to complete
        time.sleep(0.5)
        
        # Refresh from database
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        
        # Verify feature was not modified
        self.assertEqual(geojson, original_geojson)
    
    def test_background_geocoding_handles_nonexistent_feature(self):
        """Test that background geocoding handles nonexistent feature gracefully."""
        # Try to geocode a non-existent feature
        geocode_feature_async(99999)
        
        # Wait for background thread to complete
        time.sleep(0.5)
        
        # Should not raise any exceptions (error is logged but not raised)
        # This test just verifies it doesn't crash
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    def test_background_geocoding_prevents_duplicate_tags(self, mock_get_service, mock_setting):
        """Test that background geocoding doesn't add duplicate tags."""
        # Enable geocoding
        mock_setting.return_value = True
        
        # Mock geocoding service to return tags
        mock_service = MagicMock()
        mock_service.get_location_tags.return_value = [
            'geo-city:San Francisco',
            'geo-state:California'
        ]
        mock_get_service.return_value = mock_service
        
        # Create feature with one geocoding tag already present
        feature_store = self._create_test_feature(with_geocoding_tags=True)
        
        # Start background geocoding
        geocode_feature_async(feature_store.id)
        
        # Wait for background thread to complete
        time.sleep(0.5)
        
        # Refresh from database
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        system_tags = geojson.get('properties', {}).get('system_tags', [])
        
        # Count occurrences of the tag
        san_francisco_count = system_tags.count('geo-city:San Francisco')
        
        # Should only appear once (no duplicates)
        self.assertEqual(san_francisco_count, 1)
        
        # But new tags should be added
        self.assertIn('geo-state:California', system_tags)
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    def test_background_geocoding_row_locking(self, mock_get_service, mock_setting):
        """Test that background geocoding uses row locking to prevent race conditions."""
        # Enable geocoding
        mock_setting.return_value = True
        
        # Mock geocoding service with a delay to simulate slow geocoding
        mock_service = MagicMock()
        
        def slow_get_location_tags(lat, lon, import_log=None):
            time.sleep(0.1)  # Simulate slow geocoding
            return ['geo-city:San Francisco']
        
        mock_service.get_location_tags.side_effect = slow_get_location_tags
        mock_get_service.return_value = mock_service
        
        # Create feature
        feature_store = self._create_test_feature(with_geocoding_tags=False)
        
        # Start background geocoding
        geocode_feature_async(feature_store.id)
        
        # Immediately try to update the feature (simulating concurrent access)
        # This should work because select_for_update() locks the row
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        geojson['properties']['name'] = 'Updated Name'
        feature_store.geojson = geojson
        feature_store.save()
        
        # Wait for background geocoding to complete
        time.sleep(0.5)
        
        # Refresh from database
        feature_store.refresh_from_db()
        geojson = feature_store.geojson
        
        # Verify both updates are present:
        # 1. Name update from concurrent access
        self.assertEqual(geojson['properties']['name'], 'Updated Name')
        # 2. Geocoding tags from background process
        system_tags = geojson.get('properties', {}).get('system_tags', [])
        self.assertIn('geo-city:San Francisco', system_tags)


@pytest.mark.django_db
class TestSkipGeocodingParameter(TestCase):
    """Test the skip_geocoding parameter in generate_auto_tags."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='tagging@example.com',
            password='testpass123',
            username='tagginguser'
        )
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    def test_generate_auto_tags_skips_geocoding(self, mock_get_service, mock_setting):
        """Test that generate_auto_tags skips geocoding when flag is set."""
        from geo_lib.processing.tagging import generate_auto_tags
        
        # Enable geocoding
        mock_setting.return_value = True
        
        # Mock geocoding service
        mock_service = MagicMock()
        mock_service.get_location_tags.return_value = ['geo-city:San Francisco']
        mock_get_service.return_value = mock_service
        
        # Create feature
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        # Generate tags without skipping geocoding
        tags_with_geocoding = generate_auto_tags(feature, skip_geocoding=False)
        
        # Generate tags with geocoding skipped
        tags_without_geocoding = generate_auto_tags(feature, skip_geocoding=True)
        
        # Verify geocoding tags are present when not skipped
        self.assertTrue(any('geo-city' in tag for tag in tags_with_geocoding))
        
        # Verify geocoding tags are absent when skipped
        self.assertFalse(any('geo-city' in tag for tag in tags_without_geocoding))
        
        # Verify other tags are still present in both cases
        self.assertTrue(any('type:point' in tag for tag in tags_with_geocoding))
        self.assertTrue(any('type:point' in tag for tag in tags_without_geocoding))
    
    def test_generate_auto_tags_backward_compatibility(self):
        """Test that generate_auto_tags maintains backward compatibility."""
        from geo_lib.processing.tagging import generate_auto_tags
        
        # Create feature
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        # Call without skip_geocoding parameter (should default to False)
        tags = generate_auto_tags(feature)
        
        # Should still generate tags (at least type tags)
        self.assertTrue(len(tags) > 0)
        self.assertTrue(any('type:point' in tag for tag in tags))

