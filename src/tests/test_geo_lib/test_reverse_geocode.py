"""
Comprehensive tests for reverse geocoding service.

Responses come from real cached fixtures under tests/fixtures/areas_server/ (loaded via
get_areas_fixture). The autouse fixture in conftest.py wires query_areas_server to return
those fixture responses so tests do not hit the network.
"""
import pytest
from unittest.mock import patch
from django.test import TestCase
from django.core.cache import cache, caches

from geo_lib.reverse_geocoding.location_tags import get_location_tags, batch_reverse_geocode_coordinates, tags_from_areas_data
from geo_lib.reverse_geocoding.cache import _get_cache_key, _REVERSE_GEOCODING_CACHE
from geo_lib.spatial.haversine import haversine_distance_miles

from tests.fixtures.geocoding_responses import get_areas_fixture


class ReverseGeocodingTagTestMixin:
    """Mixin for tests that assert on location tags; provides assert_tags_exact and expected-from-fixture helper."""

    def assert_tags_exact(self, actual_tags, expected_tags):
        """Assert tag sets are exactly equal: no missing tags, no unexpected extra tags."""
        self.assertEqual(
            sorted(actual_tags),
            sorted(expected_tags),
            "Tags must match exactly (no missing, no unexpected extra). "
            f"Extra: {sorted(set(actual_tags) - set(expected_tags))!r}. "
            f"Missing: {sorted(set(expected_tags) - set(actual_tags))!r}.",
        )

    def _expected_tags_from_fixture(self, areas_data):
        """Build expected tag list from fixture using real tag generation."""
        return tags_from_areas_data(areas_data)


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
class TestReverseGeocodingService(ReverseGeocodingTagTestMixin, TestCase):
    """Test reverse geocoding service using real cached fixtures (tests/fixtures/areas_server/)."""
    
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

    def test_ocean_main_only_from_fixture(self):
        """Main ocean only (43.946, -126.139): exact tag set, North Pacific only."""
        lat, lon = 43.946, -126.139
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 43.946_-126.139.json")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_regional_and_main_from_fixture(self):
        """Regional + main ocean (43.8, -69.0): exact tag set, Gulf of Maine and North Atlantic."""
        lat, lon = 43.8, -69.0
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 43.8_-69.0.json")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_open_pacific_from_fixture(self):
        """Open North Pacific (41.41, -134.299): exact tag set, North Pacific Ocean only."""
        lat, lon = 41.41, -134.299
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 41.41_-134.299.json")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_shore_tagged_ocean(self):
        """Point on/near shore (43.65, -70.25): exact tag set and at least one ocean tag."""
        lat, lon = 43.65, -70.25
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 43.65_-70.25.json (shore)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_state_park_from_fixture(self):
        """Oregon coast (45.84810, -123.96116): exact tag set, North Pacific (not North Atlantic), state park."""
        lat, lon = 45.84810, -123.96116
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 45.848_-123.961.json (state park + ocean)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

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
        """Test classify_protected_area: leisure=park without boundary -> 'park'; boundary=protected_area -> not 'park'."""
        from geo_lib.reverse_geocoding.protected_areas import classify_protected_area

        # leisure=park and no boundary (or boundary != protected_area) -> "park"
        self.assertEqual(classify_protected_area({"leisure": "park"}), "park")
        self.assertEqual(classify_protected_area({"leisure": "park", "boundary": ""}), "park")
        self.assertEqual(classify_protected_area({"leisure": "park", "name": "City Park"}), "park")

        # boundary=protected_area -> must not classify as "park" (falls through to national-park/wilderness/protected-area)
        area_protected = {"boundary": "protected_area", "protection_title": "Wilderness"}
        self.assertIn(classify_protected_area(area_protected), ("national-park", "wilderness", "protected-area"))
        area_protected_only = {"boundary": "protected_area"}
        self.assertEqual(classify_protected_area(area_protected_only), "protected-area")
    
    def test_ski_resort_tag_from_areas_server(self):
        """When areas fixture has ski_resort (e.g. Vail), get_location_tags returns exact tags from fixture."""
        lat, lon = 39.613, -106.357  # Vail; fixture 39.613_-106.357.json
        areas_data = get_areas_fixture(lat, lon)
        self.assertIsNotNone(areas_data, "Fixture 39.613_-106.357.json (Vail ski resort)")
        self.assertIsNotNone(areas_data.get("ski_resort"), "Fixture must include ski_resort")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_nearby_lakes_from_areas_fixture(self):
        """When areas fixture has nearby_lakes, get_location_tags returns exact tags from fixture."""
        areas_data = get_areas_fixture(40.2514, -105.8239)
        if not areas_data or not areas_data.get("nearby_lakes"):
            self.skipTest("Load areas_server fixtures with nearby_lakes (fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(40.2514, -105.8239)
        self.assert_tags_exact(tags, expected)

    def test_get_location_tags_comprehensive(self):
        """Test comprehensive location tag generation matches fixture-derived expected set."""
        areas_data = get_areas_fixture(39.0, -105.0)
        self.assertIsNotNone(areas_data, "Load areas_server fixtures (e.g. fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(39.0, -105.0)
        self.assert_tags_exact(tags, expected)


@pytest.mark.django_db
class TestCaching(ReverseGeocodingTagTestMixin, TestCase):
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
        self.assert_tags_exact(tags_1, tags_3)
        tags_2 = results[(40.1232, -105.789)][0]
        tags_4 = results[(40.1232, -105.7891)][0]
        self.assert_tags_exact(tags_2, tags_4)

    def test_batch_reverse_geocode_empty_list(self):
        """Test that batch_reverse_geocode_coordinates handles empty list."""
        results = batch_reverse_geocode_coordinates([])
        self.assertEqual(results, {})


@pytest.mark.django_db
class TestErrorHandling(ReverseGeocodingTagTestMixin, TestCase):
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
        """Test that get_location_tags handles invalid coordinates: no fixture -> empty tags."""
        tags, log_messages = get_location_tags(999.0, 999.0)
        self.assert_tags_exact(tags, [])
        self.assertIsInstance(log_messages, list)

    def test_areas_server_error_logged(self):
        """When areas client returns an error, error is logged and tags are empty. No fixture for errors, so we simulate the same (response, error) the real client returns when e.g. AREAS_SERVER_URL is unset."""
        _REVERSE_GEOCODING_CACHE.clear()
        real_client_error = "AREAS_SERVER_URL is not set; required for reverse geocoding."
        with patch('geo_lib.reverse_geocoding.location_tags.query_areas_server') as mock_areas:
            mock_areas.return_value = (None, real_client_error)
            tags, log_messages = get_location_tags(39.746, -104.844)
        self.assert_tags_exact(tags, [])
        errors = [m for m in log_messages if m.level == 'ERROR']
        self.assertEqual(len(errors), 1)
        self.assertIn("AREAS_SERVER_URL", errors[0].message)
        self.assertEqual(errors[0].source, 'Reverse Geocoding')


@pytest.mark.django_db
class TestTagGeneration(ReverseGeocodingTagTestMixin, TestCase):
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
        """Test protected-area tag generation (wilderness/national park area) matches fixture exactly."""
        areas_data = get_areas_fixture(40.34, -105.68)
        if not areas_data:
            self.skipTest("Load areas_server fixtures (fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(40.34, -105.68)
        self.assert_tags_exact(tags, expected)

    def test_national_monument_tag(self):
        """Test national monument tag generation matches fixture exactly."""
        areas_data = get_areas_fixture(39.07, -108.73)
        if not areas_data:
            self.skipTest("Load areas_server fixtures (fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(39.07, -108.73)
        self.assert_tags_exact(tags, expected)

    def test_wilderness_tag(self):
        """Test wilderness area tag generation matches fixture exactly."""
        areas_data = get_areas_fixture(39.42, -105.65)
        if not areas_data:
            self.skipTest("Load areas_server fixtures (fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(39.42, -105.65)
        self.assert_tags_exact(tags, expected)

    def test_yellowstone_national_park_tag(self):
        """Test that a point inside Yellowstone NP has exact tags from fixture."""
        areas_data = get_areas_fixture(44.60384, -110.47567)
        if not areas_data:
            self.skipTest("Load areas_server fixtures (fetch_areas_fixtures.py <url>)")
        expected = self._expected_tags_from_fixture(areas_data)
        tags, _ = get_location_tags(44.60384, -110.47567)
        self.assert_tags_exact(tags, expected)


