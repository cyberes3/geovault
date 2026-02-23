#!/usr/bin/env python3
"""
Inspect one ski-resort orphan cluster: list way_ids with distance from bbox_center,
optionally run Overpass for piste/aerialway ways, and optionally plot bbox + ways on a Leaflet map.

Use when a cluster's bbox_center is far from the Google resort (e.g. "skipped: 2.6 mi")
to see which orphan ways pull the bbox and to fetch OSM geometry via Overpass.

Usage (from src/areas-server):
  python scripts/inspect_ski_resort_cluster.py --bbox-center 59.9793,10.7401
  python scripts/inspect_ski_resort_cluster.py --bbox-center 59.9793,10.7401 --overpass [--overpass-out out.json]
  python scripts/inspect_ski_resort_cluster.py --bbox-center 59.9793,10.7401 --overpass --map-out cluster.html [--google-place 59.9914,10.6683]
  python scripts/inspect_ski_resort_cluster.py --candidate-index 6 --candidates ski_resort_orphan_candidates.csv --map-out cluster.html
"""
import argparse
import csv
import json
import math
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

OVERPASS_DEFAULT_URL = "https://overpass-api.de/api/interpreter"
DEFAULT_GRID_SIZE = 0.02


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _overpass_to_geojson(elements: list) -> dict:
    """Convert Overpass elements with geometry to GeoJSON FeatureCollection of LineStrings."""
    features = []
    for el in elements:
        if el.get("type") != "way":
            continue
        geom = el.get("geometry")
        if not geom or len(geom) < 2:
            continue
        coords = [[p["lon"], p["lat"]] for p in geom]
        features.append({
            "type": "Feature",
            "properties": {"id": el.get("id"), **{k: v for k, v in el.get("tags", {}).items()}},
            "geometry": {"type": "LineString", "coordinates": coords},
        })
    return {"type": "FeatureCollection", "features": features}


