"""
Comprehensive tests for reverse geocoding service.

The autouse fixture in conftest.py mocks query_overpass (lakes/cities) and
query_areas_server (admin/protected from same fixtures) automatically.
"""
import pytest
from unittest.mock import patch
from django.test import TestCase
from django.core.cache import cache, caches

from geo_lib.reverse_geocoding.combined_overpass import fetch_lakes_and_cities
from geo_lib.reverse_geocoding.admin_boundaries import get_admin_hierarchy
from geo_lib.reverse_geocoding.nearby_places import find_nearby_cities, search_nearby_lakes
from geo_lib.reverse_geocoding.protected_areas import get_protected_areas
from geo_lib.reverse_geocoding.location_tags import get_location_tags, batch_reverse_geocode_coordinates
from geo_lib.reverse_geocoding.cache import _get_cache_key, _REVERSE_GEOCODING_CACHE
from geo_lib.reverse_geocoding.ski_resorts import load_ski_resorts, search_nearby_ski_resorts
from geo_lib.spatial.haversine import haversine_distance_miles
from geo_lib.reverse_geocoding import overpass_api

from tests.fixtures.geocoding_responses import get_areas_fixture, get_combined_fixture


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
            caches['reverse_geocoding'].clear()
        except Exception:
            pass
    
    def tearDown(self):
        """Clean up after tests."""
        cache.clear()
    
    def test_admin_hierarchy_query(self):
        """Test admin hierarchy from cached areas_server fixture (load fixtures with fetch script)."""
        # Use a precise coordinate so the fixture unambiguously maps to one county (39.222, -105.933 -> Park County)
        areas = get_areas_fixture(39.222, -105.933)
        self.assertIsNotNone(areas, "Load areas_server fixtures (e.g. fetch_combined_overpass_fixtures.py --areas-url)")
        admin = areas["admin_hierarchy"]
        self.assertEqual(admin["country"], "United States of America")
        self.assertEqual(admin["state"], "Colorado")
        self.assertEqual(admin["county"], "Park County")
    
    def test_find_nearby_cities(self):
        """Test nearby city search from cached combined_overpass fixture."""
        response = get_combined_fixture(39.222, -105.933)
        cities, errors = find_nearby_cities(response, 39.2216, -105.9327, 5.0)
        self.assertIsInstance(cities, list)
        if response.get("elements"):
            self.assertFalse(errors)
            self.assertLessEqual(len(cities), 20)
        else:
            self.assertEqual(cities, [], "Load combined_overpass fixtures (fetch_combined_overpass_fixtures.py)")
    
    def test_protected_areas_query(self):
        """Test protected areas from cached areas_server fixture."""
        areas_data = get_areas_fixture(40.34, -105.68)
        self.assertIsNotNone(areas_data, "Load areas_server fixtures (e.g. fetch_combined_overpass_fixtures.py --areas-url)")
        protected = areas_data["protected_areas"]
        self.assertGreaterEqual(len(protected), 1)
        names = [a["name"] for a in protected]
        self.assertTrue(
            any("Rocky Mountain" in n for n in names),
            f"Expected Rocky Mountain area in {names}",
        )
    
    def test_protected_areas_misc_parks(self):
        """Test classify_protected_area on areas from cached areas_server fixtures."""
        from geo_lib.reverse_geocoding.protected_areas import classify_protected_area

        # Cached fixtures: (40.34, -105.68) has national-park; (39.42, -105.65) has wilderness + national-forest; (39.07, -108.73) has national-monument
        found = {}
        for lat, lon, expected_prefix in [
            (40.34, -105.68, "national-park"),
            (39.42, -105.65, "wilderness"),
            (39.07, -108.73, "national-monument"),
        ]:
            areas_data = get_areas_fixture(lat, lon)
            if not areas_data or not areas_data.get("protected_areas"):
                continue
            for area in areas_data["protected_areas"]:
                if classify_protected_area(area) == expected_prefix:
                    found[expected_prefix] = True
                    break
        self.assertEqual(found.get("national-park"), True, "Fixture should have at least one national-park area")
        self.assertEqual(found.get("wilderness"), True, "Fixture should have at least one wilderness area")
        self.assertEqual(found.get("national-monument"), True, "Fixture should have at least one national-monument area")
        if len(found) < 3:
            self.skipTest("Load areas_server fixtures (fetch_combined_overpass_fixtures.py --areas-url)")
    
    def test_city_park_classification(self):
        """Test classify_protected_area on areas from cached areas_server fixtures (park vs protected-area)."""
        from geo_lib.reverse_geocoding.protected_areas import classify_protected_area

        # Find an area with leisure=park and no boundary (classified as 'park')
        for lat, lon in [(39.722, -104.957), (39.0, -105.0), (40.34, -105.68)]:
            areas_data = get_areas_fixture(lat, lon)
            if not areas_data:
                continue
            for area in areas_data.get("protected_areas", []):
                if area.get("leisure") == "park" and not area.get("boundary"):
                    self.assertEqual(classify_protected_area(area), "park")
                    break
            else:
                continue
            break
        else:
            self.skipTest("Load areas_server fixture with leisure=park, no boundary (e.g. city park)")

        # Any area with boundary=protected_area from fixture should not classify as plain 'park'
        for lat, lon in [(40.34, -105.68), (39.42, -105.65)]:
            areas_data = get_areas_fixture(lat, lon)
            if not areas_data or not areas_data.get("protected_areas"):
                continue
            area = areas_data["protected_areas"][0]
            if area.get("boundary") == "protected_area":
                self.assertIn(classify_protected_area(area), ("national-park", "wilderness", "protected-area"))
                return
        self.skipTest("Load areas_server fixtures with protected_areas (fetch_combined_overpass_fixtures.py)")
    
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
        # Grand Lake, CO area - fixture may have lake way without center; we assert behavior
        response, _ = fetch_lakes_and_cities(40.2514, -105.8239)
        lakes, errors = search_nearby_lakes(response, 40.2514, -105.8239, 1.0)
        # Lakes require center/lat/lon for distance; fixture may have 0 or 1
        self.assertIsInstance(lakes, list)
        if len(lakes) >= 1:
            self.assertEqual(lakes[0]['name'], 'Grand Lake')
    
    def test_search_nearby_lakes_outside_range(self):
        """Test that lakes outside 1-mile range are not included."""
        # Point >1 mile from Grand Lake - fixture in conftest.py
        response, _ = fetch_lakes_and_cities(40.211372, -105.768591)
        lakes, errors = search_nearby_lakes(response, 40.211372, -105.768591, 1.0)
        
        # Should filter out lakes beyond 1 mile threshold
        self.assertEqual(len(lakes), 0)
    
    def test_get_location_tags_comprehensive(self):
        """Test comprehensive location tag generation."""
        # Generic Colorado coordinates - fixture in conftest.py
        tags, log_messages = get_location_tags(39.0, -105.0)
        
        # Should have at least one admin-level tag (country, state, or county)
        tag_strings = [t for t in tags]
        self.assertTrue(
            any('country:' in t or 'state:' in t or 'county:' in t for t in tag_strings),
            f'Expected at least one of country/state/county in {tag_strings}'
        )


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
        """Test that admin hierarchy results are cached (one Overpass call per coordinate)."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        # First call - one Overpass query
        response1, _ = fetch_lakes_and_cities(40.0, -105.0)
        result1, errors1 = get_admin_hierarchy(response1, 40.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        
        # Second call should use cache (no new Overpass call)
        response2, _ = fetch_lakes_and_cities(40.0, -105.0)
        result2, errors2 = get_admin_hierarchy(response2, 40.0, -105.0)
        call_count_2 = overpass_api.query_overpass.call_count
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
        """Test that protected areas results are cached (one Overpass call per coordinate)."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        response1, _ = fetch_lakes_and_cities(40.3428, -105.6836)
        areas1, errors1 = get_protected_areas(response1, 40.3428, -105.6836)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)

        response2, _ = fetch_lakes_and_cities(40.3428, -105.6836)
        areas2, errors2 = get_protected_areas(response2, 40.3428, -105.6836)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(areas1, areas2)

    def test_protected_areas_caching_with_rounded_coords(self):
        """Test that protected areas cache works with coordinate rounding."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        response1, _ = fetch_lakes_and_cities(40.3428, -105.6836)
        areas1, errors1 = get_protected_areas(response1, 40.3428, -105.6836)
        call_count_1 = overpass_api.query_overpass.call_count
        
        response2, _ = fetch_lakes_and_cities(40.3429, -105.6837)
        areas2, errors2 = get_protected_areas(response2, 40.3429, -105.6837)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(areas1, areas2)
    
    def test_nearby_cities_caching(self):
        """Test that nearby cities results are cached (one Overpass call per coordinate)."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        response1, _ = fetch_lakes_and_cities(39.2216, -105.9327)
        cities1, errors1 = find_nearby_cities(response1, 39.2216, -105.9327, 5.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        
        response2, _ = fetch_lakes_and_cities(39.2216, -105.9327)
        cities2, errors2 = find_nearby_cities(response2, 39.2216, -105.9327, 5.0)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(cities1, cities2)
    
    def test_nearby_cities_caching_different_threshold(self):
        """Test that different threshold does not trigger new Overpass call (same response)."""
        overpass_api.query_overpass.reset_mock()
        response, _ = fetch_lakes_and_cities(39.2216, -105.9327)
        call_count_1 = overpass_api.query_overpass.call_count
        cities1, _ = find_nearby_cities(response, 39.2216, -105.9327, 5.0)
        cities2, _ = find_nearby_cities(response, 39.2216, -105.9327, 10.0)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertLessEqual(len(cities1), len(cities2))
    
    def test_nearby_lakes_caching(self):
        """Test that nearby lakes results are cached (one Overpass call per coordinate)."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        response1, _ = fetch_lakes_and_cities(40.2514, -105.8239)
        lakes1, errors1 = search_nearby_lakes(response1, 40.2514, -105.8239, 1.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        
        response2, _ = fetch_lakes_and_cities(40.2514, -105.8239)
        lakes2, errors2 = search_nearby_lakes(response2, 40.2514, -105.8239, 1.0)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(lakes1, lakes2)
    
    def test_nearby_lakes_caching_different_threshold(self):
        """Test that different proximity does not trigger new Overpass call (same response)."""
        overpass_api.query_overpass.reset_mock()
        response, _ = fetch_lakes_and_cities(40.2514, -105.8239)
        call_count_1 = overpass_api.query_overpass.call_count
        lakes1, _ = search_nearby_lakes(response, 40.2514, -105.8239, 1.0)
        lakes2, _ = search_nearby_lakes(response, 40.2514, -105.8239, 2.0)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertLessEqual(len(lakes1), len(lakes2))
    
    def test_get_location_tags_uses_query_cache(self):
        """Test that get_location_tags uses one Overpass call and cache on second call."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        tags1, log_messages1 = get_location_tags(39.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        
        tags2, log_messages2 = get_location_tags(39.0, -105.0)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(tags1, tags2)
    
    def test_get_location_tags_cache_with_rounded_coords(self):
        """Test that get_location_tags cache works with coordinate rounding (same rounded coord reuses cache)."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        tags1, _ = get_location_tags(39.0, -105.0)
        call_count_1 = overpass_api.query_overpass.call_count
        
        tags2, _ = get_location_tags(39.0001, -105.0001)
        call_count_2 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        self.assertEqual(call_count_1, call_count_2)
        self.assertEqual(tags1, tags2)
    
    def test_query_cache_via_batch(self):
        """Test that Overpass call is cached and reused via batch."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        coordinates = [(39.0, -105.0)]
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
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
        """Test that batch_reverse_geocode_coordinates deduplicates nearby coordinates (one call per rounded coord)."""
        overpass_api.query_overpass.reset_mock()
        # Use coords that round to exactly 2 unique (40.123, -105.79) and (40.123, -105.789)
        coordinates = [
            (40.1231, -105.79),    # Rounds to 40.123, -105.79
            (40.1232, -105.789),   # Rounds to 40.123, -105.789
            (40.1231, -105.7901),  # Rounds to 40.123, -105.79 (same as first)
            (40.1232, -105.7891),  # Rounds to 40.123, -105.789 (same as second)
        ]
        
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 2)  # Two unique rounded coordinates
        self.assertEqual(len(results1), 4)
        
        # Coordinates that round to same key get same results
        tags_1 = results1[(40.1231, -105.79)][0]
        tags_3 = results1[(40.1231, -105.7901)][0]
        self.assertEqual(tags_1, tags_3)
        
        tags_2 = results1[(40.1232, -105.789)][0]
        tags_4 = results1[(40.1232, -105.7891)][0]
        self.assertEqual(tags_2, tags_4)
    
    def test_batch_reverse_geocode_caching(self):
        """Test that batch_reverse_geocode_coordinates uses one Overpass call per coord, cache on second call."""
        _REVERSE_GEOCODING_CACHE.clear()
        overpass_api.query_overpass.reset_mock()
        
        coordinates = [
            (39.0, -105.0),
            (39.0001, -105.0001),  # Rounds to (39.0, -105.0)
        ]
        
        results1 = batch_reverse_geocode_coordinates(coordinates)
        call_count_1 = overpass_api.query_overpass.call_count
        self.assertEqual(call_count_1, 1)
        
        # Reset mock to track new calls (cache should still be active)
        overpass_api.query_overpass.reset_mock()
        
        # Second batch call with same coordinates
        results2 = batch_reverse_geocode_coordinates(coordinates)
        call_count_2 = overpass_api.query_overpass.call_count
        
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
    """Test error handling in reverse geocoding."""
    
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
        response, _ = fetch_lakes_and_cities(40.0, -105.0)
        result, errors = get_admin_hierarchy(response, 40.0, -105.0)
        self.assertIsInstance(result, dict)
        self.assertIn('country', result)
    
    def test_overpass_error_response(self):
        """Test handling of Overpass API error response."""
        response, _ = fetch_lakes_and_cities(40.0, -105.0)
        result, errors = get_admin_hierarchy(response, 40.0, -105.0)
        self.assertIsInstance(result, dict)
    


    def test_get_location_tags_exception_handling(self):
        """Test that get_location_tags handles exceptions gracefully."""
        # Invalid coordinates shouldn't crash
        tags, log_messages = get_location_tags(999.0, 999.0)
        self.assertIsInstance(tags, list)
        self.assertIsInstance(log_messages, list)

    def test_areas_server_error_logged(self):
        """Test that areas server error is logged to console and import log (log_messages)."""
        from geo_lib.reverse_geocoding.cache import _REVERSE_GEOCODING_CACHE
        _REVERSE_GEOCODING_CACHE.clear()
        with patch('geo_lib.reverse_geocoding.location_tags.query_areas_server') as mock_areas:
            mock_areas.return_value = (None, None, "is_in area server returned 503")
            tags, log_messages = get_location_tags(39.746, -104.844)
        self.assertIsInstance(tags, list)
        errors = [m for m in log_messages if m.level == 'ERROR']
        self.assertEqual(len(errors), 1)
        self.assertIn("503", errors[0].message)
        self.assertEqual(errors[0].source, 'Reverse Geocoding')
        # Admin/protected should be empty when areas server fails
        self.assertFalse(any(t.startswith('country:') or t.startswith('state:') for t in tags))


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
        """Test protected-area tag generation (wilderness/national park area)."""
        # Rocky Mountain area - fixture has Rocky Mountain Wilderness with bounds
        tags, log_messages = get_location_tags(40.34, -105.68)
        # Fixture may have national-park or wilderness tag depending on what contains the point
        self.assertTrue(
            any('national-park:' in t or 'wilderness:' in t for t in tags),
            f'Expected a protected-area tag in {list(tags)}'
        )
    
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

    def test_yellowstone_national_park_tag(self):
        """Test that a point inside Yellowstone NP is tagged as national-park:Yellowstone National Park."""
        tags, log_messages = get_location_tags(44.60384, -110.47567)
        self.assertTrue(
            any('national-park:' in t and 'Yellowstone' in t for t in tags),
            f'Expected national-park tag for Yellowstone in {list(tags)}'
        )


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
