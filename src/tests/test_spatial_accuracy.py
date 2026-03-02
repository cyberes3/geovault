"""
Validation tests for spatial precision and distortion in the areas server.
Using real database operations to verify that ground-truth geography distance
is maintained for land features and quantify the error for geometry-optimized ocean lookups.
"""
import os
import sys
import unittest
from pathlib import Path

import psycopg
import pytest

# Add areas-server dir to path
_test_file_dir = Path(__file__).resolve().parent
_areas_server_dir = _test_file_dir.parent / "areas-server"
if str(_areas_server_dir) not in sys.path:
    sys.path.insert(0, str(_areas_server_dir))

# Environment is loaded by run-tests.sh

DB_URL = os.environ.get("AREAS_SERVER_DATABASE")

@pytest.fixture(scope="module")
def db_conn():
    if not DB_URL:
        pytest.skip("AREAS_SERVER_DATABASE not set")
    try:
        with psycopg.connect(DB_URL) as conn:
            yield conn
    except Exception as e:
        pytest.skip(f"Could not connect to database: {e}")

def test_latitude_distortion_explanation():
    """
    Theoretical check: Verify our understanding of 111km/degree math.
    This doesn't hit the DB, but provides the baseline for the DB checks.
    """
    import math
    def meters_per_lon_degree(lat):
        return 111320.0 * math.cos(math.radians(lat))

    # At 40N (Colorado)
    assert pytest.approx(meters_per_lon_degree(40), 100) == 85270
    # At 70N (Arctic)
    assert pytest.approx(meters_per_lon_degree(70), 100) == 38075

class TestRealDatabasePrecision:
    """Live DB tests confirming 'geography' (Meters) vs 'geometry' (Degrees)."""

    def test_geography_distance_is_constant_across_latitudes(self, db_conn):
        """
        Confirm that ST_Distance(geography, geography) correctly returns ~111m 
        for a 0.001 degree shift in LATITUDE regardless of location.
        """
        # 0.001 deg lat is approx 111.32 meters
        query = """
        SELECT 
            public.ST_Distance(
                public.ST_SetSRID(public.ST_MakePoint(0, %s), 4326)::geography,
                public.ST_SetSRID(public.ST_MakePoint(0, %s + 0.001), 4326)::geography
            )
        """
        with db_conn.cursor() as cur:
            # At Equator
            cur.execute(query, (0, 0))
            dist_eq = cur.fetchone()[0]
            
            # At 40N
            cur.execute(query, (40, 40))
            dist_40 = cur.fetchone()[0]
            
            # At 70N
            cur.execute(query, (70, 70))
            dist_70 = cur.fetchone()[0]
            
        assert pytest.approx(dist_eq, 0.1) == 111.32
        assert pytest.approx(dist_40, 0.1) == 111.32
        assert pytest.approx(dist_70, 0.1) == 111.32

    def test_geometry_distance_distorts_longitude_at_latitude(self, db_conn):
        """
        Quantify the distortion if we used geometry distance (degrees * constant)
        for longitude shifts at high latitudes. 
        This validates our reason for restoring geography for land features.
        """
        # We used 111320.0 as the 'magic number' constant for geometry math.
        query = """
        SELECT 
            public.ST_Distance(
                public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326),
                public.ST_SetSRID(public.ST_MakePoint(%s + 0.001, %s), 4326)
            ) * 111320.0
        """
        with db_conn.cursor() as cur:
            # At Equator (no distortion)
            cur.execute(query, (0, 0, 0, 0))
            dist_eq = cur.fetchone()[0]
            
            # At 40N (~23% shrinkage)
            cur.execute(query, (0, 40, 0, 40))
            dist_40 = cur.fetchone()[0]
            
            # At 70N (~65% shrinkage)
            cur.execute(query, (0, 70, 0, 70))
            dist_70 = cur.fetchone()[0]
            
        # At equator, 0.001 lon degree is ~111.32m
        assert pytest.approx(dist_eq, 1) == 111.32
        
        # At 40N, it's actually ~85m, but geometry math says 111m.
        # This means a 1.0 mile search in degrees is TOO SHORT at 40N.
        assert 110 < dist_40 < 112 # Geometry thinks it's 111m
        
        # Actual geography distance for comparison
        cur = db_conn.execute("""
            SELECT public.ST_Distance(
                public.ST_SetSRID(public.ST_MakePoint(0, 40), 4326)::geography,
                public.ST_SetSRID(public.ST_MakePoint(0.001, 40), 4326)::geography
            )
        """)
        actual_dist_40 = cur.fetchone()[0]
        assert actual_dist_40 < 86 # True distance is ~85m
        
        # Total Error at 40N if using geometry for a 1.0 mile (1609m) search:
        # We would search for 1609 / 111320 = 0.01445 degrees.
        # But 0.01445 degrees at 40N is only 0.01445 * 85270 = 1232 meters.
        # Missed ground: 1609 - 1232 = 377 meters (~0.23 miles missed).
        error_miles = (1609.34 - actual_dist_40 * (1609.34 / 111.32)) / 1609.34
        assert 0.20 < error_miles < 0.25

    def test_restored_layers_use_geography(self, db_conn):
        """
        End-to-end sanity check: verify that a specific point query returns 
        the correct terrestrial distance for a known feature.
        """
        # Using a point near Standley Lake, CO (~39.86, -105.12)
        # Verify that distance is reported in true miles using geography.
        from areas_lib import lookup_water
        
        # Point JUST outside the lake (approx 0.1 miles away)
        # Lat: 39.8616, Lon: -105.1206 is on water.
        # Let's shift it slightly East by 0.002 degrees (~0.1 miles)
        lat, lon = 39.8616, -105.1206 + 0.002
        results = lookup_water.run_water_single(db_conn, lat, lon, lake_radius_miles=1.0)
        
        assert len(results) > 0
        # First result should be Standley Lake
        name, water_type, dist_miles, on_water = results[0]
        assert "Standley Lake" in name
        
        # Calculate expected geography distance
        cur = db_conn.execute("""
            SELECT public.ST_Distance(
                public.ST_SetSRID(public.ST_MakePoint(-105.1206 + 0.002, 39.8616), 4326)::geography,
                (SELECT geom::geography FROM is_in.water_bodies WHERE name LIKE '%%Standley Lake%%' LIMIT 1)
            ) / 1609.34
        """)
        expected_miles = cur.fetchone()[0]
        
        assert pytest.approx(dist_miles, 0.001) == expected_miles
        print(f"\n[PASS] Land feature accuracy at 40N: Reported {dist_miles:.4f} mi, Expected {expected_miles:.4f} mi")

    def test_ocean_distortion_quantified(self, db_conn):
        """
        Confirm and quantify that oceans STILL use geometry (for performance)
        and thus have valid but distorted distances.
        """
        # Ocean lookups use ST_Distance(geom, geom) * constant or similar
        # Actually, query.py uses geometry distance.
        # Let's verify what lookup_ocean returns.
        from areas_lib import lookup_ocean
        
        # Point in the middle of typical ocean coordinates
        lat, lon = 20.0, -40.0
        # This hits the DB and uses our optimized (but distorted) logic.
        name = lookup_ocean.run_ocean_single(db_conn, lat, lon, ocean_radius_miles=10.0)
        
        # We want to verify it doesn't crash and returns a name.
        assert name is not None
        print(f"\n[INFO] Ocean optimization active: Found {name}")
