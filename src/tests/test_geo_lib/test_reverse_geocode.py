"""
Comprehensive tests for reverse geocoding service.

All geocoding functions are imported at the top level. The autouse fixture
in conftest.py mocks query_overpass with real fixture data automatically.
"""
import pytest
from django.test import TestCase
from django.core.cache import cache, caches

from geo_lib.geocoding.admin_boundaries import get_admin_hierarchy
from geo_lib.geocoding.nearby_places import find_nearby_cities, search_nearby_lakes
from geo_lib.geocoding.protected_areas import get_protected_areas
from geo_lib.geocoding.location_tags import get_location_tags
from geo_lib.geocoding.cache import _get_cache_key
from geo_lib.geocoding.ski_resorts import load_ski_resorts, search_nearby_ski_resorts
from geo_lib.spatial.haversine import haversine_distance_miles
from geo_lib.geocoding import overpass_api


@pytest.mark.django_db
class TestHaversineDistance(TestCase):
    """Test haversine distance calculation."""
    
    def test_haversine_distance_zero(self):
        """Test distance between same point is zero."""
        distance = haversine_distance_miles(40.0, -105.0, 40.0, -105.0)
        self.assertAlmostEqual(distance, 0.0, places=2)
    
    def test_haversine_distance_known(self):
        """Test known distance calculation."""
        # Denver to Colorado Springs (approx 63 miles)
        distance = haversine_distance_miles(39.7392, -104.9903, 38.8339, -104.8214)
        self.assertAlmostEqual(distance, 63, delta=2)
    
    def test_haversine_distance_international(self):
        """Test international distance calculation."""
        # London to Paris (approx 213 miles)
        distance = haversine_distance_miles(51.5074, -0.1278, 48.8566, 2.3522)
        self.assertAlmostEqual(distance, 213, delta=5)


@pytest.mark.django_db
class TestCacheKey(TestCase):
    """Test cache key generation."""
    
    def test_cache_key_format(self):
        """Test cache key has correct format."""
        key = _get_cache_key(40.123456, -105.789012)
        self.assertTrue(key.startswith("reverse_geocode:"))
        self.assertIn("40.123", key)
        self.assertIn("-105.789", key)
    
    def test_cache_key_rounding(self):
        """Test cache key rounds coordinates to 3 decimal places."""
        key1 = _get_cache_key(40.1234, -105.7899)
        key2 = _get_cache_key(40.1235, -105.7891)
        # First rounds to 40.123, -105.79
        self.assertEqual(key1, "reverse_geocode:40.123,-105.79")
        # Second rounds to 40.123, -105.789 (different longitude)
        self.assertEqual(key2, "reverse_geocode:40.123,-105.789")
        
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
        resorts = load_ski_resorts()
        self.assertIsInstance(resorts, list)
        self.assertGreater(len(resorts), 50)  # Should have at least 50 resorts
    
    def test_ski_resort_structure(self):
        """Test that ski resorts have required fields."""
        resorts = load_ski_resorts()
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
        resorts = load_ski_resorts()
        for resort in resorts:
            bbox = resort['bbox']
            self.assertLess(bbox['min_lat'], bbox['max_lat'])
            self.assertLess(bbox['min_lon'], bbox['max_lon'])


