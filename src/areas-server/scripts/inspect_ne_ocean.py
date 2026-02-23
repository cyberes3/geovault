#!/usr/bin/env python3
"""
Download and inspect Natural Earth ocean/marine datasets for suitability for
ocean reverse geocoding (on ocean + within 1 mile of ocean, with optional name).

Zips are downloaded into memory and shapefiles are loaded directly from the
in-memory zip (no temp files for the archive).

Datasets:
  ocean  – ne_50m_ocean: single ocean polygon, no names (point-in-ocean only).
  marine – ne_10m_geography_marine_polys: named seas/oceans (Pacific, Atlantic, etc.).

Usage (from repo root or src/areas-server):
  python src/areas-server/scripts/inspect_ne_ocean.py [--dataset ocean|marine] [--local PATH]
  python scripts/inspect_ne_ocean.py --dataset marine
  python scripts/inspect_ne_ocean.py --compare          # download and inspect both, print comparison

Optional deps: pyshp (pip install pyshp) for reading .shp; shapely for point-in-polygon test.
"""
import argparse
import io
import json
import sys
import tempfile
import zipfile
from pathlib import Path
from urllib.request import urlopen, Request

# Try optional deps
try:
    import shapefile  # pyshp
except ImportError:
    shapefile = None
try:
    from shapely.geometry import Point, shape as shapely_shape
    from shapely.ops import unary_union
except ImportError:
    shapely_shape = None
    unary_union = None


# Natural Earth dataset download URLs (NACIS CDN only)
DOWNLOAD_URLS = {
    "ocean": ["https://naciscdn.org/naturalearth/50m/physical/ne_50m_ocean.zip"],
    "marine": ["https://naciscdn.org/naturalearth/10m/physical/ne_10m_geography_marine_polys.zip"],
}

# Test points: coastline and 100 miles in ocean (Oregon coast / Pacific)
COASTLINE_POINT = (-124.1248293541604, 43.91110998943451)  # (lon, lat)
OCEAN_100MI_POINT = (-126.1398807838393, 43.946227774668166)  # (lon, lat)


def download_zip_to_memory(url: str) -> bytes | None:
    """Download zip to memory; return bytes or None on failure."""
    req = Request(url, headers={"User-Agent": "GeoVault-Inspect-Script/1.0"})
    try:
        with urlopen(req, timeout=60) as resp:
            return resp.read()
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return None


def open_shapefile_from_zip(zip_bytes: bytes):
    """Open shapefile from zip bytes in memory. Returns (shapefile.Reader, base_name)."""
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
    return (
        shapefile.Reader(
            shp=io.BytesIO(shp_b),
            shx=io.BytesIO(shx_b),
            dbf=io.BytesIO(dbf_b),
        ),
        base_name,
    )


def find_shapefile(dir_path: Path):
    for p in dir_path.iterdir():
        if p.suffix.lower() == ".shp":
            return p
    return None


def inspect_with_pyshp(sf) -> dict:
    out = {
        "format": "shapefile",
        "shapeType": sf.shapeTypeName,
        "numRecords": len(sf),
        "fields": [f[0] for f in sf.fields[1:]],  # skip DeletionFlag
        "field_specs": [
            {"name": f[0], "type": f[1], "size": f[2], "decimal": f[3]}
            for f in sf.fields[1:]
        ],
        "has_name_like_field": False,
        "bbox": None,
        "sample_records": [],
        "point_tests": {},
    }
    for name in out["fields"]:
        if "name" in name.lower():
            out["has_name_like_field"] = True
            break
    # Bounding box from first shape
    if sf.shape(0) is not None:
        out["bbox"] = sf.shape(0).bbox
    # Sample first 3 records
    for i in range(min(3, len(sf))):
        rec = sf.record(i)
        out["sample_records"].append(dict(zip(out["fields"], rec)))
    return out


def build_geometries_with_shapely(source):
    """Build Shapely geometry from shapefile for point-in-polygon. source: Path or shapefile.Reader. Returns union of all polygons."""
    if shapely_shape is None or unary_union is None:
        return None
    from shapely.geometry import Polygon
    if isinstance(source, Path):
        sf = shapefile.Reader(str(source))
        try:
            return _build_geometries_from_reader(sf)
        finally:
            sf.close()
    else:
        return _build_geometries_from_reader(source)


def _build_geometries_from_reader(sf):
    from shapely.geometry import Polygon
    shapes = []
    for s in sf.shapes():
        if not getattr(s, "points", None):
            continue
        pts = s.points
        parts = getattr(s, "parts", None) or [0]
        try:
            for i in range(len(parts)):
                start = parts[i]
                end = parts[i + 1] if i + 1 < len(parts) else len(pts)
                ring = pts[start:end]
                if len(ring) >= 3:
                    poly = Polygon(ring)
                    if not poly.is_empty and poly.is_valid:
                        shapes.append(poly)
        except Exception:
            pass
    if not shapes:
        return None
    return unary_union(shapes)


