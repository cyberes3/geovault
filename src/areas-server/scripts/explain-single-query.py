#!/usr/bin/env python3
"""
Run EXPLAIN (ANALYZE) on the single-point areas query for a given lat/lon.
Use this to see which parts of the query are slow and whether indexes are used.

Usage (from src/areas-server):
  ./venv/bin/python scripts/explain-single-query.py DATABASE_URL 40.34 -105.68
  ./venv/bin/python scripts/explain-single-query.py DATABASE_URL --lat 40.34 --lon -105.68
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from areas_lib import lookup_waterway
from areas_lib.query import _query_single_sql

_MILES_TO_M = 1609.34


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run EXPLAIN (ANALYZE) on the single-point query for the given coordinates."
    )
    parser.add_argument("database", type=str, help="PostgreSQL connection string")
    parser.add_argument("lat", type=float, nargs="?", default=None, help="Latitude")
    parser.add_argument("lon", type=float, nargs="?", default=None, help="Longitude")
    parser.add_argument("--lat", type=float, default=None, dest="lat_opt", help="Latitude")
    parser.add_argument("--lon", type=float, default=None, dest="lon_opt", help="Longitude")
    args = parser.parse_args()
    lat = args.lat_opt if args.lat_opt is not None else args.lat
    lon = args.lon_opt if args.lon_opt is not None else args.lon
    if lat is None or lon is None:
        parser.error("Provide lat and lon (positional or --lat/--lon)")
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: database URL must be non-empty", file=sys.stderr)
        return 1

    include_place = True  # default city_radius_miles=3
    with psycopg.connect(conninfo) as conn:
        include_waterway = lookup_waterway.table_exists(conn)
    sql, _ = _query_single_sql(include_place, include_waterway=include_waterway)
    lake_radius_m = 1.0 * _MILES_TO_M
    ocean_radius_m = 1.0 * _MILES_TO_M
    city_radius_m = 3.0 * _MILES_TO_M
    waterway_radius_m = lookup_waterway.DEFAULT_WATERWAY_RADIUS_MILES * _MILES_TO_M
    params = [lon, lat, lake_radius_m, ocean_radius_m, ocean_radius_m]
    if include_waterway:
        params.append(waterway_radius_m)
    if include_place:
        params.append(city_radius_m)
    explain_sql = "EXPLAIN (ANALYZE, FORMAT TEXT) " + sql

    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            cur.execute(explain_sql, params)
            for row in cur.fetchall():
                print(row[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
