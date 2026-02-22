#!/usr/bin/env python3
"""
Load Natural Earth 10m geography marine polygons into is_in.ocean_polygons.
Downloads from NACIS CDN (or loads from --local-path if provided and file exists).
Replaces the table on each run (DROP + CREATE + INSERT).

Usage (from src/areas_server):
  python scripts/import_ocean_polygons.py --database "postgresql://..." [--local-path /path/to/cache]
"""
import argparse
import io
import sys
from pathlib import Path
from urllib.request import urlopen, Request

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from config import SCHEMA

MARINE_ZIP_URL = "https://naciscdn.org/naturalearth/10m/physical/ne_10m_geography_marine_polys.zip"
CACHE_FILENAME = "ne_10m_geography_marine_polys.zip"


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


def main() -> None:
    parser = argparse.ArgumentParser(description="Import Natural Earth marine polygons into is_in.ocean_polygons.")
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
        help="Directory to cache the downloaded zip; if file exists, load from it instead of downloading",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: --database must be non-empty", file=sys.stderr)
        sys.exit(1)

    zip_bytes = None
    if args.local_path is not None:
        cache_file = args.local_path / CACHE_FILENAME
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
        sf = open_shapefile_from_zip(zip_bytes)
    except Exception as e:
        print(f"Failed to open shapefile from zip: {e}", file=sys.stderr)
        sys.exit(1)

    rows = list(iter_shape_name_geom(sf))
    sf.close()
    if not rows:
        print("No valid (name, geometry) rows from shapefile.", file=sys.stderr)
        sys.exit(1)
    print(f"Loaded {len(rows)} marine polygons.", file=sys.stderr)

    table = "ocean_polygons"
    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."{table}"')
            cur.execute(
                f'''
                CREATE TABLE "{SCHEMA}"."{table}" (
                    name text NOT NULL,
                    geom geometry(Geometry, 4326) NOT NULL
                )
                '''
            )
            for name, geom in rows:
                wkt = geom.wkt
                cur.execute(
                    f'INSERT INTO "{SCHEMA}"."{table}" (name, geom) VALUES (%s, ST_GeomFromText(%s, 4326))',
                    (name, wkt),
                )
            cur.execute(
                f'CREATE INDEX IF NOT EXISTS "{table}_geom_geog_gist" ON "{SCHEMA}"."{table}" USING GIST ((geom::geography))'
            )
            cur.execute(f'ANALYZE "{SCHEMA}"."{table}"')
        conn.commit()
    print(f"Created {SCHEMA}.{table} with {len(rows)} rows.")


if __name__ == "__main__":
    main()