@pytest.mark.django_db
class TestReverseGeocodingService(TestCase):
    """Test reverse geocoding service with mocked Overpass API."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_admin_hierarchy_query(self):
        """Test administrative hierarchy query."""
        # Aurora, CO coordinates - fixture in conftest.py
        result = get_admin_hierarchy(39.746, -104.844)
        
        self.assertEqual(result['country'], 'United States of America')
        self.assertEqual(result['state'], 'Colorado')
        self.assertEqual(result['county'], 'Adams County')
        self.assertEqual(result['city'], 'Aurora')
    
    def test_find_nearby_cities(self):
        """Test nearby city search."""
        # Fairplay, CO area - fixture in conftest.py
        cities = find_nearby_cities(39.2216, -105.9327, 5.0)
        
        self.assertEqual(len(cities), 1)
        self.assertEqual(cities[0]['name'], 'Fairplay')
        self.assertLess(cities[0]['distance_miles'], 5.0)
    
    def test_protected_areas_query(self):
        """Test protected areas query."""
        # Rocky Mountain National Park - fixture in conftest.py
        areas = get_protected_areas(40.3428, -105.6836)
        
        self.assertEqual(len(areas), 2)
        self.assertEqual(areas[0]['name'], 'Rocky Mountain National Park')
        self.assertEqual(areas[1]['name'], 'Rocky Mountain Wilderness')
    
    def test_ski_resort_inside_bbox(self):
        """Test ski resort detection when point is inside resort bbox."""
        # Vail coordinates (inside Vail resort bbox)
        resorts = search_nearby_ski_resorts(39.6403, -106.3742, 2.0)
        
        self.assertGreater(len(resorts), 0)
        self.assertEqual(resorts[0]['name'], 'Vail')
        self.assertEqual(resorts[0]['distance_miles'], 0.0)
    
    def test_ski_resort_nearby(self):
        """Test ski resort detection when point is near resort."""
        # Point slightly outside Vail bbox but within 5 miles
        resorts = search_nearby_ski_resorts(39.65, -106.30, 5.0)
        
        # Should find Vail nearby
        resort_names = [r['name'] for r in resorts]
        self.assertIn('Vail', resort_names)
    
    def test_search_nearby_lakes(self):
        """Test lake proximity search."""
        # Grand Lake, CO area - fixture in conftest.py
        lakes = search_nearby_lakes(40.2514, -105.8239, 1.0)
        
        self.assertEqual(len(lakes), 1)
        self.assertEqual(lakes[0]['name'], 'Grand Lake')
    
    def test_search_nearby_lakes_outside_range(self):
        """Test that lakes outside 1-mile range are not included."""
        # Point >1 mile from Grand Lake - fixture in conftest.py
        lakes = search_nearby_lakes(40.211372, -105.768591, 1.0)
        
        # Should filter out lakes beyond 1 mile threshold
        self.assertEqual(len(lakes), 0)
    
    def test_get_location_tags_comprehensive(self):
        """Test comprehensive location tag generation."""
        # Generic Colorado coordinates - fixture in conftest.py
        tags, log_messages = get_location_tags(39.0, -105.0)
        
        # Should have country and state tags
        tag_strings = [t for t in tags]
        self.assertTrue(any('country:' in t for t in tag_strings))
        self.assertTrue(any('state:' in t for t in tag_strings))


@pytest.mark.django_db
class TestCaching(TestCase):
    """Test caching functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_admin_hierarchy_caching(self):
        """Test that admin hierarchy results are cached."""
        # Clear any existing calls
        overpass_api.query_overpass.reset_mock()
        
        # First call - fixture in conftest.py
        result1 = get_admin_hierarchy(40.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Second call should use cache
        result2 = get_admin_hierarchy(40.0, -105.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # No additional calls should have been made
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(result1, result2)
    
    def test_ski_resort_caching(self):
        """Test that ski resort results are cached."""
        # First call
        resorts1 = search_nearby_ski_resorts(39.64, -106.37, 2.0)
        
        # Second call should use cache (same rounded coordinates)
        resorts2 = search_nearby_ski_resorts(39.6401, -106.3701, 2.0)
        
        self.assertEqual(resorts1, resorts2)


@pytest.mark.django_db
class TestErrorHandling(TestCase):
    """Test error handling in reverse geocoding."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_overpass_timeout_handling(self):
        """Test handling of Overpass API timeout/error."""
        # Coordinates without fixture data return None
        result = get_admin_hierarchy(40.0, -105.0)
        
        # Should return default structure, not raise exception
        self.assertIsInstance(result, dict)
        self.assertIn('country', result)
    
    def test_overpass_error_response(self):
        """Test handling of Overpass API error response."""
        # Coordinates without fixture data return None
        result = get_admin_hierarchy(40.0, -105.0)
        
        # Should return default structure, not raise exception
        self.assertIsInstance(result, dict)
    
    def test_get_location_tags_exception_handling(self):
        """Test that get_location_tags handles exceptions gracefully."""
        # Invalid coordinates shouldn't crash
        tags, log_messages = get_location_tags(999.0, 999.0)
        
        # Should return empty list, not raise exception
        self.assertIsInstance(tags, list)
        self.assertIsInstance(log_messages, list)


@pytest.mark.django_db
class TestTagGeneration(TestCase):
    """Test tag generation from location data."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_national_park_tag(self):
        """Test national park tag generation."""
        # Rocky Mountain NP coordinates - fixture in conftest.py
        tags, log_messages = get_location_tags(40.34, -105.68)
        
        self.assertTrue(any('national-park:Rocky Mountain National Park' in t for t in tags))
    
    def test_national_monument_tag(self):
        """Test national monument tag generation."""
        # Colorado National Monument area - fixture in conftest.py
        tags, log_messages = get_location_tags(39.07, -108.73)
        
        self.assertTrue(any('national-monument:' in t for t in tags))
    
    def test_wilderness_tag(self):
        """Test wilderness area tag generation."""
        # Mt Evans Wilderness area - fixture in conftest.py
        tags, log_messages = get_location_tags(39.42, -105.65)
        
        self.assertTrue(any('wilderness:' in t for t in tags))


@pytest.mark.django_db
class TestIntegration(TestCase):
    """Integration tests for ski resort detection."""
    
    def test_ski_resort_detection_vail(self):
        """Integration test: Vail ski resort detection."""
        resorts = search_nearby_ski_resorts(39.6403, -106.3742, 2.0)
        
        self.assertGreater(len(resorts), 0)
        self.assertEqual(resorts[0]['name'], 'Vail')
    
    def test_ski_resort_detection_breckenridge(self):
        """Integration test: Breckenridge ski resort detection."""
        resorts = search_nearby_ski_resorts(39.4817, -106.0384, 2.0)
        
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
            resorts = search_nearby_ski_resorts(lat, lon, 2.0)
            resort_names = [r['name'] for r in resorts]
            self.assertIn(name, resort_names, f"Failed to detect {name}")
