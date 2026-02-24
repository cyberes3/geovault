#!/usr/bin/env python3
"""
Regenerate reverse geocoding test fixtures from the is_in area server.

Fetches for each default test coordinate:
  Areas server GET /query?lat=&lon= -> fixtures/areas_server/{lat}_{lon}.json

Usage:

  cd src/tests && python fetch_areas_fixtures.py http://localhost:5001
  python fetch_areas_fixtures.py http://... --dry-run

Options:
  URL                   Area server base URL (required, positional)
  --no-verify-ssl       Skip SSL certificate verification
  --dry-run             Only list coordinates that would be fetched
  --jobs N              Number of concurrent requests (default 4)
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
_COORDINATE_PRECISION = 3


def _round_coordinate(latitude: float, longitude: float) -> tuple:
    return (round(latitude, _COORDINATE_PRECISION), round(longitude, _COORDINATE_PRECISION))


DEFAULT_FIXTURE_COORDINATES = [
    (36.156, -86.925),
    (37.775, -122.419),
    (39.0, -105.0),
    (39.07, -108.73),
    (39.22337887866515, -105.94799963185382),  # Park County, CO (near Fairplay)
    (39.42, -105.65),
    (39.563, -105.15),
    (39.723, -104.958),
    (39.746, -104.844),
    (39.48050041625039, -106.07818993106984),   # Breckenridge (ski resort)
    (39.61259099698669, -106.35683442323163),   # Vail (ski resort)
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
    (43.591287434883135, -110.85327582346859),   # Jackson Hole (ski resort)
    (43.911, -124.125),
    (43.946, -126.139),
    (43.8, -69.0),       # Gulf of Maine / North Atlantic (regional + main ocean)
    (41.41, -134.299),   # Open North Pacific
    (43.65, -70.25),     # Maine coast shore (point on/near shore tagged ocean)
    (44.604, -110.476),
    (45.84810, -123.96116),  # Oregon coast: state park + ocean (~300 ft from shore)
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
    areas_url: str,
    timeout: int,
    verify_ssl: bool,
    areas_dir: str,
) -> Tuple[str, bool, Optional[str]]:
    """
    Fetch areas fixture for one coordinate.
    Returns (name, success, error_message).
    """
    areas_data, areas_err = _fetch_areas_from_server(lat, lon, areas_url, timeout, verify_ssl)
    if areas_err:
        return (name, False, areas_err)
    areas_path = os.path.join(areas_dir, name)
    with open(areas_path, 'w', encoding='utf-8') as f:
        json.dump(areas_data, f, indent=2, ensure_ascii=False)
    return (name, True, None)


def main():
    parser = argparse.ArgumentParser(
        description='Fetch areas server fixtures for reverse geocoding tests.',
    )
    parser.add_argument('url', type=str, help='Area server base URL')
    parser.add_argument('--no-verify-ssl', action='store_true', help='Skip SSL certificate verification')
    parser.add_argument('--dry-run', action='store_true', help='Only list coordinates that would be fetched')
    parser.add_argument('--jobs', type=int, default=4, help='Number of concurrent requests (default 4)')
    args = parser.parse_args()

    areas_url = args.url.strip()
    if not areas_url:
        print('Error: URL required', file=sys.stderr)
        sys.exit(1)

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
    if not os.path.isdir(areas_dir):
        os.makedirs(areas_dir, exist_ok=True)
        print(f'Created {areas_dir}')

    print(f'Found {len(coords)} fixture(s). Areas -> {areas_dir}')
    if args.dry_run:
        for name, lat, lon in coords:
            print(f'  {name}  -> ({lat}, {lon})')
        return

    verify_ssl = not args.no_verify_ssl
    timeout = 150
    jobs = max(1, args.jobs)
    os.makedirs(areas_dir, exist_ok=True)
    if not verify_ssl:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

    # Clear areas server cache so fixtures reflect current DB state
    cache_clear_url = areas_url.rstrip('/') + '/cache-clear'
    try:
        r = requests.get(cache_clear_url, timeout=30, verify=verify_ssl)
        r.raise_for_status()
        data = r.json()
        cleared = data.get('cleared', 0)
        print(f'Cleared areas server cache ({cleared} entries).', file=sys.stderr)
    except requests.RequestException as e:
        print(f'Warning: could not clear areas server cache: {e}', file=sys.stderr)
    except (json.JSONDecodeError, KeyError):
        pass

    print_lock = threading.Lock()
    ok = 0
    err = 0
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = {
            executor.submit(
                _fetch_one,
                name, lat, lon,
                areas_url,
                timeout, verify_ssl,
                areas_dir,
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
