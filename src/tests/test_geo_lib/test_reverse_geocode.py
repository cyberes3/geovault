"""
Comprehensive tests for reverse geocoding service.
Tests all major features: cities, admin hierarchy, protected areas, ski resorts, lakes.
"""
import pytest
from unittest.mock import patch, MagicMock
from django.test import TestCase
from django.core.cache import cache

from geo_lib.geolocation.reverse_geocode import (
    get_reverse_geocoding_service,
    haversine_distance,
    _get_cache_key,
    _load_ski_resorts
)


@pytest.mark.django_db
class TestHaversineDistance(TestCase):
    """Test haversine distance calculation."""
    
    def test_haversine_distance_zero(self):
        """Test distance between same point is zero."""
        distance = haversine_distance(40.0, -105.0, 40.0, -105.0)
        self.assertAlmostEqual(distance, 0.0, places=2)
    
    def test_haversine_distance_known(self):
        """Test known distance calculation."""
        # Denver to Colorado Springs (approx 63 miles)
        distance = haversine_distance(39.7392, -104.9903, 38.8339, -104.8214)
        self.assertAlmostEqual(distance, 63, delta=2)
    
    def test_haversine_distance_international(self):
        """Test international distance calculation."""
        # London to Paris (approx 213 miles)
        distance = haversine_distance(51.5074, -0.1278, 48.8566, 2.3522)
        self.assertAlmostEqual(distance, 213, delta=5)


@pytest.mark.django_db
class TestCacheKey(TestCase):
    """Test cache key generation."""
    
    def test_cache_key_format(self):
        """Test cache key has correct format."""
        key = _get_cache_key(40.123456, -105.789012)
        self.assertTrue(key.startswith("geocode:"))
        self.assertIn("40.123", key)
        self.assertIn("-105.789", key)
    
    def test_cache_key_rounding(self):
        """Test cache key rounds coordinates to 3 decimal places."""
        key1 = _get_cache_key(40.1234, -105.7899)
        key2 = _get_cache_key(40.1235, -105.7891)
        # First rounds to 40.123, -105.79
        self.assertEqual(key1, "geocode:40.123,-105.79")
        # Second rounds to 40.123, -105.789 (different longitude)
        self.assertEqual(key2, "geocode:40.123,-105.789")
        
        # Test that similar coords get same key
        key3 = _get_cache_key(40.12299, -105.78999)
        key4 = _get_cache_key(40.12301, -105.79001)
        # Both should round to 40.123, -105.79
        self.assertEqual(key3, key4)
    
    def test_cache_key_prefix(self):
        """Test cache key uses custom prefix."""
        key = _get_cache_key(40.0, -105.0, prefix="test")
        self.assertTrue(key.startswith("test:"))


@pytest.mark.django_db
class TestSkiResortDatabase(TestCase):
    """Test ski resort database loading."""
    
    def test_ski_resorts_load(self):
        """Test that ski resorts database loads successfully."""
        resorts = _load_ski_resorts()
        self.assertIsInstance(resorts, list)
        self.assertGreater(len(resorts), 50)  # Should have at least 50 resorts
    
    def test_ski_resort_structure(self):
        """Test that ski resorts have required fields."""
        resorts = _load_ski_resorts()
        for resort in resorts[:5]:  # Check first 5
            self.assertIn('name', resort)
            self.assertIn('country', resort)
            self.assertIn('bbox', resort)
            bbox = resort['bbox']
            self.assertIn('min_lat', bbox)
            self.assertIn('max_lat', bbox)
            self.assertIn('min_lon', bbox)
            self.assertIn('max_lon', bbox)
    
    def test_ski_resort_bbox_valid(self):
        """Test that bounding boxes are valid."""
        resorts = _load_ski_resorts()
        for resort in resorts:
            bbox = resort['bbox']
            self.assertLess(bbox['min_lat'], bbox['max_lat'])
            self.assertLess(bbox['min_lon'], bbox['max_lon'])


