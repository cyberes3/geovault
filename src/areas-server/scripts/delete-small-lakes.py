#!/usr/bin/env python3
"""
Delete water bodies smaller than a minimum area (default 0.25 sq mi) and those that are
swimming pools / sport=swimming (same exclusions as areas.lua). Run after import.

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

# Same exclusions as areas.lua: man-made pools only. Do not exclude sport=swimming or
# leisure=swimming_area — those can apply to natural lakes (designated swimming zones).
POOL_CONDITION = """(
    (tags->>'leisure' = 'swimming_pool')
    OR (tags->>'amenity' = 'swimming_pool')
)"""


def _has_tags_column(cur) -> bool:
    cur.execute(
        """
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = %s AND table_name = 'water_bodies' AND column_name = 'tags'
        """,
        (SCHEMA,),
    )
    return cur.fetchone() is not None


def main() -> None:
    parser = argparse.ArgumentParser(description="Delete small lakes and swimming pools from water_bodies.")
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
            has_tags = _has_tags_column(cur)
            area_expr = "public.ST_Area(public.geography(geom)) / 1e6"
            # Delete if below area threshold OR (when tags column exists) if swimming pool by tag
            if has_tags:
                where_clause = f"({area_expr}) < %s OR {POOL_CONDITION}"
            else:
                where_clause = f"({area_expr}) < %s"
            print("Reading database...")
            cur.execute(
                f"""
                SELECT COUNT(*) FROM {SCHEMA}.water_bodies
                WHERE {where_clause}
                """,
                (min_sqkm,),
            )
            (n,) = cur.fetchone()
            if n == 0:
                print("No water bodies to delete (none below area threshold or matching pool tags).")
                return
            if args.dry_run:
                print(f"Would delete {n} water bodies (area < {args.min_area_sqmi} sq mi or swimming pools).")
                return
            batch_size = 5000
            deleted = 0
            with tqdm(total=n, unit="rows", desc="Deleting small lakes and pools") as pbar:
                while True:
                    cur.execute(
                        f"""
                        WITH to_del AS (
                            SELECT ctid FROM {SCHEMA}.water_bodies
                            WHERE {where_clause}
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
    print(f"Deleted {deleted} water bodies (small and/or swimming pools).")


if __name__ == "__main__":
    main()
