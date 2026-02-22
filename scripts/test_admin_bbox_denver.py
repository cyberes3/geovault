#!/usr/bin/env python3
"""
Standalone test script for the bbox-based country/state Overpass query (Denver point).

Avoids is_in() which can OOM on constrained servers by querying only
relation["boundary"="administrative"]["admin_level"~"2|4"] in a small bbox,
then filtering by point-in-polygon in Python.

Usage (from repo root):
  python scripts/test_admin_bbox_denver.py

Requires: requests, pycountry
  pip install requests pycountry
"""
import json
import sys
import urllib3

import pycountry
import requests

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Hardcoded for local Overpass testing
OVERPASS_URL = "https://172.0.2.121/api/interpreter"
# Denver, CO
DENVER_LAT = 39.7392
DENVER_LON = -104.9903
# Single bbox for admin_level 2|4. Keep small so we don't pull in every state in the region;
# country often won't be in the result (border nodes far from point). Country then from state tags (pycountry).
BBOX_HALF_DEGREES = 0.05  # ~5 km


def get_name(tags: dict) -> str | None:
    """Prefer name:en, else name."""
    return tags.get("name:en") or tags.get("name")


def build_query(lat: float, lon: float, half_deg: float, admin_levels: str = "2|4") -> str:
    south = lat - half_deg
    north = lat + half_deg
    west = lon - half_deg
    east = lon + half_deg
    return f"""[out:json][timeout:15];
relation["boundary"="administrative"]["admin_level"~"{admin_levels}"]({south},{west},{north},{east});
out tags geom bb;
"""


def _run_query(
    lat: float, lon: float, half_deg: float, admin_levels: str = "2|4"
) -> tuple[list, list[str]]:
    """Run Overpass query; return (elements, errors)."""
    errors = []
    query = build_query(lat, lon, half_deg, admin_levels)
    print("Overpass query:")
    print(query)
    try:
        resp = requests.post(
            OVERPASS_URL,
            data=query,
            timeout=30,
            headers={"Content-Type": "text/plain; charset=utf-8"},
            verify=False,
        )
    except Exception as e:
        errors.append(str(e))
        return [], errors
    if resp.status_code != 200:
        errors.append(f"HTTP {resp.status_code}: {resp.text[:500]}")
        return [], errors
    try:
        data = resp.json()
    except json.JSONDecodeError as e:
        errors.append(f"Invalid JSON: {e}")
        return [], errors
    return data.get("elements", []), errors


def _extract_rings(geometry: list[dict]) -> list[list[tuple[float, float]]]:
    """Convert Overpass geometry to list of rings (lon, lat). One ring per closed line."""
    if not geometry or len(geometry) < 3:
        return []
    pts = [(float(p["lon"]), float(p["lat"])) for p in geometry]
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    return [pts]


def point_in_multipolygon(lat: float, lon: float, geometry: list[dict]) -> bool:
    """Point (lat, lon) inside polygon. Handles single ring from Overpass 'geometry'."""
    rings = _extract_rings(geometry)
    for ring in rings:
        if len(ring) < 3:
            continue
        n = len(ring) - 1
        inside = False
        j = n - 1
        for i in range(n):
            yi, yj = ring[i][1], ring[j][1]
            xi, xj = ring[i][0], ring[j][0]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
        if inside:
            return True
    return False


def _country_from_state_tags(tags: dict) -> str | None:
    """Country name from state relation tags: explicit name or ISO3166-2 via pycountry."""
    name = tags.get("is_in:country") or tags.get("addr:country") or tags.get("country")
    if name:
        return name
    iso3166_2 = (tags.get("ISO3166-2") or "").strip()
    if "-" not in iso3166_2:
        return None
    try:
        sub = pycountry.subdivisions.get(code=iso3166_2)
        if sub:
            c = pycountry.countries.get(alpha_2=sub.country_code)
            return c.name if c else None
    except (KeyError, AttributeError):
        pass
    return None


def _point_in_bounds(lat: float, lon: float, bounds: dict) -> bool:
    """True if (lat, lon) is inside bounds (minlat/maxlat/minlon/maxlon or south/west/north/east)."""
    if not bounds:
        return False
    minlat = bounds.get("minlat") or bounds.get("south")
    maxlat = bounds.get("maxlat") or bounds.get("north")
    minlon = bounds.get("minlon") or bounds.get("west")
    maxlon = bounds.get("maxlon") or bounds.get("east")
    if None in (minlat, maxlat, minlon, maxlon):
        return False
    return float(minlat) <= lat <= float(maxlat) and float(minlon) <= lon <= float(maxlon)


def _element_contains_point(el: dict, lat: float, lon: float) -> bool:
    """True if (lat, lon) is inside the element (geometry or bounds)."""
    geom = el.get("geometry")
    if geom:
        return point_in_multipolygon(lat, lon, geom)
    return _point_in_bounds(lat, lon, el.get("bounds") or {})


def _parse_admin_elements(
    elements: list, lat: float, lon: float
) -> tuple[str | None, str | None, dict | None]:
    """Pick country (2) and state (4) whose geometry/bounds contain (lat, lon). Returns (country, state, state_element)."""
    country = None
    state = None
    state_el = None
    for el in elements:
        if el.get("type") not in ("relation", "area"):
            continue
        tags = el.get("tags", {}) or {}
        if tags.get("boundary") != "administrative":
            continue
        admin_level = tags.get("admin_level")
        name = get_name(tags)
        if not name or admin_level not in ("2", "4"):
            continue
        if not _element_contains_point(el, lat, lon):
            continue
        if admin_level == "2":
            country = name
        elif admin_level == "4":
            state = name
            state_el = el
        if country and state:
            break
    return country, state, state_el


def fetch_country_state(
    lat: float, lon: float, debug: bool = False
) -> tuple[str | None, str | None, list[str]]:
    """(country_name, state_name, errors). Single bbox query; country from state tags if missing."""
    elements, errors = _run_query(lat, lon, BBOX_HALF_DEGREES, "2|4")
    if errors:
        return None, None, errors

    country, state, state_el = _parse_admin_elements(elements, lat, lon)
    if debug and elements:
        print(f"DEBUG: {len(elements)} elements -> country={country} state={state}")

    if country is None and state_el:
        country = _country_from_state_tags(state_el.get("tags", {}))

    return country, state, []


def main() -> None:
    print(f"Querying Overpass at {OVERPASS_URL} for Denver ({DENVER_LAT}, {DENVER_LON})")
    print("(bbox-based admin_level 2|4 only, no is_in)\n")
    debug = "--debug" in sys.argv
    country, state, errors = fetch_country_state(DENVER_LAT, DENVER_LON, debug=debug)
    if errors:
        print("Errors:", errors)
    print(f"Country: {country}")
    print(f"State:   {state}")


if __name__ == "__main__":
    main()