def run_point_tests(geom, out: dict) -> None:
    if geom is None or shapely_shape is None:
        out["point_tests"] = {"note": "Shapely not available or no geometry built"}
        return
    coast_pt = Point(COASTLINE_POINT[0], COASTLINE_POINT[1])
    ocean_pt = Point(OCEAN_100MI_POINT[0], OCEAN_100MI_POINT[1])
    out["point_tests"] = {
        "coastline_point_inside": geom.contains(coast_pt),
        "ocean_100mi_point_inside": geom.contains(ocean_pt),
        "coastline_point": list(COASTLINE_POINT),
        "ocean_100mi_point": list(OCEAN_100MI_POINT),
    }


def _run_one(args, urls) -> dict:
    """Download/open one dataset, inspect, return result dict. Used for single run or --compare."""
    work_dir = Path(tempfile.mkdtemp(prefix="ne_ocean_inspect_"))
    sf = None
    shp_path = None
    used_local = False
    try:
        if args.local is not None:
            p = args.local.resolve()
            if p.is_dir():
                shp_path = find_shapefile(p)
                if shp_path is None:
                    raise SystemExit(f"No .shp found in {p}")
                used_local = True
            elif p.suffix.lower() == ".zip":
                with zipfile.ZipFile(p) as zf:
                    zf.extractall(work_dir)
                shp_path = find_shapefile(work_dir)
                if shp_path is None:
                    raise SystemExit(f"No .shp in zip {p}")
                used_local = True
            elif p.suffix.lower() == ".shp":
                shp_path = p
                used_local = True
            else:
                raise SystemExit(f"Unknown format: {p}")
            sf = shapefile.Reader(str(shp_path)) if shapefile else None
        else:
            if args.no_download:
                raise SystemExit("Use --local PATH or remove --no-download to allow download.")
            if shapefile is None:
                raise SystemExit("Install pyshp to inspect shapefile: pip install pyshp")
            zip_bytes = None
            for url in urls:
                print(f"[{args.dataset}] Trying {url} ...", file=sys.stderr)
                zip_bytes = download_zip_to_memory(url)
                if zip_bytes is not None:
                    break
            if zip_bytes is None:
                raise SystemExit("Download failed.")
            sf, _ = open_shapefile_from_zip(zip_bytes)

        if shapefile is None:
            raise SystemExit("Install pyshp to inspect shapefile: pip install pyshp")

        out = inspect_with_pyshp(sf)
        out["dataset"] = args.dataset
        out["source"] = "local" if used_local else "download (in-memory)"
        out["path"] = str(shp_path) if shp_path else "(from zip in memory)"

        geom = build_geometries_with_shapely(sf if not shp_path else shp_path)
        run_point_tests(geom, out)

        if args.dataset == "ocean":
            out["suitability"] = {
                "for_point_in_ocean": "yes – single polygon; use ST_Contains in PostGIS",
                "for_ocean_name": "no – no name attribute. Use marine dataset for names.",
            }
        else:
            out["suitability"] = {
                "for_point_in_ocean": "yes – multiple named polygons; use ST_Contains in PostGIS",
                "for_ocean_name": "yes – name-like attribute present." if out["has_name_like_field"] else "check – no name-like attribute in sample.",
            }
        if not out["has_name_like_field"] and args.dataset == "ocean":
            out["suitability"]["for_ocean_name"] = "no – no name-like attribute. Use ne_10m_geography_marine_polys for names."
        if out.get("point_tests", {}).get("coastline_point_inside") is False and out.get("point_tests", {}).get("ocean_100mi_point_inside") is True:
            out["suitability"]["note"] = "Coastline test point is on land; 100mi ocean point is inside. Expected for land/water boundary."
        elif out.get("point_tests", {}).get("coastline_point_inside") is True:
            out["point_tests"]["coastline_note"] = "Coastline point is inside polygon – water-side or coastal water."

        return out
    finally:
        if sf is not None and shp_path is None:
            try:
                sf.close()
            except Exception:
                pass
        if work_dir.exists():
            import shutil
            shutil.rmtree(work_dir, ignore_errors=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Download and inspect Natural Earth ocean or marine dataset.")
    parser.add_argument(
        "--dataset",
        choices=["ocean", "marine"],
        default="ocean",
        help="Dataset: ocean (50m, no names) or marine (10m, named seas/oceans). Default: ocean",
    )
    parser.add_argument(
        "--local",
        type=Path,
        default=None,
        help="Use local zip or directory containing .shp instead of downloading",
    )
    parser.add_argument(
        "--no-download",
        action="store_true",
        help="Fail if --local not set and download would be needed",
    )
    parser.add_argument(
        "--compare",
        action="store_true",
        help="Download and inspect both ocean and marine datasets; print combined comparison (ignores --local)",
    )
    args = parser.parse_args()

    if args.compare:
        results = {}
        for name in ("ocean", "marine"):
            args.dataset = name
            args.local = None
            results[name] = _run_one(args, DOWNLOAD_URLS[name])
        print(json.dumps({"ocean": results["ocean"], "marine": results["marine"]}, indent=2))
        return

    out = _run_one(args, DOWNLOAD_URLS[args.dataset])
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