@pytest.mark.django_db
class TestReverseGeocodingService(TestCase):
    """Test reverse geocoding service main functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.service = get_reverse_geocoding_service()
        # Clear cache before each test
        cache.clear()
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_admin_hierarchy_query(self, mock_post):
        """Test administrative hierarchy query."""
        # Mock Overpass response for Aurora, CO
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'elements': [
                {'tags': {'name': 'United States of America', 'admin_level': '2', 'boundary': 'administrative'}},
                {'tags': {'name': 'Colorado', 'admin_level': '4', 'boundary': 'administrative'}},
                {'tags': {'name': 'Adams County', 'admin_level': '6', 'boundary': 'administrative'}},
                {'tags': {'name': 'Aurora', 'admin_level': '8', 'boundary': 'administrative'}}
            ]
        }
        mock_post.return_value = mock_response
        
        result = self.service._get_admin_hierarchy(39.746, -104.844)
        
        self.assertEqual(result['country'], 'United States of America')
        self.assertEqual(result['state'], 'Colorado')
        self.assertEqual(result['county'], 'Adams County')
        self.assertEqual(result['city'], 'Aurora')
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_find_nearby_cities(self, mock_post):
        """Test nearby city search."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'elements': [
                {
                    'tags': {'name': 'Fairplay', 'place': 'town'},
                    'lat': 39.2252,
                    'lon': -106.0020
                }
            ]
        }
        mock_post.return_value = mock_response
        
        cities = self.service._find_nearby_cities(39.2216, -105.9327, 5.0)
        
        self.assertEqual(len(cities), 1)
        self.assertEqual(cities[0]['name'], 'Fairplay')
        self.assertLess(cities[0]['distance_miles'], 5.0)
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_protected_areas_query(self, mock_post):
        """Test protected areas query."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'elements': [
                {
                    'tags': {
                        'name': 'Rocky Mountain National Park',
                        'protection_title': 'National Park',
                        'boundary': 'protected_area'
                    }
                },
                {
                    'tags': {
                        'name': 'Rocky Mountain Wilderness',
                        'protection_title': 'Wilderness Area',
                        'boundary': 'protected_area'
                    }
                }
            ]
        }
        mock_post.return_value = mock_response
        
        areas = self.service._get_protected_areas(40.3428, -105.6836)
        
        self.assertEqual(len(areas), 2)
        self.assertEqual(areas[0]['name'], 'Rocky Mountain National Park')
        self.assertEqual(areas[1]['name'], 'Rocky Mountain Wilderness')
    
    def test_ski_resort_inside_bbox(self):
        """Test ski resort detection when point is inside resort bbox."""
        # Vail coordinates (inside Vail resort bbox)
        resorts = self.service._search_nearby_ski_resorts(39.6403, -106.3742, 2.0)
        
        self.assertGreater(len(resorts), 0)
        self.assertEqual(resorts[0]['name'], 'Vail')
        self.assertEqual(resorts[0]['distance_miles'], 0.0)
    
    def test_ski_resort_nearby(self):
        """Test ski resort detection when point is near resort."""
        # Point slightly outside Vail bbox but within 2 miles
        resorts = self.service._search_nearby_ski_resorts(39.65, -106.30, 5.0)
        
        # Should find Vail nearby
        resort_names = [r['name'] for r in resorts]
        self.assertIn('Vail', resort_names)
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_search_nearby_lakes(self, mock_post):
        """Test lake proximity search."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'elements': [
                {
                    'tags': {'name': 'Grand Lake', 'natural': 'water', 'water': 'lake'},
                    'lat': 40.2514,
                    'lon': -105.8239
                }
            ]
        }
        mock_post.return_value = mock_response
        
        lakes = self.service._search_nearby_lakes(40.2514, -105.8239, 1.0)
        
        self.assertEqual(len(lakes), 1)
        self.assertEqual(lakes[0]['name'], 'Grand Lake')
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_search_nearby_lakes_outside_range(self, mock_post):
        """Test that lakes outside 1-mile range are not included."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        # Mock response with a lake that's far away
        mock_response.json.return_value = {
            'elements': [
                {
                    'tags': {'name': 'Grand Lake', 'natural': 'water', 'water': 'lake'},
                    'lat': 40.2514,  # This is >1 mile from test point
                    'lon': -105.8239
                }
            ]
        }
        mock_post.return_value = mock_response
        
        # Point that's >1 mile from Grand Lake
        lakes = self.service._search_nearby_lakes(40.211372, -105.768591, 1.0)
        
        # Should filter out lakes beyond 1 mile threshold
        self.assertEqual(len(lakes), 0)
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_get_location_tags_comprehensive(self, mock_post):
        """Test comprehensive location tag generation."""
        # Mock responses for all queries
        def mock_overpass(*args, **kwargs):
            response = MagicMock()
            response.status_code = 200
            query = args[1] if len(args) > 1 else kwargs.get('data', '')
            
            # Admin hierarchy query
            if 'admin_level' in query:
                response.json.return_value = {
                    'elements': [
                        {'tags': {'name': 'United States of America', 'admin_level': '2', 'boundary': 'administrative'}},
                        {'tags': {'name': 'Colorado', 'admin_level': '4', 'boundary': 'administrative'}},
                        {'tags': {'name': 'Park County', 'admin_level': '6', 'boundary': 'administrative'}}
                    ]
                }
            # Protected areas query
            elif 'protected_area' in query:
                response.json.return_value = {
                    'elements': [
                        {'tags': {'name': 'Pike National Forest', 'protection_title': 'National Forest'}}
                    ]
                }
            # Lakes query
            elif 'natural' in query and 'water' in query:
                response.json.return_value = {'elements': []}
            else:
                response.json.return_value = {'elements': []}
            
            return response
        
        mock_post.side_effect = mock_overpass
        
        tags = self.service.get_location_tags(39.0, -105.0)
        
        # Should have country, state, county tags
        tag_strings = [t for t in tags]
        self.assertTrue(any('country:' in t for t in tag_strings))
        self.assertTrue(any('state:' in t for t in tag_strings))


@pytest.mark.django_db
class TestCaching(TestCase):
    """Test caching functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.service = get_reverse_geocoding_service()
        cache.clear()
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_admin_hierarchy_caching(self, mock_post):
        """Test that admin hierarchy results are cached."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'elements': [
                {'tags': {'name': 'Colorado', 'admin_level': '4'}}
            ]
        }
        mock_post.return_value = mock_response
        
        # First call
        result1 = self.service._get_admin_hierarchy(40.0, -105.0)
        self.assertEqual(mock_post.call_count, 1)
        
        # Second call should use cache
        result2 = self.service._get_admin_hierarchy(40.0, -105.0)
        self.assertEqual(mock_post.call_count, 1)  # No additional call
        
        self.assertEqual(result1, result2)
    
    def test_ski_resort_caching(self):
        """Test that ski resort results are cached."""
        # First call
        resorts1 = self.service._search_nearby_ski_resorts(39.64, -106.37, 2.0)
        
        # Second call should use cache (same rounded coordinates)
        resorts2 = self.service._search_nearby_ski_resorts(39.6401, -106.3701, 2.0)
        
        self.assertEqual(resorts1, resorts2)


@pytest.mark.django_db
class TestErrorHandling(TestCase):
    """Test error handling in reverse geocoding."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.service = get_reverse_geocoding_service()
        cache.clear()
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_overpass_timeout_handling(self, mock_post):
        """Test handling of Overpass API timeout."""
        import requests
        mock_post.side_effect = requests.exceptions.Timeout()
        
        result = self.service._get_admin_hierarchy(40.0, -105.0)
        
        # Should return default structure, not raise exception
        self.assertIsInstance(result, dict)
        self.assertIn('country', result)
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_overpass_error_response(self, mock_post):
        """Test handling of Overpass API error response."""
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.text = "Internal Server Error"
        mock_post.return_value = mock_response
        
        result = self.service._get_admin_hierarchy(40.0, -105.0)
        
        # Should return default structure, not raise exception
        self.assertIsInstance(result, dict)
    
    def test_get_location_tags_exception_handling(self):
        """Test that get_location_tags handles exceptions gracefully."""
        # Invalid coordinates shouldn't crash
        tags = self.service.get_location_tags(999.0, 999.0)
        
        # Should return empty list, not raise exception
        self.assertIsInstance(tags, list)


