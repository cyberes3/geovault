#!/usr/bin/env python3
"""
Delete water bodies smaller than a minimum area (default 0.25 sq mi).
Run after import (no need to pre-compute area; uses ST_Area(geography(geom)) in the DELETE).
Usage: from src/areas_server: python scripts/delete_small_lakes.py [--min-area-sqmi 0.25]
Env: AREAS_SERVER_DATABASE.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from config import SCHEMA, get_conninfo

# 1 sq mi = 2.589988110336 km²
SQMI_TO_SQKM = 2.589988110336


def main() -> None:
    parser = argparse.ArgumentParser(description="Delete small lakes from water_bodies.")
    parser.add_argument(
        "--min-area-sqmi",
        type=float,
        default=0.25,
        help="Minimum area in square miles; smaller water bodies are deleted (default 0.25)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only report how many rows would be deleted",
    )
    args = parser.parse_args()
    min_sqkm = args.min_area_sqmi * SQMI_TO_SQKM

    try:
        conninfo = get_conninfo()
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            # Area from geometry (m²) / 1e6 = km²; no pre-computed column needed
            area_expr = "public.ST_Area(public.geography(geom)) / 1e6"
            cur.execute(
                f"""
                SELECT COUNT(*) FROM {SCHEMA}.water_bodies
                WHERE ({area_expr}) < %s
                """,
                (min_sqkm,),
            )
            (n,) = cur.fetchone()
            if n == 0:
                print("No water bodies below minimum area; nothing to delete.")
                return
            if args.dry_run:
                print(f"Would delete {n} water bodies with area < {args.min_area_sqmi} sq mi (< {min_sqkm:.4f} km²).")
                return
            cur.execute(
                f"""
                DELETE FROM {SCHEMA}.water_bodies
                WHERE ({area_expr}) < %s
                """,
                (min_sqkm,),
            )
            deleted = cur.rowcount
        conn.commit()
    print(f"Deleted {deleted} water bodies with area < {args.min_area_sqmi} sq mi.")


if __name__ == "__main__":
    main()
