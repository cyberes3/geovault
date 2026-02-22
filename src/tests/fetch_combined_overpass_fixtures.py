#!/usr/bin/env python3
"""
Regenerate reverse geocoding test fixtures from the live Overpass API and is_in area server.

Fetches for each default test coordinate:
  1. Lakes-and-cities Overpass query -> fixtures/combined_overpass/{lat}_{lon}.json
  2. Areas server GET /query?lat=&lon= -> fixtures/areas_server/{lat}_{lon}.json

Usage:

  cd src/tests && python fetch_combined_overpass_fixtures.py --url https://overpass-api.de/api/interpreter --areas-url http://localhost:5001
  python fetch_combined_overpass_fixtures.py --url https://... --areas-url http://... --dry-run

Options:
  --url URL           Overpass API URL (required)
  --areas-url URL     is_in area server base URL (required)
  --no-verify-ssl     Skip SSL certificate verification
  --dry-run           Only list coordinates that would be fetched
  --jobs N            Number of concurrent requests (default 4)
"""
import argparse
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Optional, Tuple

import requests
import urllib3

tests_dir = os.path.dirname(os.path.abspath(__file__))

# Must match geo_lib.reverse_geocoding.combined_overpass (build_lakes_and_cities_query)
_LAKE_RADIUS_M = int(1.0 * 1609.34)   # LAKE_PROXIMITY_MILES default 1.0
_CITY_RADIUS_M = int(5.0 * 1609.34)   # CITY_PROXIMITY_MILES default 5.0
_OVERPASS_TIMEOUT = 60                 # same as [timeout:60] in combined_overpass
_COORDINATE_PRECISION = 3


def _build_lakes_and_cities_query(latitude: float, longitude: float) -> str:
    """Build lakes-and-cities Overpass query (identical to combined_overpass.build_lakes_and_cities_query)."""
    return f"""[out:json][timeout:{_OVERPASS_TIMEOUT}];
(
  way["natural"="water"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  relation["natural"="water"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  way["water"="lake"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  relation["water"="lake"]["name"](around:{_LAKE_RADIUS_M},{latitude},{longitude});
  node["place"~"town|city|village"](around:{_CITY_RADIUS_M},{latitude},{longitude});
)->.all;
.all out tags geom center bb;
"""


def _round_coordinate(latitude: float, longitude: float) -> tuple:
    return (round(latitude, _COORDINATE_PRECISION), round(longitude, _COORDINATE_PRECISION))


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
    (43.911, -124.125),
    (43.946, -126.139),
    (44.604, -110.476),
]


def _fetch_areas_from_server(lat: float, lon: float, base_url: str, timeout: int, verify_ssl: bool) -> Tuple[Optional[dict], Optional[str]]:
    """GET areas server /query?lat=&lon=; return (response_dict, error_message)."""
    url = base_url.rstrip('/') + '/query'
    try:
        r = requests.get(url, params={'lat': lat, 'lon': lon}, timeout=timeout, verify=verify_ssl)
        r.raise_for_status()
        data = r.json()
        if 'admin_hierarchy' in data and 'protected_areas' in data:
            return (data, None)
        return (None, 'response missing admin_hierarchy or protected_areas')
    except requests.RequestException as e:
        return (None, str(e))
    except json.JSONDecodeError as e:
        return (None, f'invalid JSON: {e}')


def _fetch_one(
    name: str,
    lat: float,
    lon: float,
    overpass_url: str,
    areas_url: str,
    timeout: int,
    verify_ssl: bool,
    overpass_dir: str,
    areas_dir: str,
) -> Tuple[str, bool, Optional[str]]:
    """
    Fetch Overpass lakes+cities and areas fixtures for one coordinate.
    Returns (name, success, error_message). Success is True only if both writes succeed.
    """
    # 1. Lakes+cities Overpass
    query = _build_lakes_and_cities_query(lat, lon)
    try:
        r = requests.post(
            overpass_url,
            data=query,
            timeout=timeout,
            headers={'Content-Type': 'text/plain; charset=utf-8'},
            verify=verify_ssl,
        )
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        return (name, False, f'Overpass request failed: {e}')
    except json.JSONDecodeError as e:
        return (name, False, f'Overpass invalid JSON: {e}')

    remark = data.get('remark') or ''
    if 'runtime error' in remark or 'timed out' in remark:
        return (name, False, f'Overpass error: {remark[:80]!r}')

    overpass_path = os.path.join(overpass_dir, name)
    with open(overpass_path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    # 2. Areas response (areas_url is required)
    areas_data, areas_err = _fetch_areas_from_server(lat, lon, areas_url, timeout, verify_ssl)
    if areas_err:
        return (name, False, f'areas: {areas_err}')
    areas_path = os.path.join(areas_dir, name)
    with open(areas_path, 'w', encoding='utf-8') as f:
        json.dump(areas_data, f, indent=2, ensure_ascii=False)

    return (name, True, None)


def main():
    parser = argparse.ArgumentParser(
        description='Fetch lakes+cities Overpass and areas server fixtures for reverse geocoding tests.',
    )
    parser.add_argument('--url', type=str, default=None, help='Overpass API URL (required)')
    parser.add_argument('--areas-url', type=str, default=None, help='is_in area server base URL (required)')
    parser.add_argument('--no-verify-ssl', action='store_true', help='Skip SSL certificate verification')
    parser.add_argument('--dry-run', action='store_true', help='Only list coordinates that would be fetched')
    parser.add_argument('--jobs', type=int, default=4, help='Number of concurrent requests (default 4)')
    args = parser.parse_args()

    if not args.url:
        print('Error: --url required', file=sys.stderr)
        sys.exit(1)
    if not args.areas_url or not args.areas_url.strip():
        print('Error: --areas-url required', file=sys.stderr)
        sys.exit(1)
    overpass_url = args.url
    areas_url = args.areas_url.strip()

    overpass_dir = os.path.join(tests_dir, 'fixtures', 'combined_overpass')
    areas_dir = os.path.join(tests_dir, 'fixtures', 'areas_server')

    seen = set()
    coords = []
    for lat, lon in DEFAULT_FIXTURE_COORDINATES:
        lat_r, lon_r = _round_coordinate(lat, lon)
        name = f'{lat_r}_{lon_r}.json'
        if name in seen:
            continue
        seen.add(name)
        coords.append((name, lat_r, lon_r))
    for d in (overpass_dir, areas_dir):
        if not os.path.isdir(d):
            os.makedirs(d, exist_ok=True)
            print(f'Created {d}')

    print(f'Found {len(coords)} fixture(s). Overpass -> {overpass_dir}, areas -> {areas_dir}')
    if args.dry_run:
        for name, lat, lon in coords:
            print(f'  {name}  -> ({lat}, {lon})')
        return

    verify_ssl = not args.no_verify_ssl
    timeout = 150
    jobs = max(1, args.jobs)
    os.makedirs(overpass_dir, exist_ok=True)
    os.makedirs(areas_dir, exist_ok=True)
    if not verify_ssl:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

    print_lock = threading.Lock()
    ok = 0
    err = 0
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = {
            executor.submit(
                _fetch_one,
                name, lat, lon,
                overpass_url, areas_url,
                timeout, verify_ssl,
                overpass_dir, areas_dir,
            ): (name, lat, lon)
            for name, lat, lon in coords
        }
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
