#!/usr/bin/env python3
"""
Debug why a lat/lon returns no protected_areas: run ST_Contains (as the API does)
and ST_DWithin to list any protected areas near the point.

Usage (from src/areas-server):
  ./venv/bin/python scripts/debug_protected_at_point.py DATABASE_URL 39.86161999885882 -105.12065936657157
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from config import SCHEMA

TABLE = "protected_areas"
MILES_TO_M = 1609.34


def main() -> int:
    parser = argparse.ArgumentParser(description="Debug protected_areas at a point")
    parser.add_argument("database", type=str, help="PostgreSQL connection string")
    parser.add_argument("lat", type=float, help="Latitude")
    parser.add_argument("lon", type=float, help="Longitude")
    parser.add_argument("--radius-miles", type=float, default=2.0, help="Radius for nearby search (default 2)")
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: database URL must be non-empty", file=sys.stderr)
        return 1

    radius_m = args.radius_miles * MILES_TO_M
    lat, lon = args.lat, args.lon

    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            # 1) Exact same query as the API: point inside polygon
            cur.execute(
                f"""
                SELECT osm_id, name, tags
                FROM {SCHEMA}.{TABLE}
                WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
                LIMIT 10
                """,
                (lon, lat),
            )
            contains_rows = cur.fetchall()

            # 2) Any protected area within radius (with distance) to see if data exists but point is outside
            cur.execute(
                f"""
                SELECT osm_id, name,
                       (public.ST_Distance(public.geography(geom), public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))) / %s)::numeric(10,4) AS distance_miles
                FROM {SCHEMA}.{TABLE}
                WHERE public.ST_DWithin(public.geography(geom), public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326)), %s)
                ORDER BY distance_miles
                LIMIT 15
                """,
                (lon, lat, MILES_TO_M, lon, lat, radius_m),
            )
            nearby_rows = cur.fetchall()

    print(f"Point: lat={lat}, lon={lon}")
    print()
    print("1) ST_Contains (what the API uses) – areas that contain the point:")
    if not contains_rows:
        print("   (none)")
    else:
        for r in contains_rows:
            print(f"   osm_id={r[0]} name={r[1]} tags={r[2]}")
    print()
    print(f"2) Protected areas within {args.radius_miles} miles (nearest first):")
    if not nearby_rows:
        print("   (none – no protected_areas in DB near this point)")
    else:
        for r in nearby_rows:
            print(f"   osm_id={r[0]} name={r[1]} distance_miles={r[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
