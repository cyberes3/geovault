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


# Canonical list: one coordinate per fixture (plan: reverse geocode test categories).
# Run fetch script to regenerate fixtures. Each coordinate has exactly one test.
DEFAULT_FIXTURE_COORDINATES = [
    (35.89684, -85.00500),
    (36.156, -86.925),
    (37.75214, -122.50269),  # San Francisco, closer to ocean
    (38.03982, -103.42472),
    (38.05677, -122.87860),
    (38.4627240263864, -107.17141673546334),
    (38.62375, -105.83993),
    (38.89178, -105.17907),
    (39.0, -105.0),
    (39.05548, -108.69338),
    (39.22337887866515, -105.94799963185382),
    (39.38965, -105.58278),
    (39.42, -105.65),
    (39.563, -105.15),
    (39.613, -106.357),
    (39.68960, -105.21190),
    (39.70073, -105.17302),
    (39.72296, -104.95785),
    (39.74498318354445, -104.95147156373426),
    (39.746, -104.844),
    (39.75832221022334, -104.92042641825462),
    (39.86543015343607, -105.12295898204981),
    (39.84041664204802, -105.10241566871551),
    (39.47371, -106.07716),
    (40.0, -105.0),
    (40.10763, -105.60469),
    (40.16692, -106.19653),
    (40.211, -105.769),
    (40.24301, -105.82766),
    (40.26762, -106.03746),
    (40.34303, -105.68435),
    (41.41, -134.299),
    (41.68187473276889, -101.36391746047425),
    (41.72390, -102.31360),
    (41.729, -102.872),
    (42.209, -71.108),
    (42.729, -102.417),
    (43.591287434883135, -110.85327582346859),
    (43.65, -70.25),
    (43.8, -69.0),
    (43.91095153533744, -124.1260278900942),
    (43.946, -126.139),
    (44.604, -110.476),
    (45.84810, -123.96116),
    (46.56804, -86.31349),
    (39.78976, -104.97147),  # river point
    (35.71677, -89.93794),   # river point
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
