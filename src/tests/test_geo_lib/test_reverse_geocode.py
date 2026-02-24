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

from geo_lib.reverse_geocoding.location_tags import get_location_tags, batch_reverse_geocode_coordinates
from geo_lib.reverse_geocoding.cache import _get_cache_key, _REVERSE_GEOCODING_CACHE
from geo_lib.spatial.haversine import haversine_distance_miles


class ReverseGeocodingTagTestMixin:
    """Mixin for tests that assert on location tags."""

    def assert_tags_exact(self, actual_tags, expected_tags):
        """Assert tag sets are exactly equal: no missing tags, no unexpected extra tags."""
        self.assertEqual(
            sorted(actual_tags),
            sorted(expected_tags),
            "Tags must match exactly (no missing, no unexpected extra). "
            f"Extra: {sorted(set(actual_tags) - set(expected_tags))!r}. "
            f"Missing: {sorted(set(expected_tags) - set(actual_tags))!r}.",
        )


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
    """One test per fixture coordinate; expected tags hardcoded in each test."""

    def setUp(self):
        cache.clear()
        try:
            caches['reverse_geocoding'].clear()
        except Exception:
            pass

    def tearDown(self):
        cache.clear()

    def test_crossville(self):
        lat, lon = 35.89684, -85.00500
        expected = [
            'city:Crossville',
            'country:United States of America',
            'county:Cumberland County',
            'lake:Byrd Lake',
            'protected-area:Cumberland Mountain State Park',
            'state:Tennessee',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_nashville_bells_bend(self):
        lat, lon = 36.156, -86.925
        expected = [
            'city:Nashville',
            'country:United States of America',
            'county:Davidson County',
            'lake:Cheatham Lake',
            'protected-area:Bells Bend Park',
            'state:Tennessee',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_san_francisco_ocean_city(self):
        lat, lon = 37.75214, -122.50269
        expected = [
            'city:San Francisco',
            'country:United States of America',
            'county:San Francisco',
            'ocean:North Pacific Ocean',
            'state:California',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_bents_old_fort_national_historic_site(self):
        lat, lon = 38.03982, -103.42472
        expected = [
            'country:United States of America',
            'county:Otero County',
            "park:Bent's Old Fort National Historic Site",
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_point_reyes_national_seashore(self):
        lat, lon = 38.05677, -122.87860
        expected = [
            'country:United States of America',
            'county:Marin County',
            'protected-area:Point Reyes National Seashore',
            'state:California',
            'wilderness:Phillip Burton Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_curecanti_national_recreation_area(self):
        lat, lon = 38.4627240263864, -107.17141673546334
        expected = [
            'country:United States of America',
            'county:Gunnison County',
            'lake:Blue Mesa Reservoir',
            'national-recreation-area:Curecanti National Recreation Area',
            'protected-area:BLM - Gunnison Field Office',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_san_isabel_national_forest(self):
        lat, lon = 38.62375, -105.83993
        expected = [
            'country:United States of America',
            'county:Fremont County',
            'national-forest:San Isabel National Forest',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_mueller_state_park(self):
        lat, lon = 38.89178, -105.17907
        expected = [
            'country:United States of America',
            'county:Teller County',
            'lake:Dragonfly',
            'lake:Lost Pond',
            'lake:Peak View Pond',
            'state-park:Mueller State Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_colorado_national_monument(self):
        lat, lon = 39.05548, -108.69338
        expected = [
            'country:United States of America',
            'county:Mesa County',
            'national-monument:Colorado National Monument',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_pike_national_forest(self):
        lat, lon = 39.0, -105.0
        expected = [
            'country:United States of America',
            'county:El Paso County',
            'lake:Grace Lake',
            'lake:Leo Lake',
            'lake:Sapphire Lake',
            'national-forest:Pike National Forest',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_fairplay(self):
        lat, lon = 39.22337887866515, -105.94799963185382
        expected = [
            'city:Fairplay',
            'country:United States of America',
            'county:Park County',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_lost_creek_wilderness_shawnee(self):
        lat, lon = 39.38965, -105.58278
        expected = [
            'city:Shawnee',
            'country:United States of America',
            'county:Park County',
            'national-forest:Pike National Forest',
            'state:Colorado',
            'wilderness:Lost Creek Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_lost_creek_wilderness_park_county(self):
        lat, lon = 39.42, -105.65
        expected = [
            'country:United States of America',
            'county:Park County',
            'national-forest:Pike National Forest',
            'state:Colorado',
            'wilderness:Lost Creek Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_south_valley_park_open_space(self):
        lat, lon = 39.563, -105.15
        expected = [
            'country:United States of America',
            'county:Jefferson County',
            'lake:Ken Caryl Reservoir',
            'lake:Mann Reservoir',
            'protected-area:South Valley Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_vail_ski_resort(self):
        lat, lon = 39.613, -106.357
        expected = [
            'city:Vail',
            'country:United States of America',
            'county:Eagle County',
            'national-forest:White River National Forest',
            'ski-resort:Vail',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_matthews_winters_park(self):
        lat, lon = 39.68960, -105.21190
        expected = [
            'city:Morrison',
            'country:United States of America',
            'county:Jefferson County',
            'protected-area:Matthews/Winters Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_william_frederick_hayden_park(self):
        lat, lon = 39.70073, -105.17302
        expected = [
            'city:Lakewood',
            'country:United States of America',
            'county:Jefferson County',
            'protected-area:William Frederick Hayden Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_glendale_james_manley_park(self):
        lat, lon = 39.72296, -104.95785
        expected = [
            'city:Glendale',
            'country:United States of America',
            'county:Denver',
            'lake:El Pomar Waterway',
            'lake:Four Towers Pool',
            'lake:Steppe Garden Waterway',
            'park:James N. Manley Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_denver_city_park(self):
        lat, lon = 39.74498318354445, -104.95147156373426
        expected = [
            'city:Denver',
            'country:United States of America',
            'county:Denver',
            'lake:Duck Lake',
            'lake:Ferril Lake',
            'lake:Lily Pond',
            'park:City Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_denver_admin(self):
        lat, lon = 39.75832221022334, -104.92042641825462
        expected = [
            'country:United States of America',
            'county:Denver',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_standley_lake_on_lake_and_park(self):
        lat, lon = 39.86543015343607, -105.12295898204981
        expected = [
            'city:Westminster',
            'country:United States of America',
            'county:Jefferson County',
            'lake:Standley Lake',
            'protected-area:Standley Lake Regional Park',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_two_ponds_national_wildlife_refuge(self):
        lat, lon = 39.84041664204802, -105.10241566871551
        expected = [
            'city:Arvada',
            'country:United States of America',
            'county:Jefferson County',
            'lake:Pomona Lake',
            'lake:Pomona Lake Number 2',
            'national-wildlife-refuge:Two Ponds National Wildlife Refuge',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_breckenridge_ski_resort(self):
        lat, lon = 39.47371, -106.07716
        expected = [
            'city:Breckenridge',
            'country:United States of America',
            'county:Summit County',
            'lake:Sawmill Reservoir',
            'national-forest:White River National Forest',
            'ski-resort:Breckenridge',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_broomfield(self):
        lat, lon = 40.0, -105.0
        expected = [
            'country:United States of America',
            'county:Broomfield',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_indian_peaks_wilderness(self):
        lat, lon = 40.10763, -105.60469
        expected = [
            'country:United States of America',
            'county:Boulder County',
            'lake:Coney Lake',
            'lake:Upper Coney Lake',
            'state:Colorado',
            'wilderness:Indian Peaks Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_medicine_bow_routt_national_forest(self):
        lat, lon = 40.16692, -106.19653
        expected = [
            'country:United States of America',
            'county:Grand County',
            'national-forest:Medicine Bow-Routt National Forest',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_rocky_mountain_national_park(self):
        lat, lon = 40.211, -105.769
        expected = [
            'country:United States of America',
            'county:Grand County',
            'national-park:Rocky Mountain National Park',
            'state:Colorado',
            'wilderness:Rocky Mountain Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_grand_lake_nearby_lakes(self):
        lat, lon = 40.24301, -105.82766
        expected = [
            'city:Grand Lake',
            'country:United States of America',
            'county:Grand County',
            'lake:Grand Lake',
            'lake:Shadow Mountain Lake',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_arapaho_national_forest(self):
        lat, lon = 40.26762, -106.03746
        expected = [
            'country:United States of America',
            'county:Grand County',
            'national-forest:Arapaho National Forest',
            'state:Colorado',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_rocky_mountain_np_larimer_lakes(self):
        lat, lon = 40.34303, -105.68435
        expected = [
            'country:United States of America',
            'county:Larimer County',
            'lake:Fern Lake',
            'lake:Primrose Pond',
            'lake:Spruce Lake',
            'national-park:Rocky Mountain National Park',
            'state:Colorado',
            'wilderness:Rocky Mountain Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_north_pacific_open(self):
        lat, lon = 41.41, -134.299
        expected = [
            'ocean:North Pacific Ocean',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_mcpherson_nebraska(self):
        lat, lon = 41.68187473276889, -101.36391746047425
        expected = [
            'country:United States of America',
            'county:McPherson County',
            'lake:Sand Beach Lake',
            'lake:Stickney Lake',
            'state:Nebraska',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_crescent_lake_national_wildlife_refuge(self):
        lat, lon = 41.72390, -102.31360
        expected = [
            'country:United States of America',
            'county:Garden County',
            'lake:Deer Lake',
            'lake:Swede Lake',
            'national-wildlife-refuge:Crescent Lake National Wildlife Refuge',
            'state:Nebraska',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_morrill_nebraska(self):
        lat, lon = 41.729, -102.872
        expected = [
            'country:United States of America',
            'county:Morrill County',
            'state:Nebraska',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_blue_hills_reservation_state_park(self):
        lat, lon = 42.209, -71.108
        expected = [
            'city:Milton',
            'country:United States of America',
            'county:Norfolk County',
            "lake:Houghton's Pond",
            'lake:Ponkapoag Pond',
            'state-park:Blue Hills Reservation',
            'state:Massachusetts',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_rushville_nebraska(self):
        lat, lon = 42.729, -102.417
        expected = [
            'city:Rushville',
            'country:United States of America',
            'county:Sheridan County',
            'state:Nebraska',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_jackson_hole_ski_resort(self):
        lat, lon = 43.591287434883135, -110.85327582346859
        expected = [
            'city:Teton Village',
            'country:United States of America',
            'county:Teton County',
            'national-forest:Bridger-Teton National Forest',
            'ski-resort:Jackson Hole Mountain Resort',
            'state:Wyoming',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_south_portland_ocean_shore(self):
        lat, lon = 43.65, -70.25
        expected = [
            'city:South Portland',
            'country:United States of America',
            'county:Cumberland County',
            'ocean:Gulf of Maine',
            'ocean:North Atlantic Ocean',
            'state:Maine',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_matinicus_ocean_regional_main(self):
        lat, lon = 43.8, -69.0
        expected = [
            'city:Matinicus Isle Plantation',
            'country:United States of America',
            'county:Knox County',
            'ocean:Gulf of Maine',
            'ocean:North Atlantic Ocean',
            'state:Maine',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_oregon_dunes_nra_state_park_ocean(self):
        lat, lon = 43.91095153533744, -124.1260278900942
        expected = [
            'city:Dunes City',
            'country:United States of America',
            'county:Lane County',
            'lake:Woahink Lake',
            'national-forest:Siuslaw National Forest',
            'national-recreation-area:Oregon Dunes National Recreation Area',
            'state:Oregon',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_ocean_main_only_north_pacific(self):
        lat, lon = 43.946, -126.139
        expected = [
            'ocean:North Pacific Ocean',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_yellowstone_national_park(self):
        lat, lon = 44.604, -110.476
        expected = [
            'country:United States of America',
            'county:Park County',
            'national-park:Yellowstone National Park',
            'state:Wyoming',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)

    def test_cannon_beach_ocean(self):
        lat, lon = 45.84810, -123.96116
        expected = [
            'city:Cannon Beach',
            'country:United States of America',
            'county:Clatsop County',
            'ocean:North Pacific Ocean',
            'protected-area:Arcadia Beach State Recreation Site',
            'state:Oregon',
        ]
        tags, _ = get_location_tags(lat, lon)
        self.assert_tags_exact(tags, expected)
        self.assertLessEqual(sum(1 for t in tags if t.startswith('ocean:')), 2)

    def test_pictured_rocks_national_lakeshore(self):
        lat, lon = 46.56804, -86.31349
        expected = [
            'city:Burt Township',
            'country:United States of America',
            'county:Alger County',
            'lake:Beaver Lake',
            'national-lakeshore:Pictured Rocks National Lakeshore (Federal Unit)',
            'state:Michigan',
            'wilderness:Beaver Basin Wilderness',
        ]
        tags, _ = get_location_tags(lat, lon)
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
        """Same rounded coord yields same tags (two pairs that round to same fixture key)."""
        coordinates = [
            (39.72296, -104.95785),  # rounds to 39.723, -104.958
            (39.723, -104.958),
            (39.74498318354445, -104.95147156373426),  # rounds to 39.745, -104.951
            (39.745, -104.951),
        ]
        results = batch_reverse_geocode_coordinates(coordinates)
        self.assertEqual(len(results), 4)
        self.assert_tags_exact(results[(39.72296, -104.95785)][0], results[(39.723, -104.958)][0])
        self.assert_tags_exact(results[(39.74498318354445, -104.95147156373426)][0], results[(39.745, -104.951)][0])

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
