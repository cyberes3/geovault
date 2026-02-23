#!/usr/bin/env python3
"""
Load ski resort boundaries into is_in.ski_resorts.
Source: https://www.opensnowmap.org/download/planet_pistes.osm.gz
Builds a boundary per resort from site=piste relation member ways (runs + lifts), applies 500 ft buffer.
Orphan runs are assigned by best guess from data/ski_resorts.json; unmatched orphans are reported.
Drops and recreates the table on each run.

Usage (from src/areas-server):
  python scripts/import_ski_resorts.py --database "postgresql://..." [--local-path /path/to/cache]
"""
import argparse
import gzip
import io
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.request import urlopen, Request

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
import tqdm
from config import SCHEMA

PLANET_PISTES_URL = "https://www.opensnowmap.org/download/planet_pistes.osm.gz"
PLANET_PISTES_FILENAME = "planet_pistes.osm.gz"
TABLE_NAME = "ski_resorts"
BUFFER_SMALL_M = 75.0
BUFFER_500FT_M = 152.4  # 500 ft in meters


def download_gz(url: str, timeout: int = 300) -> bytes | None:
    req = Request(url, headers={"User-Agent": "GeoVault-Import-SkiResorts/1.0"})
    try:
        with urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return None


def load_ski_resorts_json(areas_server_root: Path) -> list:
    path = areas_server_root / "data" / "ski_resorts.json"
    if not path.is_file():
        return []
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("ski_resorts", [])


def _utm_zone(lon: float) -> int:
    return int((lon + 180) / 6) + 1


def buffer_meters(wgs84_geom, meters: float):
    """Buffer geometry by meters in a local UTM, then back to WGS84."""
    from shapely.ops import transform
    from pyproj import CRS, Transformer

    if wgs84_geom.is_empty:
        return wgs84_geom
    centroid = wgs84_geom.centroid
    lon, lat = centroid.x, centroid.y
    zone = _utm_zone(lon)
    utm_crs = CRS.from_proj4(f"+proj=utm +zone={zone} +datum=WGS84 +units=m")
    wgs84 = CRS.from_epsg(4326)
    to_utm = Transformer.from_crs(wgs84, utm_crs, always_xy=True)
    to_wgs84 = Transformer.from_crs(utm_crs, wgs84, always_xy=True)
    utm_geom = transform(to_utm.transform, wgs84_geom)
    buffered = utm_geom.buffer(meters)
    return transform(to_wgs84.transform, buffered)


def parse_osm(path: Path):
    """Parse OSM XML; yield (nodes_dict, ways_dict, relations_list). Single pass, full load for relations."""
    import xml.etree.ElementTree as ET

    nodes: dict = {}
    ways: dict = {}
    relations: list = []

    def get_tags(elem):
        out = {}
        for tag in elem.iter("tag"):
            k = tag.get("k")
            v = tag.get("v")
            if k and v is not None:
                out[k] = v
        return out

    def get_nd_refs(elem):
        return [nd.get("ref") for nd in elem.iter("nd") if nd.get("ref") is not None]

    def get_members(elem):
        out = []
        for m in elem.iter("member"):
            out.append((m.get("type"), m.get("ref"), m.get("role")))
        return out

    file_size = path.stat().st_size

    with open(path, "rb") as raw_f:
        with tqdm.tqdm(total=file_size, unit="B", unit_scale=True, unit_divisor=1024, desc="Parsing OSM") as pbar:

            class ProgressReader:
                """Wraps a binary file so each read() updates the progress bar (compressed bytes read)."""

                def __init__(self, f, pbar):
                    self._f = f
                    self._pbar = pbar

                def read(self, size=-1):
                    data = self._f.read(size)
                    if data:
                        self._pbar.update(len(data))
                    return data

                def __getattr__(self, name):
                    return getattr(self._f, name)

            wrapped = ProgressReader(raw_f, pbar)
            with gzip.GzipFile(fileobj=wrapped, mode="rb") as gz:
                text_f = io.TextIOWrapper(gz, encoding="utf-8", errors="replace")
                for event, elem in ET.iterparse(text_f, events=("end",)):
                    if elem.tag == "node":
                        nid = elem.get("id")
                        if nid and elem.get("lat") and elem.get("lon"):
                            try:
                                nodes[nid] = (float(elem.get("lat")), float(elem.get("lon")))
                            except (TypeError, ValueError):
                                pass
                        elem.clear()
                    elif elem.tag == "way":
                        wid = elem.get("id")
                        if wid:
                            nd_refs = get_nd_refs(elem)
                            tags = get_tags(elem)
                            ways[wid] = (nd_refs, tags)
                        elem.clear()
                    elif elem.tag == "relation":
                        rid = elem.get("id")
                        tags = get_tags(elem)
                        members = get_members(elem)
                        if tags.get("type") == "site" and tags.get("site") == "piste":
                            relations.append((rid, tags, members))
                        elem.clear()

    return nodes, ways, relations