def _fetch_overpass(south: float, west: float, north: float, east: float, overpass_url: str) -> dict:
    """Run Overpass query for piste/aerialway ways in bbox. Returns full API response dict."""
    query = (
        f"[out:json][timeout:30];"
        f"( way({south},{west},{north},{east})[\"piste:type\"];"
        f"  way({south},{west},{north},{east})[\"aerialway\"];"
        f"); out geom;"
    )
    req = Request(
        overpass_url,
        data=query.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST",
    )
    with urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _write_map_html(
    overpass_data: dict,
    min_lat: float,
    max_lat: float,
    min_lon: float,
    max_lon: float,
    bbox_center_lat: float,
    bbox_center_lon: float,
    out_path: Path,
    google_lat: float | None = None,
    google_lon: float | None = None,
) -> None:
    """Write Leaflet HTML map with bbox, bbox_center, optional Google place, and Overpass ways."""
    elements = overpass_data.get("elements", [])
    geojson = _overpass_to_geojson(elements)
    geojson_str = json.dumps(geojson)
    bbox_ring = [
        [min_lon, min_lat],
        [min_lon, max_lat],
        [max_lon, max_lat],
        [max_lon, min_lat],
        [min_lon, min_lat],
    ]
    bbox_geojson = json.dumps({
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "properties": {},
            "geometry": {"type": "Polygon", "coordinates": [bbox_ring]},
        }],
    })
    center_lat = (min_lat + max_lat) / 2
    center_lon = (min_lon + max_lon) / 2
    if google_lat is not None and google_lon is not None:
        center_lat = (center_lat + bbox_center_lat + google_lat) / 3
        center_lon = (center_lon + bbox_center_lon + google_lon) / 3
    else:
        center_lat = (center_lat + bbox_center_lat) / 2
        center_lon = (center_lon + bbox_center_lon) / 2
    html = f"""<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Cluster map</title>
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
</head>
<body>
  <div id="map" style="height: 800px;"></div>
  <script>
    var map = L.map('map').setView([{center_lat}, {center_lon}], 14);
    L.tileLayer('https://{{s}}.tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png', {{ attribution: '&copy; OSM' }}).addTo(map);

    var bboxLayer = L.geoJSON({bbox_geojson}, {{
      style: {{ color: '#00f', weight: 2, fillOpacity: 0.05 }}
    }}).addTo(map);

    var waysLayer = L.geoJSON({geojson_str}, {{
      style: {{ color: '#080', weight: 2 }}
    }}).addTo(map);

    L.circleMarker([{bbox_center_lat}, {bbox_center_lon}], {{
      radius: 10, fillColor: '#00f', color: '#00f', weight: 2, fillOpacity: 0.8
    }}).addTo(map).bindTooltip('bbox_center', {{ permanent: false }});

    L.circleMarker([{bbox_center_lat}, {bbox_center_lon}], {{
      radius: 6, fillColor: '#fff', color: '#00f', weight: 2
    }}).addTo(map);
"""
    if google_lat is not None and google_lon is not None:
        html += f"""
    L.circleMarker([{google_lat}, {google_lon}], {{
      radius: 10, fillColor: '#c00', color: '#c00', weight: 2, fillOpacity: 0.8
    }}).addTo(map).bindTooltip('Google resort', {{ permanent: false }});

    L.circleMarker([{google_lat}, {google_lon}], {{
      radius: 6, fillColor: '#fff', color: '#c00', weight: 2
    }}).addTo(map);
"""
    html += """
  </script>
</body>
</html>
"""
    out_path.write_text(html, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Inspect one orphan cluster: way_ids, distances from bbox_center, optional Overpass query"
    )
    parser.add_argument(
        "orphan_report",
        type=Path,
        nargs="?",
        default=Path("ski_resort_orphans.csv"),
        help="Orphan report CSV (default: ski_resort_orphans.csv)",
    )
    parser.add_argument(
        "--candidates",
        type=Path,
        default=Path("ski_resort_orphan_candidates.csv"),
        help="Candidates CSV to resolve bbox from index (default: ski_resort_orphan_candidates.csv)",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--bbox-center",
        type=str,
        metavar="LAT,LON",
        help="bbox_center of the cluster to inspect (e.g. 59.9793,10.7401)",
    )
    group.add_argument(
        "--candidate-index",
        type=int,
        metavar="N",
        help="1-based row index in candidates CSV (e.g. 6 for 6th candidate)",
    )
    parser.add_argument(
        "--grid-size",
        type=float,
        default=DEFAULT_GRID_SIZE,
        help=f"Grid size in degrees, must match analyze script (default {DEFAULT_GRID_SIZE})",
    )
    parser.add_argument(
        "--overpass",
        action="store_true",
        help="Run Overpass query for piste/aerialway ways in the cluster bbox and print or save result",
    )
    parser.add_argument(
        "--overpass-url",
        type=str,
        default=OVERPASS_DEFAULT_URL,
        help=f"Overpass API URL (default {OVERPASS_DEFAULT_URL})",
    )
    parser.add_argument(
        "--overpass-out",
        type=Path,
        default=None,
        help="If set, write Overpass JSON to this file; otherwise print element count and query",
    )
    parser.add_argument(
        "--map-out",
        type=Path,
        default=None,
        metavar="PATH",
        help="Write Leaflet HTML map (bbox + ways + bbox_center, optional --google-place). Runs Overpass if needed.",
    )
    parser.add_argument(
        "--google-place",
        type=str,
        default=None,
        metavar="LAT,LON",
        help="Optional Google resort point (red marker) for --map-out",
    )
    args = parser.parse_args()

    if not args.orphan_report.is_file():
        print(f"Error: orphan report not found: {args.orphan_report}", file=sys.stderr)
        sys.exit(1)

    # Resolve bbox and bbox_center
    if args.bbox_center is not None:
        try:
            lat_s, lon_s = args.bbox_center.strip().split(",")
            bbox_center_lat = float(lat_s.strip())
            bbox_center_lon = float(lon_s.strip())
        except ValueError:
            print("Error: --bbox-center must be LAT,LON (e.g. 59.9793,10.7401)", file=sys.stderr)
            sys.exit(1)
        min_lat = max_lat = bbox_center_lat
        min_lon = max_lon = bbox_center_lon
        # We'll get bbox from the cluster points below
    else:
        if not args.candidates.is_file():
            print(f"Error: candidates file not found: {args.candidates}", file=sys.stderr)
            sys.exit(1)
        with open(args.candidates, encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f)
            rows = list(reader)
        idx = args.candidate_index
        if idx < 1 or idx > len(rows):
            print(f"Error: --candidate-index must be 1..{len(rows)}", file=sys.stderr)
            sys.exit(1)
        r = rows[idx - 1]
        min_lat = float(r["min_lat"])
        max_lat = float(r["max_lat"])
        min_lon = float(r["min_lon"])
        max_lon = float(r["max_lon"])
        bbox_center_lat = (min_lat + max_lat) / 2.0
        bbox_center_lon = (min_lon + max_lon) / 2.0

    # Read orphan report and group by grid cell
    g = args.grid_size
    cells = {}
    with open(args.orphan_report, encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != ["lon", "lat", "way_id", "closest_resort", "distance_miles"]:
            print("Error: expected CSV columns: lon, lat, way_id, closest_resort, distance_miles", file=sys.stderr)
            sys.exit(1)
        for row in reader:
            try:
                lon = float(row["lon"])
                lat = float(row["lat"])
            except (ValueError, KeyError):
                continue
            key = (round(lat / g) * g, round(lon / g) * g)
            cells.setdefault(key, []).append({
                "lat": lat,
                "lon": lon,
                "way_id": row.get("way_id", ""),
                "closest_resort": row.get("closest_resort", ""),
                "distance_miles": row.get("distance_miles", ""),
            })

    # Find cluster that contains bbox_center (same grid cell)
    key = (round(bbox_center_lat / g) * g, round(bbox_center_lon / g) * g)
    points = cells.get(key, [])
    if not points:
        # If we used --candidate-index we already have bbox; else we only have a point
        if args.bbox_center is not None:
            print(f"No orphan points in grid cell {key!r} for bbox_center ({bbox_center_lat}, {bbox_center_lon}).", file=sys.stderr)
            sys.exit(1)
        points = []

    if points and args.bbox_center is not None:
        lats = [p["lat"] for p in points]
        lons = [p["lon"] for p in points]
        min_lat, max_lat = min(lats), max(lats)
        min_lon, max_lon = min(lons), max(lons)
        bbox_center_lat = (min_lat + max_lat) / 2.0
        bbox_center_lon = (min_lon + max_lon) / 2.0

    # Distances from bbox_center (fliers = large distance)
    for p in points:
        p["dist_km"] = _haversine_km(bbox_center_lat, bbox_center_lon, p["lat"], p["lon"])
    points_sorted = sorted(points, key=lambda x: -x["dist_km"])

    print(f"Cluster bbox_center: ({bbox_center_lat:.4f}, {bbox_center_lon:.4f})", file=sys.stderr)
    print(f"Bbox: min_lat={min_lat:.4f} max_lat={max_lat:.4f} min_lon={min_lon:.4f} max_lon={max_lon:.4f}", file=sys.stderr)
    print(f"Orphan count: {len(points)}", file=sys.stderr)
    print(file=sys.stderr)
    print("way_id,lat,lon,dist_km,closest_resort")
    for p in points_sorted:
        print(f"{p['way_id']},{p['lat']:.6f},{p['lon']:.6f},{p['dist_km']:.2f},{p['closest_resort']}")

    overpass_data = None
    if args.overpass or args.map_out is not None:
        margin = 0.001
        south = min_lat - margin
        west = min_lon - margin
        north = max_lat + margin
        east = max_lon + margin
        try:
            overpass_data = _fetch_overpass(south, west, north, east, args.overpass_url)
        except (HTTPError, URLError, json.JSONDecodeError) as e:
            print(f"Overpass request failed: {e}", file=sys.stderr)
            sys.exit(1)
        elements = overpass_data.get("elements", [])
        ways = [e for e in elements if e.get("type") == "way"]
        print(file=sys.stderr)
        print(f"Overpass: {len(ways)} way(s) in bbox (piste:type or aerialway)", file=sys.stderr)
        if args.overpass_out is not None:
            with open(args.overpass_out, "w", encoding="utf-8") as f:
                json.dump(overpass_data, f, indent=2)
            print(f"Wrote {args.overpass_out}", file=sys.stderr)
        elif args.overpass:
            print("Query (paste at overpass-turbo.eu or use --overpass-out to save JSON):", file=sys.stderr)
            margin = 0.001
            q = (
                f"[out:json][timeout:30];"
                f"( way({min_lat - margin},{min_lon - margin},{max_lat + margin},{max_lon + margin})[\"piste:type\"];"
                f"  way({min_lat - margin},{min_lon - margin},{max_lat + margin},{max_lon + margin})[\"aerialway\"];"
                f"); out geom;"
            )
            print(q, file=sys.stderr)

    if args.map_out is not None and overpass_data is not None:
        google_lat = google_lon = None
        if args.google_place:
            try:
                google_lat, google_lon = [float(x.strip()) for x in args.google_place.split(",")]
            except ValueError:
                print("Error: --google-place must be LAT,LON", file=sys.stderr)
                sys.exit(1)
        _write_map_html(
            overpass_data,
            min_lat,
            max_lat,
            min_lon,
            max_lon,
            bbox_center_lat,
            bbox_center_lon,
            args.map_out,
            google_lat,
            google_lon,
        )
        print(f"Wrote {args.map_out}", file=sys.stderr)
        print(f"Open in browser: file://{args.map_out.resolve()}", file=sys.stderr)


if __name__ == "__main__":
    main()
