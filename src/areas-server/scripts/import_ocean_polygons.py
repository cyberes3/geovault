#!/usr/bin/env python3
"""
Load ocean data into is_in.ocean_regions (sub-regions) and is_in.oceans (main oceans).
Imports both datasets in one run; drops existing ocean_regions and oceans tables on each run.

1. ocean_regions: Natural Earth 10m geography marine polys (seas, gulfs, straits, bays).
   Download from NACIS CDN or --local-path.
2. oceans: GOaS (Global Oceans and Seas v1) — 10 main ocean basins.
   Download from geovault-data or load GOaS_v1_20211214.zip from --local-path.

Usage (from src/areas-server):
  python scripts/import_ocean_polygons.py --database "postgresql://..." [--local-path /path/to/cache]
"""
import argparse
import io
import sys
from pathlib import Path
from urllib.request import urlopen, Request

import tqdm

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from config import SCHEMA

MARINE_ZIP_URL = "https://naciscdn.org/naturalearth/10m/physical/ne_10m_geography_marine_polys.zip"
MARINE_CACHE_FILENAME = "ne_10m_geography_marine_polys.zip"

GOAS_ZIP_URL = "https://git.evulid.cc/cyberes/geovault-data/raw/branch/master/GOaS_v1_20211214.zip"
GOAS_CACHE_FILENAME = "GOaS_v1_20211214.zip"

TABLE_OCEAN_REGIONS = "ocean_regions"
TABLE_OCEANS = "oceans"


def download_zip_to_memory(url: str) -> bytes | None:
    req = Request(url, headers={"User-Agent": "GeoVault-Import-Ocean/1.0"})
    try:
        with urlopen(req, timeout=120) as resp:
            return resp.read()
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return None


def open_shapefile_from_zip(zip_bytes: bytes):
    import zipfile
    import shapefile
    with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
        shp_names = [n for n in zf.namelist() if n.lower().endswith(".shp")]
        if not shp_names:
            raise ValueError("No .shp in zip")
        full = shp_names[0]
        base_name = Path(full).stem
        prefix = full[: full.rfind("/") + 1] if "/" in full else ""
        def member(suffix):
            return prefix + base_name + suffix
        shp_b = zf.read(member(".shp"))
        shx_b = zf.read(member(".shx"))
        dbf_b = zf.read(member(".dbf"))
    return shapefile.Reader(
        shp=io.BytesIO(shp_b),
        shx=io.BytesIO(shx_b),
        dbf=io.BytesIO(dbf_b),
    )


def iter_shape_name_geom(sf):
    """Yield (name, shapely_geom) for each record. name from 'name' field; geom from shape."""
    from shapely.geometry import Polygon, MultiPolygon
    fields = [f[0] for f in sf.fields[1:]]
    name_idx = fields.index("name") if "name" in fields else None
    for i in range(len(sf)):
        rec = sf.record(i)
        name = str(rec[name_idx]).strip() if name_idx is not None and rec[name_idx] else None
        if not name:
            continue
        s = sf.shape(i)
        if not getattr(s, "points", None):
            continue
        pts = s.points
        parts = getattr(s, "parts", None) or [0]
        polygons = []
        for j in range(len(parts)):
            start = parts[j]
            end = parts[j + 1] if j + 1 < len(parts) else len(pts)
            ring = pts[start:end]
            if len(ring) >= 3:
                try:
                    poly = Polygon(ring)
                    if not poly.is_empty and poly.is_valid:
                        polygons.append(poly)
                except Exception:
                    pass
        if not polygons:
            continue
        geom = polygons[0] if len(polygons) == 1 else MultiPolygon(polygons)
        if geom.is_empty or not geom.is_valid:
            continue
        yield (name, geom)