def way_to_linestring(nd_refs: list, nodes: dict):
    """Build a LineString from node refs; return None if invalid."""
    from shapely.geometry import LineString

    coords = []
    for ref in nd_refs:
        if ref not in nodes:
            return None
        lat, lon = nodes[ref]
        coords.append((lon, lat))
    if len(coords) < 2:
        return None
    return LineString(coords)


def is_piste_or_lift(tags: dict) -> bool:
    if not tags:
        return False
    if tags.get("piste:type"):
        return True
    if tags.get("aerialway"):
        return True
    return False


def build_resort_geometry(way_ids: list, ways: dict, nodes: dict):
    """Union of run/lift LineStrings, small buffer to polygon, then 500 ft buffer. Returns (Multi)Polygon or None."""
    from shapely.ops import unary_union
    from shapely.geometry import MultiLineString

    lines = []
    for wid in way_ids:
        if wid not in ways:
            continue
        nd_refs, tags = ways[wid]
        if not is_piste_or_lift(tags):
            continue
        ls = way_to_linestring(nd_refs, nodes)
        if ls is not None and not ls.is_empty:
            lines.append(ls)
    if not lines:
        return None
    union = unary_union(lines)
    if union.is_empty:
        return None
    if union.geom_type == "LineString":
        union = MultiLineString([union])
    buffered = buffer_meters(union, BUFFER_SMALL_M)
    if buffered.is_empty or buffered.geom_type not in ("Polygon", "MultiPolygon"):
        return None
    return buffer_meters(buffered, BUFFER_500FT_M)


def resort_name_from_relation(rid: str, tags: dict) -> str:
    name = (tags or {}).get("name") or (tags or {}).get("operator")
    if name and str(name).strip():
        return str(name).strip()
    return f"Ski area {rid}"


