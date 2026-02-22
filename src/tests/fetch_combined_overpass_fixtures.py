#!/usr/bin/env python3
"""
Regenerate combined Overpass response fixtures from the live API.

Fetches the same combined query used by reverse geocoding for each default test
coordinate and writes fixtures/combined_overpass/{lat}_{lon}.json. Use after
changing the query or when fixtures were deleted.

Usage (no Django or backend required):

  cd src/tests && python fetch_combined_overpass_fixtures.py --url https://overpass-api.de/api/interpreter
  python fetch_combined_overpass_fixtures.py --url https://... --dry-run

Options:
  --url URL           Overpass API URL (required)
  --no-verify-ssl     Skip SSL certificate verification (e.g. for self-signed local servers)
  --dry-run           Only list coordinates that would be fetched
  --existing          Only refresh existing fixture files (do not create from default list)
  --jobs N            Number of concurrent requests (default 4)
"""
import argparse
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
import urllib3

tests_dir = os.path.dirname(os.path.abspath(__file__))

# Must match geo_lib.reverse_geocoding.combined_overpass.build_combined_query
_BBOX_HALF_DEGREES = 0.5  # match combined_overpass (0.25 misses e.g. Yellowstone)
_LAKE_RADIUS_M = int(1.0 * 1609.34)   # 1 mile, same as LAKE_PROXIMITY_MILES default
_CITY_RADIUS_M = int(5.0 * 1609.34)    # 5 miles, same as CITY_PROXIMITY_MILES default
_COORDINATE_PRECISION = 3


def _build_combined_query(latitude: float, longitude: float) -> str:
    """Build the combined Overpass QL query (same as combined_overpass.build_combined_query)."""
    south = latitude - _BBOX_HALF_DEGREES
    north = latitude + _BBOX_HALF_DEGREES
    west = longitude - _BBOX_HALF_DEGREES
    east = longitude + _BBOX_HALF_DEGREES
    # Longer timeout for fixture fetch; large bbox can be slow on public APIs
    return f"""[out:json][timeout:120];
(
  relation["boundary"="administrative"]["admin_level"~"2|4|6|8"]({south},{west},{north},{east});
  relation["boundary"="protected_area"]({south},{west},{north},{east});
  relation["leisure"="nature_reserve"]({south},{west},{north},{east});
  relation["boundary"="national_park"]({south},{west},{north},{east});
  relation["leisure"="park"]({south},{west},{north},{east});
  relation["landuse"="recreation_ground"]({south},{west},{north},{east});
  way["boundary"="protected_area"]({south},{west},{north},{east});
  way["leisure"="park"]({south},{west},{north},{east});
  way["landuse"="recreation_ground"]({south},{west},{north},{east});
  way["natural"="water"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  way["water"="lake"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  node["place"~"town|city|village"](around:{_CITY_RADIUS_M},{latitude},{longitude});
)->.all;
.all out tags geom center bb;
"""


def _round_coordinate(latitude: float, longitude: float) -> tuple:
    """Round to cache precision (same as geo_lib.spatial.coordinates.round_coordinate)."""
    return (round(latitude, _COORDINATE_PRECISION), round(longitude, _COORDINATE_PRECISION))


# Coordinates used by test_geo_lib.test_reverse_geocode.
DEFAULT_FIXTURE_COORDINATES = [
    (36.156, -86.925),
    (37.775, -122.419),
    (39.0, -105.0),
    (39.07, -108.73),
    (39.222, -105.933),
    (39.42, -105.65),
    (39.563, -105.15),
    (39.723, -104.958),
    (39.746, -104.844),
    (40.0, -105.0),
    (40.211, -105.769),
    (40.251, -105.824),
    (40.34, -105.68),
    (40.343, -105.684),
    (41.694, -101.384),
    (41.729, -102.872),
    (42.209, -71.108),
    (42.209, -71.119),
    (42.218, -71.113),
    (42.223, -71.098),
    (42.729, -102.417),
    (44.604, -110.476),  # Yellowstone NP (44.60384, -110.47567)
]


def _parse_fixture_filename(name: str):
    """Return (lat, lon) or None for a filename like 39.0_-105.0.json."""
    if not name.endswith('.json'):
        return None
    base = name[:-5]
    parts = base.split('_', 1)
    if len(parts) != 2:
        return None
    try:
        lat = float(parts[0])
        lon = float(parts[1])
        return (lat, lon)
    except ValueError:
        return None