def iter_goas_name_geom(sf):
    """Yield (name, shapely_geom) for each GOaS record. Applies buffer(0) when geom is invalid (e.g. North Pacific)."""
    from shapely.geometry import Polygon, MultiPolygon
    fields = [f[0] for f in sf.fields[1:]]
    name_idx = fields.index("name") if "name" in fields else 0
    for i in range(len(sf)):
        rec = sf.record(i)
        name = str(rec[name_idx]).strip() if name_idx < len(rec) and rec[name_idx] else None
        if not name:
            continue
        s = sf.shape(i)
        if not getattr(s, "points", None):
            continue
        pts = s.points
        parts = getattr(s, "parts", None) or [0]
        polygons = []
        for j in range(len(parts)):
            start = parts[j]
            end = parts[j + 1] if j + 1 < len(parts) else len(pts)
            ring = pts[start:end]
            if len(ring) >= 3:
                try:
                    poly = Polygon(ring)
                    if not poly.is_empty and poly.is_valid:
                        polygons.append(poly)
                except Exception:
                    pass
        if not polygons:
            continue
        geom = polygons[0] if len(polygons) == 1 else MultiPolygon(polygons)
        if geom.is_empty:
            continue
        if not geom.is_valid and hasattr(geom, "buffer"):
            geom = geom.buffer(0)
        if geom.is_empty or not geom.is_valid:
            continue
        yield (name, geom)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Import ocean_regions (Natural Earth 10m) and oceans (GOaS) into is_in schema. Drops existing tables."
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
        help="Directory to cache downloaded zips; if files exist, load from here instead of downloading",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: --database must be non-empty", file=sys.stderr)
        sys.exit(1)

    print("Starting ocean polygons import (ocean_regions + oceans).", file=sys.stderr)

    # --- Natural Earth 10m (ocean_regions) ---
    print("Step 1/3: Natural Earth 10m (ocean_regions) ...", file=sys.stderr)
    zip_bytes = None
    if args.local_path is not None:
        cache_file = args.local_path / MARINE_CACHE_FILENAME
        if cache_file.is_file():
            print(f"Loading from cache: {cache_file}", file=sys.stderr)
            zip_bytes = cache_file.read_bytes()
        if zip_bytes is None:
            print(f"Downloading {MARINE_ZIP_URL} ...", file=sys.stderr)
            zip_bytes = download_zip_to_memory(MARINE_ZIP_URL)
            if zip_bytes is not None:
                args.local_path.mkdir(parents=True, exist_ok=True)
                cache_file.write_bytes(zip_bytes)
                print(f"Cached to {cache_file}", file=sys.stderr)
    else:
        print(f"Downloading {MARINE_ZIP_URL} ...", file=sys.stderr)
        zip_bytes = download_zip_to_memory(MARINE_ZIP_URL)

    if zip_bytes is None:
        sys.exit(1)

    try:
        print("Opening shapefile from zip ...", file=sys.stderr)
        sf = open_shapefile_from_zip(zip_bytes)
    except Exception as e:
        print(f"Failed to open shapefile from zip: {e}", file=sys.stderr)
        sys.exit(1)

    rows_regions = list(tqdm.tqdm(iter_shape_name_geom(sf), desc="Parsing ocean regions", unit="poly"))
    sf.close()
    if not rows_regions:
        print("No valid (name, geometry) rows from Natural Earth shapefile.", file=sys.stderr)
        sys.exit(1)
    print(f"Loaded {len(rows_regions)} ocean region polygons.", file=sys.stderr)

    # --- GOaS (oceans) ---
    print("Step 2/3: GOaS (oceans) ...", file=sys.stderr)
    goas_bytes = None
    if args.local_path is not None:
        goas_file = args.local_path / GOAS_CACHE_FILENAME
        if goas_file.is_file():
            print(f"Loading GOaS from cache: {goas_file}", file=sys.stderr)
            goas_bytes = goas_file.read_bytes()
    if goas_bytes is None:
        print(f"Downloading {GOAS_ZIP_URL} ...", file=sys.stderr)
        goas_bytes = download_zip_to_memory(GOAS_ZIP_URL)
        if goas_bytes is not None and args.local_path is not None:
            args.local_path.mkdir(parents=True, exist_ok=True)
            (args.local_path / GOAS_CACHE_FILENAME).write_bytes(goas_bytes)
            print(f"Cached GOaS to {args.local_path / GOAS_CACHE_FILENAME}", file=sys.stderr)

    rows_oceans = []
    if goas_bytes is not None:
        try:
            sf_goas = open_shapefile_from_zip(goas_bytes)
            rows_oceans = list(tqdm.tqdm(iter_goas_name_geom(sf_goas), desc="Parsing GOaS", unit="poly"))
            sf_goas.close()
            print(f"Loaded {len(rows_oceans)} ocean (GOaS) polygons.", file=sys.stderr)
        except Exception as e:
            print(f"Failed to load GOaS: {e}", file=sys.stderr)
    else:
        print("GOaS not available (download failed, no local file). Skipping oceans table.", file=sys.stderr)

    # --- Write to DB: drop old and current ocean tables, then create and fill ---
    print("Step 3/3: Writing to database ...", file=sys.stderr)
    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            print("Dropping existing ocean tables ...", file=sys.stderr)
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."ocean_polygons"')
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."{TABLE_OCEAN_REGIONS}"')
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."{TABLE_OCEANS}"')
            cur.execute(
                f'''
                CREATE TABLE "{SCHEMA}"."{TABLE_OCEAN_REGIONS}" (
                    name text NOT NULL,
                    geom geometry(Geometry, 4326) NOT NULL
                )
                '''
            )
            for name, geom in tqdm.tqdm(rows_regions, desc=f"Inserting {TABLE_OCEAN_REGIONS}", unit="row"):
                cur.execute(
                    f'INSERT INTO "{SCHEMA}"."{TABLE_OCEAN_REGIONS}" (name, geom) VALUES (%s, ST_GeomFromText(%s, 4326))',
                    (name, geom.wkt),
                )
            cur.execute(
                f'CREATE INDEX IF NOT EXISTS "{TABLE_OCEAN_REGIONS}_geom_geog_gist" '
                f'ON "{SCHEMA}"."{TABLE_OCEAN_REGIONS}" USING GIST ((geom::geography))'
            )
            cur.execute(f'ANALYZE "{SCHEMA}"."{TABLE_OCEAN_REGIONS}"')
            print(f"Indexed and analyzed {TABLE_OCEAN_REGIONS}.", file=sys.stderr)

            if rows_oceans:
                cur.execute(
                    f'''
                    CREATE TABLE "{SCHEMA}"."{TABLE_OCEANS}" (
                        name text NOT NULL,
                        geom geometry(Geometry, 4326) NOT NULL
                    )
                    '''
                )
                for name, geom in tqdm.tqdm(rows_oceans, desc=f"Inserting {TABLE_OCEANS}", unit="row"):
                    cur.execute(
                        f'INSERT INTO "{SCHEMA}"."{TABLE_OCEANS}" (name, geom) VALUES (%s, ST_GeomFromText(%s, 4326))',
                        (name, geom.wkt),
                    )
                cur.execute(
                    f'CREATE INDEX IF NOT EXISTS "{TABLE_OCEANS}_geom_geog_gist" '
                    f'ON "{SCHEMA}"."{TABLE_OCEANS}" USING GIST ((geom::geography))'
                )
                cur.execute(f'ANALYZE "{SCHEMA}"."{TABLE_OCEANS}"')
                print(f"Indexed and analyzed {TABLE_OCEANS}.", file=sys.stderr)
        conn.commit()
        with conn.cursor() as cur:
            cur.execute(f'SELECT COUNT(*) FROM "{SCHEMA}"."{TABLE_OCEAN_REGIONS}"')
            (n_regions,) = cur.fetchone()
            n_oceans = len(rows_oceans)
    print(f"Created {SCHEMA}.{TABLE_OCEAN_REGIONS} with {n_regions} rows.")
    print(f"Created {SCHEMA}.{TABLE_OCEANS} with {n_oceans} rows.")
    print("Import complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