def haversine_miles(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    import math
    R = 3958.8
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def find_resort_for_point(lat: float, lon: float, resorts_json: list) -> tuple[str | None, float | None]:
    """Return (resort_name, distance_miles). If inside bbox, distance 0. Else nearest by bbox center."""
    best_name = None
    best_dist: float | None = None
    for r in resorts_json:
        bbox = r.get("bbox") or {}
        if not bbox:
            continue
        min_lat = bbox.get("min_lat")
        max_lat = bbox.get("max_lat")
        min_lon = bbox.get("min_lon")
        max_lon = bbox.get("max_lon")
        if None in (min_lat, max_lat, min_lon, max_lon):
            continue
        if min_lat <= lat <= max_lat and min_lon <= lon <= max_lon:
            return (r.get("name") or "Unknown", 0.0)
        cx = (min_lon + max_lon) / 2
        cy = (min_lat + max_lat) / 2
        d = haversine_miles(lat, lon, cy, cx)
        if best_dist is None or d < best_dist:
            best_dist = d
            best_name = r.get("name") or "Unknown"
    return (best_name, best_dist)


def _process_orphan_chunk(args: tuple) -> tuple:
    """Process a chunk of orphan ways (for ProcessPoolExecutor). Must be top-level for pickling.
    Returns (list of (resort_name, geoms_list, report_or_none), processed_count) so progress bar can advance by chunk size."""
    chunk, nodes, ways, resorts_json = args
    results = []
    for item in chunk:
        wid, (nd_refs, tags) = item
        ls = way_to_linestring(nd_refs, nodes)
        if ls is None or ls.is_empty:
            continue
        centroid = ls.centroid
        lat, lon = centroid.y, centroid.x
        resort_name, dist_miles = find_resort_for_point(lat, lon, resorts_json)
        if resort_name is not None:
            orphan_geom = build_resort_geometry([wid], ways, nodes)
            if orphan_geom and not orphan_geom.is_empty:
                if orphan_geom.geom_type == "Polygon":
                    geoms = [orphan_geom]
                else:
                    geoms = list(orphan_geom.geoms) if hasattr(orphan_geom, "geoms") else [orphan_geom]
                report = (lat, lon, wid, resort_name, dist_miles) if dist_miles != 0 else None
                results.append((resort_name, geoms, report))
            else:
                results.append((resort_name, [], (lat, lon, wid, resort_name, dist_miles) if dist_miles != 0 else None))
        else:
            results.append(("N/A", [], (lat, lon, wid, "N/A", float("nan"))))
    return (results, len(chunk))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Import ski_resorts into is_in schema from planet_pistes.osm.gz. Drops existing table."
    )
    parser.add_argument("--database", type=str, required=True, help="PostgreSQL connection string")
    parser.add_argument(
        "--local-path",
        type=Path,
        default=None,
        help="Directory to cache downloaded file; if present, load from here",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: --database must be non-empty", file=sys.stderr)
        sys.exit(1)

    areas_server_root = Path(__file__).resolve().parent.parent
    resorts_json = load_ski_resorts_json(areas_server_root)
    if not resorts_json:
        raise Exception("data/ski_resorts.json not found or empty; orphan assignment will be skipped.")

    print("Step 1/4: Downloading planet_pistes.osm.gz ...", file=sys.stderr)
    osm_path = None
    if args.local_path is not None:
        cache_file = args.local_path / PLANET_PISTES_FILENAME
        if cache_file.is_file():
            print(f"Using cache: {cache_file}", file=sys.stderr)
            osm_path = cache_file
        else:
            data = download_gz(PLANET_PISTES_URL)
            if data:
                args.local_path.mkdir(parents=True, exist_ok=True)
                cache_file.write_bytes(data)
                osm_path = cache_file
                print(f"Cached to {cache_file}", file=sys.stderr)
    else:
        data = download_gz(PLANET_PISTES_URL)
        if data:
            osm_path = Path("/tmp") / PLANET_PISTES_FILENAME
            osm_path.write_bytes(data)
            print(f"Wrote temp {osm_path}", file=sys.stderr)
    if not osm_path or not osm_path.is_file():
        sys.exit(1)

    # Delete when finished if we're using a temp file in /tmp
    delete_osm_when_done = osm_path.resolve().parent == Path("/tmp").resolve()

    print("Step 2/4: Parsing OSM ...", file=sys.stderr)
    nodes, ways, relations = parse_osm(osm_path)
    way_ids_in_relations = set()
    for _rid, _tags, members in relations:
        for mtype, ref, _role in members:
            if mtype == "way" and ref:
                way_ids_in_relations.add(ref)

    # Build (name, geom) from relations (parallelized)
    name_to_geoms: dict[str, list] = {}

    def process_one_relation(rel):
        rid, tags, members = rel
        name = resort_name_from_relation(rid, tags)
        way_refs = [ref for mtype, ref, _ in members if mtype == "way" and ref]
        geom = build_resort_geometry(way_refs, ways, nodes)
        if geom is None or geom.is_empty:
            return (name, [])
        if geom.geom_type == "Polygon":
            geoms = [geom]
        else:
            geoms = list(geom.geoms) if hasattr(geom, "geoms") else [geom]
        return (name, geoms)

    max_workers = min(32, (os.cpu_count() or 4) + 4)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(process_one_relation, rel): rel for rel in relations}
        for future in tqdm.tqdm(as_completed(futures), total=len(futures), desc="Building resort boundaries", unit="resort"):
            name, geoms = future.result()
            if geoms:
                name_to_geoms.setdefault(name, []).extend(geoms)

    # Orphans: ways with piste:type or aerialway not in any relation (process-parallel for real CPU use)
    orphan_ways = [(wid, (nd_refs, tags)) for wid, (nd_refs, tags) in ways.items() if wid not in way_ids_in_relations and is_piste_or_lift(tags)]

    orphan_reports = []
    if orphan_ways:
        base_workers = min(32, (os.cpu_count() or 4) + 4)
        orphan_workers = min(56, int(base_workers * 1.75))
        # Chunk so we pass (chunk, nodes, ways, resorts_json) once per process; fewer pickles, true parallelism
        n_chunks = max(1, orphan_workers)
        chunk_size = (len(orphan_ways) + n_chunks - 1) // n_chunks
        chunks = [orphan_ways[i : i + chunk_size] for i in range(0, len(orphan_ways), chunk_size)]
        chunk_args = [(c, nodes, ways, resorts_json) for c in chunks]

        with ProcessPoolExecutor(max_workers=orphan_workers) as executor:
            futures = [executor.submit(_process_orphan_chunk, a) for a in chunk_args]
            with tqdm.tqdm(total=len(orphan_ways), desc="Processing orphan ways", unit="way") as pbar:
                for future in as_completed(futures):
                    chunk_results, processed_count = future.result()
                    pbar.update(processed_count)
                    for resort_name, geoms, report in chunk_results:
                        if resort_name and geoms:
                            name_to_geoms.setdefault(resort_name, []).extend(geoms)
                        if report is not None:
                            orphan_reports.append(report)

    # Orphan report: list unmatched (no bbox match) with closest resort
    if orphan_reports:
        print("Orphans with no bbox match (closest resort in ski_resorts.json):", file=sys.stderr)
        for lat, lon, wid, closest_name, dist in orphan_reports:
            dist_str = f"{dist:.2f}" if dist == dist else "N/A"
            print(f"  lon={lon:.6f} lat={lat:.6f} way_id={wid} closest={closest_name!r} distance_miles={dist_str}", file=sys.stderr)

    # Merge per-name geoms (each geom is already buffered in build_resort_geometry)
    from shapely.ops import unary_union

    rows = []
    for name, geoms in tqdm.tqdm(name_to_geoms.items(), desc="Merging per-resort geometries", unit="resort"):
        if not geoms:
            continue
        combined = unary_union(geoms)
        if combined.is_empty:
            continue
        if combined.geom_type not in ("Polygon", "MultiPolygon"):
            combined = buffer_meters(combined, BUFFER_SMALL_M)
        if combined.is_empty:
            continue
        if not combined.is_valid and hasattr(combined, "buffer"):
            combined = combined.buffer(0)
        if combined.is_empty or not combined.is_valid:
            continue
        rows.append((name, combined))

    print(f"Step 3/4: Built {len(rows)} resort boundaries.", file=sys.stderr)

    print("Step 4/4: Writing to database ...", file=sys.stderr)
    with psycopg.connect(conninfo) as conn:
        with conn.cursor() as cur:
            cur.execute(f'DROP TABLE IF EXISTS "{SCHEMA}"."{TABLE_NAME}"')
            cur.execute(
                f'''
                CREATE TABLE "{SCHEMA}"."{TABLE_NAME}" (
                    name text NOT NULL,
                    geom geometry(Geometry, 4326) NOT NULL
                )
                '''
            )
            it = rows
            for name, geom in tqdm.tqdm(it, desc=f"Inserting {TABLE_NAME}", unit="row"):
                cur.execute(
                    f'INSERT INTO "{SCHEMA}"."{TABLE_NAME}" (name, geom) VALUES (%s, ST_GeomFromText(%s, 4326))',
                    (name, geom.wkt),
                )
            cur.execute(
                f'CREATE INDEX IF NOT EXISTS "{TABLE_NAME}_geom_geog_gist" '
                f'ON "{SCHEMA}"."{TABLE_NAME}" USING GIST ((geom::geography))'
            )
            cur.execute(f'ANALYZE "{SCHEMA}"."{TABLE_NAME}"')
        conn.commit()
        with conn.cursor() as cur:
            cur.execute(f'SELECT COUNT(*) FROM "{SCHEMA}"."{TABLE_NAME}"')
            (n,) = cur.fetchone()
    print(f"Created {SCHEMA}.{TABLE_NAME} with {n} rows.", file=sys.stderr)
    if delete_osm_when_done and osm_path.is_file():
        try:
            osm_path.unlink()
            print(f"Removed temp file {osm_path}", file=sys.stderr)
        except OSError:
            pass
    print("Import complete.", file=sys.stderr)


if __name__ == "__main__":
    main()
