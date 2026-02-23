#!/usr/bin/env python3
"""
Cluster orphan runs from the ski resort import report into candidate resorts.

Reads the orphan CSV (lon, lat, way_id, closest_resort, distance_miles), groups
points by a grid so nearby orphans form one candidate, and writes a CSV of
candidate resorts (suggested_name, bbox, centroid, orphan_count). Use the bbox
fields to add entries to data/ski_resorts.json (replace suggested_name with
the real resort name from OSM or a map).

Optional: --google-places API_KEY resolves each candidate's name via Google
Places API (Nearby Search with type ski_resort). Pass your API key as the
argument value. Output adds google_name and google_place_id columns.

Optional: --merge appends new candidates into data/ski_resorts.json (name from
google_name if present, else suggested_name; country/state left empty). Skips
candidates whose name already exists. Use --dry-run to print what would be added.

Usage (from src/areas-server):
  python scripts/analyze_ski_resort_orphans.py [orphan_report.csv] [--grid-size 0.02] [--out candidates.csv]
  python scripts/analyze_ski_resort_orphans.py orphan_report.csv --google-places YOUR_API_KEY [--workers 10] [--radius 5000] [--delay 0.3]
  python scripts/analyze_ski_resort_orphans.py orphan_report.csv --google-places YOUR_API_KEY --merge [--dry-run]
"""
import argparse
import csv
import json
import math
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import tqdm

PLACES_NEARBY_URL = "https://places.googleapis.com/v1/places:searchNearby"
FIELD_MASK = "places.displayName,places.id,places.location"
DEFAULT_RADIUS_M = 5000
DEFAULT_DELAY_S = 0.5
DEFAULT_WORKERS = 3
PLACES_CACHE_PRECISION = 3
DEFAULT_MAX_DISTANCE_MILES = 2.0
PLACES_RATE_LIMIT_RETRIES = 5
PLACES_RATE_LIMIT_BACKOFF_BASE_S = 2.0
PLACES_RATE_LIMIT_BACKOFF_MAX_S = 60.0