def _fetch_one(
    name: str,
    lat: float,
    lon: float,
    url: str,
    timeout: int,
    verify_ssl: bool,
    fixtures_dir: str,
) -> tuple[str, bool, str | None]:
    """
    Fetch one fixture. Returns (name, success, error_message).
    """
    query = _build_combined_query(lat, lon)
    try:
        response = requests.post(
            url,
            data=query,
            timeout=timeout,
            headers={'Content-Type': 'text/plain; charset=utf-8'},
            verify=verify_ssl,
        )
        response.raise_for_status()
        data = response.json()
    except requests.RequestException as e:
        return (name, False, f'request failed: {e}')
    except json.JSONDecodeError as e:
        return (name, False, f'invalid JSON: {e}')

    remark = data.get('remark') or ''
    elements = data.get('elements') or []
    if 'runtime error' in remark or 'timed out' in remark or (not elements and 'remark' in data):
        return (name, False, f'Overpass error or empty (not overwriting): {remark[:80]!r}')

    out_path = os.path.join(fixtures_dir, name)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    return (name, True, None)


def main():
    parser = argparse.ArgumentParser(description='Fetch combined Overpass responses into test fixtures.')
    parser.add_argument('--url', type=str, default=None, help='Overpass API URL (required)')
    parser.add_argument('--no-verify-ssl', action='store_true', help='Skip SSL certificate verification')
    parser.add_argument('--dry-run', action='store_true', help='Only list coordinates that would be fetched')
    parser.add_argument(
        '--existing',
        action='store_true',
        help='Only refresh existing fixture files; do not create from default coordinate list',
    )
    parser.add_argument('--jobs', type=int, default=4, help='Number of concurrent requests (default 4)')
    args = parser.parse_args()

    if not args.url:
        print('Error: --url required', file=sys.stderr)
        sys.exit(1)
    url = args.url

    fixtures_dir = os.path.join(tests_dir, 'fixtures', 'combined_overpass')

    if args.existing:
        if not os.path.isdir(fixtures_dir):
            print(f'Error: Fixtures directory not found: {fixtures_dir}', file=sys.stderr)
            sys.exit(1)
        names = [n for n in os.listdir(fixtures_dir) if n.endswith('.json')]
        coords = []
        for name in sorted(names):
            parsed = _parse_fixture_filename(name)
            if parsed is None:
                print(f'Warning: Skipping unparseable filename: {name}', file=sys.stderr)
                continue
            coords.append((name, parsed[0], parsed[1]))
        if not coords:
            print('No fixture files found. Run without --existing to create from default coordinates.', file=sys.stderr)
            sys.exit(1)
    else:
        seen = set()
        coords = []
        for lat, lon in DEFAULT_FIXTURE_COORDINATES:
            lat_r, lon_r = _round_coordinate(lat, lon)
            name = f'{lat_r}_{lon_r}.json'
            if name in seen:
                continue
            seen.add(name)
            coords.append((name, lat_r, lon_r))
        if not os.path.isdir(fixtures_dir):
            os.makedirs(fixtures_dir, exist_ok=True)
            print(f'Created {fixtures_dir}')

    print(f'Found {len(coords)} fixture(s) in {fixtures_dir}')
    if args.dry_run:
        for name, lat, lon in coords:
            print(f'  {name}  -> ({lat}, {lon})')
        return

    verify_ssl = not args.no_verify_ssl
    timeout = 150  # HTTP timeout; must be longer than Overpass [timeout:120]
    jobs = max(1, args.jobs)
    if not verify_ssl:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

    print_lock = threading.Lock()

    def run_one(item):
        name, lat, lon = item
        return _fetch_one(name, lat, lon, url, timeout, verify_ssl, fixtures_dir)

    ok = 0
    err = 0
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = {executor.submit(run_one, (name, lat, lon)): (name, lat, lon) for name, lat, lon in coords}
        for future in as_completed(futures):
            name, success, error_message = future.result()
            if success:
                ok += 1
                with print_lock:
                    print(f'Updated {name}')
            else:
                err += 1
                name, lat, lon = futures[future]
                with print_lock:
                    print(f'ERROR {name} ({lat}, {lon}): {error_message}', file=sys.stderr)

    print('')
    print(f'Updated {ok} fixture(s).')
    if err:
        print(f'Failed {err} fixture(s).', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
