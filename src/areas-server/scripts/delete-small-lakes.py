#!/usr/bin/env python3
"""
Delete water bodies smaller than a minimum area (default 0.25 sq mi).
Run after import (no need to pre-compute area; uses ST_Area(geography(geom)) in the DELETE).

Usage (from src/areas-server):
  python scripts/delete-small-lakes.py DATABASE_URL [--min-area-sqmi 0.25] [--dry-run]
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from tqdm import tqdm

from config import SCHEMA

# 1 sq mi = 2.589988110336 km²
SQMI_TO_SQKM = 2.589988110336


def main() -> None:
    parser = argparse.ArgumentParser(description="Delete small lakes from water_bodies.")
    parser.add_argument(
        "database",
        type=str,
        help="PostgreSQL connection string (e.g. postgresql://user:pass@host/dbname)",
    )
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
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: database URL must be non-empty", file=sys.stderr)
        sys.exit(1)

    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            print('Reading database...')
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
            batch_size = 5000
            deleted = 0
            with tqdm(total=n, unit="rows", desc="Deleting small lakes") as pbar:
                while True:
                    cur.execute(
                        f"""
                        WITH to_del AS (
                            SELECT ctid FROM {SCHEMA}.water_bodies
                            WHERE ({area_expr}) < %s
                            LIMIT %s
                        )
                        DELETE FROM {SCHEMA}.water_bodies
                        WHERE ctid IN (SELECT ctid FROM to_del)
                        """,
                        (min_sqkm, batch_size),
                    )
                    batch_deleted = cur.rowcount
                    if batch_deleted == 0:
                        break
                    deleted += batch_deleted
                    pbar.update(batch_deleted)
            conn.commit()
    print(f"Deleted {deleted} water bodies with area < {args.min_area_sqmi} sq mi.")


if __name__ == "__main__":
    main()
