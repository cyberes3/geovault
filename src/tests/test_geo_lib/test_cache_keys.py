from django.test import SimpleTestCase

from geo_lib.perf.cache_keys import GeocodeKey, TileKey


class TestGeocodeKey(SimpleTestCase):
    def test_includes_mode_and_query_hash(self):
        key = str(GeocodeKey("maptiler", " Denver "))
        self.assertTrue(key.startswith("reverse_geocoding:maptiler:"))
        self.assertEqual(key, str(GeocodeKey("maptiler", "denver")))
        self.assertNotEqual(key, str(GeocodeKey("google", "denver")))

    def test_requires_mode(self):
        with self.assertRaises(ValueError):
            GeocodeKey("", "denver")


class TestTileKey(SimpleTestCase):
    def test_includes_source_and_zxy(self):
        key = str(TileKey("osm", 4, 2, 3))
        self.assertEqual(key, "tile:osm:4:2:3")

    def test_requires_source(self):
        with self.assertRaises(ValueError):
            TileKey("", 1, 2, 3)