def _haversine_miles(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Return great-circle distance in miles between (lat1, lon1) and (lat2, lon2)."""
    r = 3958.8  # Earth radius in miles
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _google_places_nearby_ski_resort(
        api_key: str,
        lat: float,
        lon: float,
        radius_m: float = DEFAULT_RADIUS_M,
) -> tuple[str, str, float | None, float | None]:
    """Call Places API (New) Nearby Search for ski_resort near (lat, lon). Returns (display_name, place_id, place_lat, place_lon) or ("", "", None, None). Retries on 429/503 with exponential backoff."""
    body = {
        "includedTypes": ["ski_resort"],
        "maxResultCount": 1,
        "rankPreference": "DISTANCE",
        "locationRestriction": {
            "circle": {
                "center": {"latitude": lat, "longitude": lon},
                "radius": radius_m,
            }
        },
    }
    data = json.dumps(body).encode("utf-8")
    last_error = None
    for attempt in range(PLACES_RATE_LIMIT_RETRIES):
        req = Request(
            PLACES_NEARBY_URL,
            data=data,
            headers={
                "Content-Type": "application/json",
                "X-Goog-Api-Key": api_key,
                "X-Goog-FieldMask": FIELD_MASK,
            },
            method="POST",
        )
        try:
            with urlopen(req, timeout=15) as resp:
                out = json.loads(resp.read().decode("utf-8"))
        except HTTPError as e:
            last_error = e
            if e.code in (429, 503) and attempt < PLACES_RATE_LIMIT_RETRIES - 1:
                backoff = min(
                    PLACES_RATE_LIMIT_BACKOFF_MAX_S,
                    PLACES_RATE_LIMIT_BACKOFF_BASE_S ** (attempt + 1),
                )
                time.sleep(backoff)
                continue
            if e.code in (429, 503):
                tqdm.tqdm.write(f"  Rate limit ({e.code}) after {PLACES_RATE_LIMIT_RETRIES} retries for ({lat:.4f}, {lon:.4f})")
            else:
                tqdm.tqdm.write(f"  Google Places HTTP error {e.code} for ({lat:.4f}, {lon:.4f}): {e.reason}")
            return "", "", None, None
        except URLError as e:
            tqdm.tqdm.write(f"  Google Places request failed for ({lat:.4f}, {lon:.4f}): {e.reason}")
            return "", "", None, None
        except (json.JSONDecodeError, KeyError) as e:
            tqdm.tqdm.write(f"  Google Places bad response for ({lat:.4f}, {lon:.4f}): {e!r}")
            return "", "", None, None
        places = out.get("places") or []
        if not places:
            return "", "", None, None
        place = places[0]
        name = ""
        if "displayName" in place and "text" in place["displayName"]:
            name = place["displayName"]["text"]
        pid = place.get("id", "")
        loc = place.get("location") or {}
        place_lat = loc.get("latitude")
        place_lon = loc.get("longitude")
        if place_lat is None or place_lon is None:
            place_lat = place_lon = None
        return name, pid, place_lat, place_lon
    return "", "", None, None


def _google_places_worker(
        args: tuple[int, dict, str, float, dict, threading.Lock],
) -> tuple[int, str, str, float | None, float | None]:
    """Worker for parallel Google Places lookup. Returns (index, name, place_id, place_lat, place_lon)."""
    i, c, api_key, radius_m, cache, lock = args
    lat = c["centroid_lat"]
    lon = c["centroid_lon"]
    key = (round(lat, PLACES_CACHE_PRECISION), round(lon, PLACES_CACHE_PRECISION))
    with lock:
        if key in cache:
            r = cache[key]
            return (i, r[0], r[1], r[2], r[3])
    result = _google_places_nearby_ski_resort(api_key, lat, lon, radius_m=radius_m)
    with lock:
        cache[key] = result
    return (i, result[0], result[1], result[2], result[3])


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Cluster orphan report into candidate resorts (bbox + centroid) for ski_resorts.json"
    )
    parser.add_argument(
        "input",
        type=Path,
        nargs="?",
        default=Path("ski_resort_orphans.csv"),
        help="Orphan report CSV from import (default: ski_resort_orphans.csv)",
    )
    parser.add_argument(
        "--grid-size",
        type=float,
        default=0.02,
        help="Grid cell size in degrees for clustering (~2.2 km at mid-lat; default 0.02)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Output CSV of candidate resorts (default: ski_resort_orphan_candidates.csv)",
    )
    parser.add_argument(
        "--google-places",
        metavar="API_KEY",
        default=None,
        help="Resolve candidate names via Google Places API (Nearby Search, type ski_resort). Pass the API key as the argument value.",
    )
    parser.add_argument(
        "--radius",
        type=float,
        default=DEFAULT_RADIUS_M,
        metavar="METERS",
        help=f"Search radius in meters for Google Places (default {DEFAULT_RADIUS_M}).",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_DELAY_S,
        metavar="SECONDS",
        help=f"Delay between Google API calls in seconds when --workers 1 (default {DEFAULT_DELAY_S}).",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=DEFAULT_WORKERS,
        metavar="N",
        help=f"Parallel Google API workers (default {DEFAULT_WORKERS}). Use 1 for sequential with --delay.",
    )
    parser.add_argument(
        "--max-distance-miles",
        type=float,
        default=DEFAULT_MAX_DISTANCE_MILES,
        metavar="MILES",
        help=f"Reject Google result if centroid is farther than this from the place (default {DEFAULT_MAX_DISTANCE_MILES}).",
    )
    parser.add_argument(
        "--merge",
        action="store_true",
        help="Append new candidates to data/ski_resorts.json (name from google_name or suggested_name).",
    )
    parser.add_argument(
        "--json",
        type=Path,
        default=Path("data/ski_resorts.json"),
        help="Path to ski_resorts.json for --merge (default: data/ski_resorts.json).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="With --merge: print what would be added without writing the JSON file.",
    )
    parser.add_argument(
        "--close-matches-out",
        type=Path,
        default=None,
        help="With --google-places: CSV of candidates skipped (distance > max); default ski_resort_close_matches.csv.",
    )
    args = parser.parse_args()

    if not args.input.is_file():
        print(f"Error: input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    # Read orphan report
    rows = []
    with open(args.input, encoding="utf-8", newline="") as f:
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
            rows.append({"lon": lon, "lat": lat, "way_id": row.get("way_id", ""), "closest_resort": row.get("closest_resort", ""), "distance_miles": row.get("distance_miles", "")})

    if not rows:
        print("No rows in orphan report.", file=sys.stderr)
        sys.exit(0)

    # Cluster by grid cell
    g = args.grid_size
    cells = {}
    for r in rows:
        lat, lon = r["lat"], r["lon"]
        key = (round(lat / g) * g, round(lon / g) * g)
        cells.setdefault(key, []).append(r)

    # Build candidate per cell: bbox, centroid = bbox center, count (sorted by count descending)
    candidates = []
    for idx, ((_cell_lat, _cell_lon), points) in enumerate(
            sorted(cells.items(), key=lambda x: -len(x[1])), start=1
    ):
        lats = [p["lat"] for p in points]
        lons = [p["lon"] for p in points]
        min_lat, max_lat = min(lats), max(lats)
        min_lon, max_lon = min(lons), max(lons)
        centroid_lat = (min_lat + max_lat) / 2.0
        centroid_lon = (min_lon + max_lon) / 2.0
        candidates.append({
            "suggested_name": f"Candidate {idx}",
            "min_lat": min_lat,
            "max_lat": max_lat,
            "min_lon": min_lon,
            "max_lon": max_lon,
            "centroid_lat": centroid_lat,
            "centroid_lon": centroid_lon,
            "orphan_count": len(points),
        })

    out_path = args.out or Path("ski_resort_orphan_candidates.csv")
    fieldnames = ["suggested_name", "min_lat", "max_lat", "min_lon", "max_lon", "centroid_lat", "centroid_lon", "orphan_count"]
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(candidates)
    print(f"Clustered {len(rows)} orphans into {len(candidates)} candidate resort(s).", file=sys.stderr)
    print(f"Wrote {out_path}", file=sys.stderr)
    if candidates:
        top = sorted(candidates, key=lambda c: -c["orphan_count"])[:5]
        print("Top 5 by orphan count:", file=sys.stderr)
        for i, c in enumerate(top, 1):
            print(f"  {i}. bbox_center ({c['centroid_lat']:.4f}, {c['centroid_lon']:.4f}) count={c['orphan_count']}", file=sys.stderr)

    if args.google_places is not None:
        api_key = (args.google_places or "").strip()
        if not api_key:
            print("Error: --google-places requires an API key value.", file=sys.stderr)
            sys.exit(1)
        max_mi = args.max_distance_miles
        close_matches = []
        workers = max(1, int(args.workers))
        cache: dict[tuple[float, float], tuple[str, str, float | None, float | None]] = {}
        cache_lock = threading.Lock()
        # Pre-fill results list so we can assign by index when parallel
        results: list[tuple[str, str, float | None, float | None]] = [
            ("", "", None, None) for _ in candidates
        ]

        if workers <= 1:
            print(f"Resolving names via Google Places (radius={args.radius}m, delay={args.delay}s, max distance={max_mi} mi)...", file=sys.stderr)
            for c in tqdm.tqdm(candidates, desc="Google Places", unit="candidate"):
                key = (round(c["centroid_lat"], PLACES_CACHE_PRECISION), round(c["centroid_lon"], PLACES_CACHE_PRECISION))
                if key in cache:
                    name, pid, place_lat, place_lon = cache[key]
                else:
                    name, pid, place_lat, place_lon = _google_places_nearby_ski_resort(
                        api_key, c["centroid_lat"], c["centroid_lon"], radius_m=args.radius
                    )
                    cache[key] = (name, pid, place_lat, place_lon)
                    time.sleep(args.delay)
                clat, clon = c["centroid_lat"], c["centroid_lon"]
                if place_lat is not None and place_lon is not None:
                    delta_mi = _haversine_miles(clat, clon, place_lat, place_lon)
                    if delta_mi > max_mi:
                        close_matches.append({
                            "suggested_name": c["suggested_name"],
                            "centroid_lat": clat,
                            "centroid_lon": clon,
                            "google_name": name,
                            "google_place_id": pid,
                            "distance_miles": delta_mi,
                            "max_distance_miles": max_mi,
                        })
                        name = ""
                        pid = ""
                        tqdm.tqdm.write(f"  (skipped: {delta_mi:.1f} mi > {max_mi} mi) bbox_center ({clat:.4f}, {clon:.4f})")
                    else:
                        tqdm.tqdm.write(f"  {name} ({delta_mi:.1f} mi) bbox_center ({clat:.4f}, {clon:.4f})")
                else:
                    if name:
                        tqdm.tqdm.write(f"  {name} (distance unknown) bbox_center ({clat:.4f}, {clon:.4f})")
                    else:
                        tqdm.tqdm.write(f"  (no place at bbox_center ({clat:.4f}, {clon:.4f}))")
                c["google_name"] = name
                c["google_place_id"] = pid
                time.sleep(args.delay)
        else:
            print(f"Resolving names via Google Places (workers={workers}, radius={args.radius}m, max distance={max_mi} mi, cache ~{PLACES_CACHE_PRECISION} decimals)...", file=sys.stderr)
            task_args = [
                (i, c, api_key, args.radius, cache, cache_lock)
                for i, c in enumerate(candidates)
            ]
            with ThreadPoolExecutor(max_workers=workers) as executor:
                futures = {executor.submit(_google_places_worker, a): a[0] for a in task_args}
                for future in tqdm.tqdm(as_completed(futures), total=len(futures), desc="Google Places", unit="candidate"):
                    i = futures[future]
                    try:
                        r = future.result()
                        results[r[0]] = (r[1], r[2], r[3], r[4])
                    except Exception:
                        results[i] = ("", "", None, None)
            # Assign and build close_matches / per-candidate output in order
            for i, c in enumerate(candidates):
                name, pid, place_lat, place_lon = results[i]
                c["google_name"] = name
                c["google_place_id"] = pid
                clat, clon = c["centroid_lat"], c["centroid_lon"]
                if place_lat is not None and place_lon is not None:
                    delta_mi = _haversine_miles(clat, clon, place_lat, place_lon)
                    if delta_mi > max_mi:
                        close_matches.append({
                            "suggested_name": c["suggested_name"],
                            "centroid_lat": clat,
                            "centroid_lon": clon,
                            "google_name": name,
                            "google_place_id": pid,
                            "distance_miles": delta_mi,
                            "max_distance_miles": max_mi,
                        })
                        tqdm.tqdm.write(f"  (skipped: {delta_mi:.1f} mi > {max_mi} mi) bbox_center ({clat:.4f}, {clon:.4f})")
                    else:
                        tqdm.tqdm.write(f"  {name} ({delta_mi:.1f} mi) bbox_center ({clat:.4f}, {clon:.4f})")
                else:
                    if name:
                        tqdm.tqdm.write(f"  {name} (distance unknown) bbox_center ({clat:.4f}, {clon:.4f})")
                    else:
                        tqdm.tqdm.write(f"  (no place at bbox_center ({clat:.4f}, {clon:.4f}))")
        if cache:
            saved = len(candidates) - len(cache)
            if saved > 0:
                print(f"Cache: {len(cache)} unique lookups, {saved} duplicate(s) skipped (rounded to {PLACES_CACHE_PRECISION} decimals).", file=sys.stderr)
        if close_matches:
            close_out = args.close_matches_out or Path("ski_resort_close_matches.csv")
            close_fieldnames = ["suggested_name", "centroid_lat", "centroid_lon", "google_name", "google_place_id", "distance_miles", "max_distance_miles"]
            with open(close_out, "w", encoding="utf-8", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=close_fieldnames)
                writer.writeheader()
                writer.writerows(close_matches)
            print(f"Wrote {len(close_matches)} close match(es) (just over {max_mi} mi) to {close_out}", file=sys.stderr)

    if args.merge:
        new_entries = []
        for c in candidates:
            name = (c.get("google_name") or "").strip() or (c.get("suggested_name") or "").strip()
            if not name:
                continue
            new_entries.append({
                "name": name,
                "country": "",
                "state": "",
                "bbox": {
                    "min_lat": c["min_lat"],
                    "max_lat": c["max_lat"],
                    "min_lon": c["min_lon"],
                    "max_lon": c["max_lon"],
                },
            })
        if not new_entries:
            print("No valid candidate entries to merge.", file=sys.stderr)
        else:
            existing = []
            if args.json.is_file():
                with open(args.json, "r", encoding="utf-8") as f:
                    data = json.load(f)
                existing = data.get("ski_resorts", [])
            existing_names = {r.get("name") for r in existing if r.get("name")}
            to_add = [e for e in new_entries if e["name"] not in existing_names]
            if not to_add:
                print("All candidates already exist in ski_resorts.json.", file=sys.stderr)
            elif args.dry_run:
                print("Would add the following resorts:", file=sys.stderr)
                for e in to_add:
                    b = e["bbox"]
                    print(f"  {e['name']}: bbox ({b['min_lat']:.4f},{b['min_lon']:.4f}) - ({b['max_lat']:.4f},{b['max_lon']:.4f})", file=sys.stderr)
            else:
                if not args.json.is_file():
                    print(f"Error: JSON file not found: {args.json}", file=sys.stderr)
                    sys.exit(1)
                merged = existing + to_add
                with open(args.json, "w", encoding="utf-8") as f:
                    json.dump({"ski_resorts": merged}, f, indent=2)
                print(f"Added {len(to_add)} resort(s) to {args.json}.", file=sys.stderr)


if __name__ == "__main__":
    main()
