#!/usr/bin/env python3
"""
Find and delete rows with invalid or empty geometry in admin_areas, protected_areas, water_bodies.
Nominatim-style: report and delete; no ST_MakeValid.

Usage (from src/areas-server):
  python scripts/clean_invalid_geometry.py --database "postgresql://..." [--dry-run]
"""
import argparse
import sys
from pathlib import Path
from typing import Any, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from psycopg import Connection

from config import SCHEMA

TABLES = ("admin_areas", "protected_areas", "water_bodies")


def find_invalid_rows(conn: Connection) -> List[Tuple[str, Any, Any, str]]:
    """Return list of (table, osm_id, ctid, reason) for rows where NOT ST_IsValid(geom) OR ST_IsEmpty(geom)."""
    out: List[Tuple[str, Any, Any, str]] = []
    with conn.cursor() as cur:
        for table in TABLES:
            cur.execute(
                f"""
                SELECT osm_id, ctid, public.ST_IsValidReason(geom)
                FROM "{SCHEMA}"."{table}"
                WHERE public.ST_IsEmpty(geom) OR NOT public.ST_IsValid(geom)
                """
            )
            for osm_id, ctid, reason in cur.fetchall():
                out.append((table, osm_id, ctid, reason or "unknown"))
    return out


def run_clean(conn: Connection, dry_run: bool) -> List[Tuple[str, Any, Any, str]]:
    """Report invalid/empty rows; if not dry_run, delete them. Returns list of (table, osm_id, ctid, reason)."""
    rows = find_invalid_rows(conn)
    if not rows:
        return rows
    if dry_run:
        return rows
    with conn.cursor() as cur:
        for table in TABLES:
            ctids = [r[2] for r in rows if r[0] == table]
            if not ctids:
                continue
            cur.execute(
                f'DELETE FROM "{SCHEMA}"."{table}" WHERE ctid = ANY(%s)',
                (ctids,),
            )
    conn.commit()
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Report and delete rows with invalid or empty geometry."
    )
    parser.add_argument(
        "--database",
        type=str,
        required=True,
        help="PostgreSQL connection string",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only report invalid rows; do not delete",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: --database must be non-empty", file=sys.stderr)
        return 1

    with psycopg.connect(conninfo) as conn:
        rows = run_clean(conn, dry_run=args.dry_run)

    if not rows:
        print("No invalid or empty geometry rows found.")
        return 0

    print(f"Found {len(rows)} row(s) with invalid or empty geometry:")
    for table, osm_id, ctid, reason in rows:
        print(f"  {SCHEMA}.{table}  osm_id={osm_id}  ctid={ctid}  reason={reason!r}")

    if args.dry_run:
        print("Dry-run: no rows deleted.")
    else:
        print(f"Deleted {len(rows)} row(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
