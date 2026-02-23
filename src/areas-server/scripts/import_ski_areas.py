#!/usr/bin/env python3
"""
Load ski area boundaries into is_in.ski_resorts from OpenSkiMap GeoJSON.
Drops existing ski_resorts table on each run.

Source: https://tiles.openskimap.org/geojson/ski_areas.geojson
With --local-path (download directory): use cache if fresh (< 1 day); if stale or missing,
download and save; on download failure use cached file if present.

Usage (from src/areas-server):
  python scripts/import_ski_areas.py --database "postgresql://..." [--local-path /path/to/download/dir]
"""
import argparse
import json
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen

import tqdm

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from config import SCHEMA

SKI_AREAS_URL = "https://tiles.openskimap.org/geojson/ski_areas.geojson"
CACHE_FILENAME = "ski_areas.geojson"
TABLE_NAME = "ski_resorts"
POINT_BUFFER_METERS = 500
CACHE_MAX_AGE_SECONDS = 86400  # 1 day
USER_AGENT = "GeoVault-Import-SkiAreas/1.0"
DOWNLOAD_TIMEOUT = 300


def download_geojson_to_memory(url: str) -> bytes | None:
    req = Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp:
            return resp.read()
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return None


def _name_from_feature(prop: dict) -> str | None:
    name = prop.get("name")
    if name and isinstance(name, str) and name.strip():
        return name.strip()
    places = prop.get("places") or []
    if places and isinstance(places, list):
        first = places[0]
        if isinstance(first, dict):
            localized = (first.get("localized") or {}).get("en")
            if isinstance(localized, dict):
                for key in ("locality", "region", "country"):
                    val = localized.get(key)
                    if val and isinstance(val, str) and val.strip():
                        return val.strip()
    oid = prop.get("id")
    if oid is not None:
        return str(oid)
    return None


def iter_ski_area_rows(fc: dict):
    """Yield (name, geometry_dict, is_point) for each feature with valid geometry."""
    for feat in fc.get("features") or []:
        if feat.get("type") != "Feature":
            continue
        prop = feat.get("properties") or {}
        name = _name_from_feature(prop)
        geom = feat.get("geometry")
        if not geom or not name:
            continue
        gtype = (geom.get("type") or "").strip()
        if gtype == "Point":
            yield (name, geom, True)
        elif gtype in ("Polygon", "MultiPolygon"):
            yield (name, geom, False)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Import ski areas (OpenSkiMap) into is_in.ski_resorts. Drops existing table."
    )
    parser.add_argument(
        "--database",
        type=str,
        required=True,
        help="PostgreSQL connection string (e.g. postgresql://user:pass@host/dbname)",
    )
    parser.add_argument(
        "--local-path",
        type=Path,
        default=None,
        help="Download directory; cache file ski_areas.geojson used if fresh (< 1 day), else download and save; on failure use cache if present",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: --database must be non-empty", file=sys.stderr)
        sys.exit(1)

    print("Starting ski areas import.", file=sys.stderr)

    # --- Step 1/2: Obtain GeoJSON ---
    print("Step 1/2: Ski areas GeoJSON ...", file=sys.stderr)
    geojson_bytes = None
    cache_file = args.local_path / CACHE_FILENAME if args.local_path else None
    if cache_file and cache_file.is_file():
        mtime = cache_file.stat().st_mtime
        if (time.time() - mtime) < CACHE_MAX_AGE_SECONDS:
            print(f"Loading from cache: {cache_file}", file=sys.stderr)
            geojson_bytes = cache_file.read_bytes()
    if geojson_bytes is None:
        print(f"Downloading {SKI_AREAS_URL} ...", file=sys.stderr)
        geojson_bytes = download_geojson_to_memory(SKI_AREAS_URL)
        if geojson_bytes is not None and args.local_path is not None:
            args.local_path.mkdir(parents=True, exist_ok=True)
            cache_file.write_bytes(geojson_bytes)
            print(f"Cached to {cache_file}", file=sys.stderr)
        elif geojson_bytes is None and cache_file and cache_file.is_file():
            print("Using cached file after download failure.", file=sys.stderr)
            geojson_bytes = cache_file.read_bytes()
        elif geojson_bytes is None:
            print("No GeoJSON available and no cache. Exiting.", file=sys.stderr)
            sys.exit(1)

    try:
        fc = json.loads(geojson_bytes.decode("utf-8"))
    except Exception as e:
        print(f"Failed to parse GeoJSON: {e}", file=sys.stderr)
        sys.exit(1)
    if fc.get("type") != "FeatureCollection" or "features" not in fc:
        print("GeoJSON is not a FeatureCollection with features.", file=sys.stderr)
        sys.exit(1)

    rows = list(tqdm.tqdm(iter_ski_area_rows(fc), desc="Parsing ski areas", unit="feature"))
    if not rows:
        print("No valid (name, geometry) rows from GeoJSON.", file=sys.stderr)
        sys.exit(1)
    print(f"Loaded {len(rows)} ski areas.", file=sys.stderr)

    # --- Step 2/2: Write to DB ---
    print("Step 2/2: Writing to database ...", file=sys.stderr)
    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            print("Dropping existing ski_resorts table ...", file=sys.stderr)
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."{TABLE_NAME}"')
            cur.execute(
                f'''
                CREATE TABLE "{SCHEMA}"."{TABLE_NAME}" (
                    name text NOT NULL,
                    geom geometry(Geometry, 4326) NOT NULL
                )
                '''
            )
            for name, geom, is_point in tqdm.tqdm(rows, desc=f"Inserting {TABLE_NAME}", unit="row"):
                geom_json = json.dumps(geom)
                if is_point:
                    cur.execute(
                        f'INSERT INTO "{SCHEMA}"."{TABLE_NAME}" (name, geom) VALUES ('
                        f'%s, ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326)::geography, %s)::geometry)',
                        (name, geom_json, POINT_BUFFER_METERS),
                    )
                else:
                    cur.execute(
                        f'INSERT INTO "{SCHEMA}"."{TABLE_NAME}" (name, geom) VALUES (%s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326))',
                        (name, geom_json),
                    )
            cur.execute(
                f'CREATE INDEX IF NOT EXISTS "{TABLE_NAME}_geom_geog_gist" '
                f'ON "{SCHEMA}"."{TABLE_NAME}" USING GIST ((geom::geography))'
            )
            cur.execute(f'ANALYZE "{SCHEMA}"."{TABLE_NAME}"')
            print(f"Indexed and analyzed {TABLE_NAME}.", file=sys.stderr)
        conn.commit()
        with conn.cursor() as cur:
            cur.execute(f'SELECT COUNT(*) FROM "{SCHEMA}"."{TABLE_NAME}"')
            (n,) = cur.fetchone()
    print(f"Created {SCHEMA}.{TABLE_NAME} with {n} rows.")
    print("Import complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
