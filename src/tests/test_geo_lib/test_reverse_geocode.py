"""
Comprehensive tests for reverse geocoding service.

The autouse fixture in conftest.py mocks query_areas_server (admin, protected_areas,
nearby_lakes, ocean from fixtures) automatically.
"""
import pytest
from unittest.mock import patch
from django.test import TestCase
from django.core.cache import cache, caches

from geo_lib.reverse_geocoding.location_tags import get_location_tags, batch_reverse_geocode_coordinates
from geo_lib.reverse_geocoding.cache import _get_cache_key, _REVERSE_GEOCODING_CACHE
from geo_lib.spatial.haversine import haversine_distance_miles

from tests.fixtures.geocoding_responses import get_areas_fixture


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
class TestReverseGeocodingService(TestCase):
    """Test reverse geocoding service with mocked areas server."""
    
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
        # Use a precise coordinate so the fixture unambiguously maps to one county (Park County, CO).
        areas = get_areas_fixture(39.22337887866515, -105.94799963185382)
        self.assertIsNotNone(areas, "Load areas_server fixtures (e.g. fetch_areas_fixtures.py <url>)")
        admin = areas["admin_hierarchy"]
        self.assertEqual(admin["country"], "United States of America")
        self.assertEqual(admin["state"], "Colorado")
        self.assertEqual(admin["county"], "Park County")
    
    def test_protected_areas_query(self):
        """Test protected areas from cached areas_server fixture."""
        areas_data = get_areas_fixture(40.34, -105.68)
        self.assertIsNotNone(areas_data, "Load areas_server fixtures (e.g. fetch_areas_fixtures.py <url>)")
        protected = areas_data["protected_areas"]
        self.assertGreaterEqual(len(protected), 1)
        names = [a["name"] for a in protected]
        self.assertTrue(
            any("Rocky Mountain" in n for n in names),
            f"Expected Rocky Mountain area in {names}",
        )

    def test_ocean_tag_from_areas_fixture(self):
        """When areas fixture includes ocean (string or list), get_location_tags returns ocean:<name> tag(s)."""
        areas_data = get_areas_fixture(43.911, -124.125)
        self.assertIsNotNone(areas_data, "Load areas_server fixtures with ocean (43.911_-124.125.json)")
        self.assertIn("ocean", areas_data)
        ocean = areas_data["ocean"]
        if isinstance(ocean, list):
            self.assertIn("Pacific Ocean", ocean)
        else:
            self.assertEqual(ocean, "Pacific Ocean")
        tags, _ = get_location_tags(43.911, -124.125)
        self.assertIn("ocean:Pacific Ocean", tags, f"Expected ocean tag in {tags}")

    def test_two_ocean_tags_from_areas_fixture(self):
        """When areas fixture has two oceans (e.g. sub-region + main), get_location_tags returns two ocean:* tags."""
        # Gulf of Maine / North Atlantic: use coordinate that rounds to fixture 43.8_-69.0.json
        lat, lon = 43.8, -69.0
        areas_data = get_areas_fixture(lat, lon)
        if areas_data is None:
            self.skipTest(
                "Add areas_server fixture 43.8_-69.0.json with \"ocean\": [\"Gulf of Maine\", \"North Atlantic Ocean\"]"
            )
        ocean = areas_data.get("ocean") or []
        ocean_list = ocean if isinstance(ocean, list) else [ocean]
        self.assertGreaterEqual(len(ocean_list), 2, "Fixture should have two ocean names")
        tags, _ = get_location_tags(lat, lon)
        self.assertIn("ocean:Gulf of Maine", tags, f"Expected ocean:Gulf of Maine in {tags}")
        self.assertIn("ocean:North Atlantic Ocean", tags, f"Expected ocean:North Atlantic Ocean in {tags}")

    def test_open_pacific_ocean_from_fixture(self):
        """Open North Pacific coordinate (41.41, -134.30) returns ocean containing North Pacific Ocean (GOaS)."""
        lat, lon = 41.41037324278657, -134.2993241551787
        areas_data = get_areas_fixture(lat, lon)
        if areas_data is None:
            self.skipTest(
                "Add areas_server fixture for 41.41_-134.299.json with \"ocean\": [\"North Pacific Ocean\"]"
            )
        self.assertIn("ocean", areas_data)
        ocean = areas_data["ocean"]
        ocean_list = ocean if isinstance(ocean, list) else [ocean]
        self.assertGreater(len(ocean_list), 0, "Fixture should have at least one ocean name")
        self.assertIn("North Pacific Ocean", ocean_list, f"Expected North Pacific Ocean in {ocean_list}")
        tags, _ = get_location_tags(lat, lon)
        self.assertIn("ocean:North Pacific Ocean", tags, f"Expected ocean:North Pacific Ocean in {tags}")
    
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
            self.skipTest("Load areas_server fixtures (fetch_areas_fixtures.py <url>)")
    
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
        self.skipTest("Load areas_server fixtures with protected_areas (fetch_areas_fixtures.py)")
    
    def test_ski_resort_tag_from_areas_server(self):
        """When query_areas_server returns ski_resort, get_location_tags includes ski-resort:<name>."""
        _REVERSE_GEOCODING_CACHE.clear()
        with patch('geo_lib.reverse_geocoding.location_tags.query_areas_server') as mock_areas:
            mock_areas.return_value = (
                {'country': 'USA', 'state': 'Colorado', 'county': 'Eagle', 'city': 'Vail'},
                [],
                [],
                [],
                'Vail',
                None,
            )
            tags, _ = get_location_tags(39.64, -106.37)
        self.assertIn('ski-resort:Vail', tags, f'Expected ski-resort:Vail in {tags}')

    def test_nearby_lakes_from_areas_fixture(self):
        """When areas fixture has nearby_lakes, get_location_tags returns lake tags."""
        areas_data = get_areas_fixture(40.2514, -105.8239)
        if not areas_data or not areas_data.get("nearby_lakes"):
            self.skipTest("Load areas_server fixtures with nearby_lakes (fetch_areas_fixtures.py <url>)")
        tags, _ = get_location_tags(40.2514, -105.8239)
        lake_tags = [t for t in tags if t.startswith("lake:")]
        self.assertGreater(len(lake_tags), 0, f"Expected at least one lake tag from fixture in {tags}")

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
    
    def test_batch_reverse_geocode_deduplication(self):
        """Test that batch_reverse_geocode_coordinates deduplicates nearby coordinates (same rounded coord -> same result)."""
        coordinates = [
            (40.1231, -105.79),
            (40.1232, -105.789),
            (40.1231, -105.7901),  # Same rounded as first
            (40.1232, -105.7891),  # Same rounded as second
        ]
        results = batch_reverse_geocode_coordinates(coordinates)
        self.assertEqual(len(results), 4)
        tags_1 = results[(40.1231, -105.79)][0]
        tags_3 = results[(40.1231, -105.7901)][0]
        self.assertEqual(tags_1, tags_3)
        tags_2 = results[(40.1232, -105.789)][0]
        tags_4 = results[(40.1232, -105.7891)][0]
        self.assertEqual(tags_2, tags_4)

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
    
    def test_get_location_tags_exception_handling(self):
        """Test that get_location_tags handles exceptions gracefully."""
        # Invalid coordinates shouldn't crash
        tags, log_messages = get_location_tags(999.0, 999.0)
        self.assertIsInstance(tags, list)
        self.assertIsInstance(log_messages, list)

    def test_areas_server_error_logged(self):
        """Test that areas server error is logged to console and import log (log_messages)."""
        _REVERSE_GEOCODING_CACHE.clear()
        with patch('geo_lib.reverse_geocoding.location_tags.query_areas_server') as mock_areas:
            mock_areas.return_value = (None, None, [], [], None, "is_in area server returned 503")
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


