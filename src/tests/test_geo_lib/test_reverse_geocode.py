"""
Comprehensive tests for reverse reverse_geocoding service.

All reverse_geocoding functions are imported at the top level. The autouse fixture
in conftest.py mocks query_overpass with real fixture data automatically.
"""
import pytest
from django.test import TestCase
from django.core.cache import cache, caches

from geo_lib.reverse_geocoding.admin_boundaries import get_admin_hierarchy
from geo_lib.reverse_geocoding.nearby_places import find_nearby_cities, search_nearby_lakes
from geo_lib.reverse_geocoding.protected_areas import get_protected_areas
from geo_lib.reverse_geocoding.location_tags import get_location_tags, batch_reverse_geocode_coordinates
from geo_lib.reverse_geocoding.cache import _get_cache_key, _REVERSE_GEOCODING_CACHE
from geo_lib.reverse_geocoding.ski_resorts import load_ski_resorts, search_nearby_ski_resorts
from geo_lib.spatial.haversine import haversine_distance_miles
from geo_lib.reverse_geocoding import overpass_api


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
    """Test reverse reverse_geocoding service with mocked Overpass API."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['reverse_geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_admin_hierarchy_query(self):
        """Test administrative hierarchy query."""
        # Aurora, CO coordinates - fixture in conftest.py
        result, errors = get_admin_hierarchy(39.746, -104.844)
        
        self.assertEqual(result['country'], 'United States of America')
        self.assertEqual(result['state'], 'Colorado')
        self.assertEqual(result['county'], 'Adams County')
        self.assertEqual(result['city'], 'Aurora')
    
    def test_find_nearby_cities(self):
        """Test nearby city search."""
        # Fairplay, CO area - fixture in conftest.py
        cities, errors = find_nearby_cities(39.2216, -105.9327, 5.0)
        
        self.assertEqual(len(cities), 1)
        self.assertEqual(cities[0]['name'], 'Fairplay')
        self.assertLess(cities[0]['distance_miles'], 5.0)
    
    def test_protected_areas_query(self):
        """Test protected areas query."""
        # Rocky Mountain National Park - fixture in conftest.py
        areas, errors = get_protected_areas(40.3428, -105.6836)
        
        self.assertEqual(len(areas), 2)
        self.assertEqual(areas[0]['name'], 'Rocky Mountain National Park')
        self.assertEqual(areas[1]['name'], 'Rocky Mountain Wilderness')
    
    def test_protected_areas_misc_parks(self):
        """Test that misc parks are correctly identified and tagged as protected-area."""
        from geo_lib.reverse_geocoding.protected_areas import classify_protected_area
        
        # South Valley Park, Colorado - should be tagged as protected-area
        areas, errors = get_protected_areas(39.5626793, -105.1501089)
        self.assertEqual(len(areas), 1)
        self.assertEqual(areas[0]['name'], 'South Valley Park')
        self.assertEqual(areas[0]['boundary'], 'protected_area')
        self.assertEqual(areas[0]['landuse'], 'recreation_ground')
        area_type = classify_protected_area(areas[0])
        self.assertEqual(area_type, 'protected-area')
        
        # Blue Hills Reservation, Massachusetts - should be tagged as state-park
        areas, errors = get_protected_areas(42.22314472038681, -71.09840390005273)
        self.assertEqual(len(areas), 1)
        self.assertEqual(areas[0]['name'], 'Blue Hills Reservation')
        self.assertEqual(areas[0]['boundary'], 'protected_area')
        self.assertEqual(areas[0]['leisure'], 'nature_reserve')
        area_type = classify_protected_area(areas[0])
        self.assertEqual(area_type, 'state-park')
        
        # Bells Bend Park, Tennessee - should be tagged as protected-area
        areas, errors = get_protected_areas(36.15564975174452, -86.92466166278994)
        self.assertEqual(len(areas), 1)
        self.assertEqual(areas[0]['name'], 'Bells Bend Park')
        self.assertEqual(areas[0]['boundary'], 'protected_area')
        area_type = classify_protected_area(areas[0])
        self.assertEqual(area_type, 'protected-area')
    
    def test_city_park_classification(self):
        """Test that city parks (leisure=park without boundary=protected_area) are tagged as 'park'."""
        from geo_lib.reverse_geocoding.protected_areas import classify_protected_area
        
        # James N. Manley Park, Colorado - city park with leisure=park but no boundary=protected_area
        areas, errors = get_protected_areas(39.72294740028117, -104.95773491586752)
        self.assertEqual(len(areas), 1)
        self.assertEqual(areas[0]['name'], 'James N. Manley Park')
        self.assertEqual(areas[0]['leisure'], 'park')
        self.assertEqual(areas[0].get('boundary', ''), '')  # No boundary tag
        area_type = classify_protected_area(areas[0])
        self.assertEqual(area_type, 'park')
        
        # Test that parks with boundary=protected_area are still protected-area
        area_with_boundary = {
            'name': 'Some Park',
            'leisure': 'park',
            'boundary': 'protected_area',
            'protection_title': '',
            'designation': '',
            'operator': ''
        }
        area_type = classify_protected_area(area_with_boundary)
        self.assertEqual(area_type, 'protected-area')
    
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
        lakes, errors = search_nearby_lakes(40.2514, -105.8239, 1.0)
        
        self.assertEqual(len(lakes), 1)
        self.assertEqual(lakes[0]['name'], 'Grand Lake')
    
    def test_search_nearby_lakes_outside_range(self):
        """Test that lakes outside 1-mile range are not included."""
        # Point >1 mile from Grand Lake - fixture in conftest.py
        lakes, errors = search_nearby_lakes(40.211372, -105.768591, 1.0)
        
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
            caches['reverse_geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_admin_hierarchy_caching(self):
        """Test that admin hierarchy results are cached."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call - fixture in conftest.py
        result1, errors1 = get_admin_hierarchy(40.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Second call should use cache
        result2, errors2 = get_admin_hierarchy(40.0, -105.0)
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
    
    def test_protected_areas_caching(self):
        """Test that protected areas results are cached."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call - Rocky Mountain National Park - fixture in conftest.py
        areas1, errors1 = get_protected_areas(40.3428, -105.6836)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        self.assertGreater(len(areas1), 0)
        
        # Second call should use cache
        areas2, errors2 = get_protected_areas(40.3428, -105.6836)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # No additional calls should have been made
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(areas1, areas2)
    
    def test_protected_areas_caching_with_rounded_coords(self):
        """Test that protected areas cache works with coordinate rounding."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call
        areas1, errors1 = get_protected_areas(40.3428, -105.6836)
        call_count_1 = overpass_api.query_overpass.call_count
        
        # Second call with slightly different coordinates (should round to same cache key)
        areas2, errors2 = get_protected_areas(40.3429, -105.6837)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # Should use cache (no additional calls)
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(areas1, areas2)
    
    def test_nearby_cities_caching(self):
        """Test that nearby cities results are cached."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call - Fairplay, CO area - fixture in conftest.py
        cities1, errors1 = find_nearby_cities(39.2216, -105.9327, 5.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Second call should use cache
        cities2, errors2 = find_nearby_cities(39.2216, -105.9327, 5.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # No additional calls should have been made
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(cities1, cities2)
    
    def test_nearby_cities_caching_different_threshold(self):
        """Test that nearby cities cache uses threshold in cache key."""
        # Clear any existing calls
        overpass_api.query_overpass.reset_mock()
        
        # First call with 5.0 mile threshold
        cities1, errors1 = find_nearby_cities(39.2216, -105.9327, 5.0)
        call_count_1 = overpass_api.query_overpass.call_count
        
        # Second call with different threshold should make new API call
        cities2, errors2 = find_nearby_cities(39.2216, -105.9327, 10.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # Should have made additional calls for different threshold
        self.assertGreater(call_count_2, call_count_1)
    
    def test_nearby_lakes_caching(self):
        """Test that nearby lakes results are cached."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call - Grand Lake, CO area - fixture in conftest.py
        lakes1, errors1 = search_nearby_lakes(40.2514, -105.8239, 1.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Second call should use cache
        lakes2, errors2 = search_nearby_lakes(40.2514, -105.8239, 1.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # No additional calls should have been made
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(lakes1, lakes2)
    
    def test_nearby_lakes_caching_different_threshold(self):
        """Test that nearby lakes cache uses threshold in cache key."""
        # Clear any existing calls
        overpass_api.query_overpass.reset_mock()
        
        # First call with 1.0 mile threshold
        lakes1, errors1 = search_nearby_lakes(40.2514, -105.8239, 1.0)
        call_count_1 = overpass_api.query_overpass.call_count
        
        # Second call with different threshold should make new API call
        lakes2, errors2 = search_nearby_lakes(40.2514, -105.8239, 2.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # Should have made additional calls for different threshold
        self.assertGreater(call_count_2, call_count_1)
    
    def test_get_location_tags_uses_query_cache(self):
        """Test that get_location_tags benefits from query_overpass caching."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call should fetch from API (query_overpass caches responses)
        tags1, log_messages1 = get_location_tags(39.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Second call should use query_overpass cache (admin, protected areas, lakes queries are cached)
        tags2, log_messages2 = get_location_tags(39.0, -105.0)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # No additional calls should have been made (all queries are cached at API level)
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(tags1, tags2)
    
    def test_get_location_tags_cache_with_rounded_coords(self):
        """Test that get_location_tags cache works with coordinate rounding."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call
        tags1, _ = get_location_tags(39.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        
        # Second call with slightly different coordinates (should round to same cache key)
        tags2, _ = get_location_tags(39.0001, -105.0001)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # Should use cache (no additional calls)
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(tags1, tags2)
    
    def test_query_cache_via_batch(self):
        """Test that query_overpass cache works via batch function."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First batch call should fetch from API and cache at query level
        coordinates = [(39.0, -105.0)]
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        tags1 = results1[(39.0, -105.0)][0]
        log_messages1 = results1[(39.0, -105.0)][1]
        
        # Reset mock to track new calls (cache should still be active)
        overpass_api.query_overpass.reset_mock()
        
        # Second batch call should use query_overpass cache (no API calls)
        results2 = batch_reverse_geocode_coordinates(coordinates)
        call_count_2 = overpass_api.query_overpass.call_count
        tags2 = results2[(39.0, -105.0)][0]
        log_messages2 = results2[(39.0, -105.0)][1]
        
        # Should use query cache (no new API calls)
        self.assertEqual(call_count_2, 0)
        self.assertEqual(tags1, tags2)
        # Log messages should be empty on cache hit (cached responses don't have log messages)
        self.assertEqual(len(log_messages2), 0)
    
    def test_batch_reverse_geocode_deduplication(self):
        """Test that batch_reverse_geocode_coordinates deduplicates nearby coordinates."""
        # Clear any existing calls
        overpass_api.query_overpass.reset_mock()
        
        # Create coordinates that will round to the same cache key
        # These are within ~111m of each other, so they should deduplicate
        coordinates = [
            (40.1234, -105.7899),  # Rounds to 40.123, -105.79
            (40.1235, -105.7891),  # Rounds to 40.123, -105.789 (different)
            (40.1236, -105.7898),  # Rounds to 40.123, -105.79 (same as first)
            (40.1237, -105.7892),  # Rounds to 40.123, -105.789 (same as second)
        ]
        
        # First batch call
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        
        # Should have made API calls for unique rounded coordinates (2 unique)
        # But we can't easily count exact calls due to multiple sub-queries
        # Instead, verify that results are consistent
        self.assertEqual(len(results1), 4)  # All 4 coordinates should have results
        
        # Verify that coordinates that round to same key get same results
        # First and third should have same results (both round to 40.123, -105.79)
        tags_1 = results1[(40.1234, -105.7899)][0]
        tags_3 = results1[(40.1236, -105.7898)][0]
        self.assertEqual(tags_1, tags_3)
        
        # Second and fourth should have same results (both round to 40.123, -105.789)
        tags_2 = results1[(40.1235, -105.7891)][0]
        tags_4 = results1[(40.1237, -105.7892)][0]
        self.assertEqual(tags_2, tags_4)
    
    def test_batch_reverse_geocode_caching(self):
        """Test that batch_reverse_geocode_coordinates uses query_overpass cache on second call."""
        # Clear cache and reset mock
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # Use coordinates that have fixtures for all query types (admin, protected, lakes, cities)
        # Use (39.0, -105.0) which has all fixtures (no empty responses)
        coordinates = [
            (39.0, -105.0),  # Has fixtures for all query types
            (39.0001, -105.0001),  # Rounds to (39.0, -105.0), should use cache
        ]
        
        # First batch call
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertGreater(call_count_1, 0)
        
        # Reset mock to track new calls (cache should still be active)
        overpass_api.query_overpass.reset_mock()
        
        # Second batch call with same coordinates
        results2 = batch_reverse_geocode_coordinates(coordinates)
        call_count_2 = overpass_api.query_overpass.call_count
        
        # Should use query_overpass cache (no new API calls)
        self.assertEqual(call_count_2, 0)
        
        # Compare tags (log messages may differ - first call has messages, cached call has empty)
        for coord in coordinates:
            tags1 = results1[coord][0]
            tags2 = results2[coord][0]
            self.assertEqual(tags1, tags2, f"Tags should match for coordinate {coord}")
            # Cached results should have empty log messages
            self.assertEqual(len(results2[coord][1]), 0, "Cached results should have empty log messages")
    
    def test_batch_reverse_geocode_empty_list(self):
        """Test that batch_reverse_geocode_coordinates handles empty list."""
        results = batch_reverse_geocode_coordinates([])
        self.assertEqual(results, {})


@pytest.mark.django_db
class TestErrorHandling(TestCase):
    """Test error handling in reverse reverse_geocoding."""
    
    def setUp(self):
        """Set up test fixtures."""
        cache.clear()
        try:
            caches['reverse_geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_overpass_timeout_handling(self):
        """Test handling of Overpass API timeout/error."""
        # Coordinates without fixture data return None
        result, errors = get_admin_hierarchy(40.0, -105.0)
        
        # Should return default structure, not raise exception
        self.assertIsInstance(result, dict)
        self.assertIn('country', result)
    
    def test_overpass_error_response(self):
        """Test handling of Overpass API error response."""
        # Coordinates without fixture data return None
        result, errors = get_admin_hierarchy(40.0, -105.0)
        
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
            caches['reverse_geocoding'].clear()
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