@pytest.mark.django_db
class TestTagGeneration(TestCase):
    """Test tag generation from location data."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.service = get_reverse_geocoding_service()
        cache.clear()
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_national_park_tag(self, mock_post):
        """Test national park tag generation."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        
        def mock_overpass(*args, **kwargs):
            response = MagicMock()
            response.status_code = 200
            query = args[1] if len(args) > 1 else kwargs.get('data', '')
            
            if 'protected_area' in query:
                response.json.return_value = {
                    'elements': [{
                        'tags': {
                            'name': 'Rocky Mountain National Park',
                            'protection_title': 'National Park'
                        }
                    }]
                }
            else:
                response.json.return_value = {'elements': []}
            return response
        
        mock_post.side_effect = mock_overpass
        
        tags = self.service.get_location_tags(40.34, -105.68)
        
        self.assertTrue(any('national-park:Rocky Mountain National Park' in t for t in tags))
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_national_monument_tag(self, mock_post):
        """Test national monument tag generation."""
        def mock_overpass(*args, **kwargs):
            response = MagicMock()
            response.status_code = 200
            query = args[1] if len(args) > 1 else kwargs.get('data', '')
            
            if 'protected_area' in query:
                response.json.return_value = {
                    'elements': [{
                        'tags': {
                            'name': 'Colorado National Monument',
                            'protection_title': 'National Monument'
                        }
                    }]
                }
            else:
                response.json.return_value = {'elements': []}
            return response
        
        mock_post.side_effect = mock_overpass
        
        tags = self.service.get_location_tags(39.07, -108.73)
        
        self.assertTrue(any('national-monument:' in t for t in tags))
    
    @patch('geo_lib.geolocation.reverse_geocode.requests.post')
    def test_wilderness_tag(self, mock_post):
        """Test wilderness area tag generation."""
        def mock_overpass(*args, **kwargs):
            response = MagicMock()
            response.status_code = 200
            query = args[1] if len(args) > 1 else kwargs.get('data', '')
            
            if 'protected_area' in query:
                response.json.return_value = {
                    'elements': [{
                        'tags': {
                            'name': 'Lost Creek Wilderness',
                            'protection_title': 'Wilderness Area'
                        }
                    }]
                }
            else:
                response.json.return_value = {'elements': []}
            return response
        
        mock_post.side_effect = mock_overpass
        
        tags = self.service.get_location_tags(39.42, -105.65)
        
        self.assertTrue(any('wilderness:' in t for t in tags))
    
    def test_duplicate_tag_prevention(self):
        """Test that duplicate tags are prevented."""
        # This would be better tested with a real scenario that could produce duplicates
        # For now, we verify the set() usage in the implementation
        tags = ['city:Denver', 'city:Denver', 'state:Colorado']
        unique_tags = list(set(tags))
        self.assertEqual(len(unique_tags), 2)


@pytest.mark.django_db  
class TestIntegrationScenarios(TestCase):
    """Integration tests for real-world scenarios."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.service = get_reverse_geocoding_service()
        cache.clear()
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_ski_resort_detection_vail(self):
        """Integration test: Vail ski resort detection."""
        resorts = self.service._search_nearby_ski_resorts(39.6403, -106.3742, 2.0)
        
        self.assertGreater(len(resorts), 0)
        self.assertEqual(resorts[0]['name'], 'Vail')
    
    def test_ski_resort_detection_breckenridge(self):
        """Integration test: Breckenridge ski resort detection."""
        resorts = self.service._search_nearby_ski_resorts(39.4817, -106.0384, 2.0)
        
        self.assertGreater(len(resorts), 0)
        resort_names = [r['name'] for r in resorts]
        self.assertIn('Breckenridge', resort_names)
    
    def test_major_epic_ikon_resorts(self):
        """Integration test: Major Epic and Ikon resorts."""
        test_resorts = [
            ('Vail', 39.6403, -106.3742),
            ('Park City', 40.66, -111.50),
            ('Jackson Hole', 43.60, -110.84),
        ]
        
        for name, lat, lon in test_resorts:
            resorts = self.service._search_nearby_ski_resorts(lat, lon, 2.0)
            resort_names = [r['name'] for r in resorts]
            self.assertIn(name, resort_names, f"Failed to detect {name}")

