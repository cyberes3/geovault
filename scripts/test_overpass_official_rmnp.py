#!/usr/bin/env python3
"""
Test Overpass API for RMNP (40.34, -105.68): bbox vs is_in.

Run:
  python scripts/test_overpass_official_rmnp.py
  OVERPASS_URL=https://172.0.2.121/api/interpreter OVERPASS_INSECURE=1 python scripts/test_overpass_official_rmnp.py
"""
import json
import os
import ssl
import time
import urllib.request

OFFICIAL_API = "https://overpass-api.de/api/interpreter"
LAT, LON = 40.34, -105.68
HALF = 0.05
SOUTH, NORTH = LAT - HALF, LAT + HALF
WEST, EAST = LON - HALF, LON + HALF
LAKE_M = int(1.0 * 1609.34)
CITY_M = int(5.0 * 1609.34)


def run_query(query: str, label: str, base_url: str, insecure: bool = False) -> dict:
    req = urllib.request.Request(
        base_url,
        data=query.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST",
    )
    ctx = None
    if insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(req, timeout=30, context=ctx) as r:
        data = json.loads(r.read().decode())
    elements = data.get("elements", [])
    types = {}
    for el in elements:
        t = el.get("type", "?")
        types[t] = types.get(t, 0) + 1
    protected_names = []
    for el in elements:
        tags = el.get("tags", {})
        if tags.get("boundary") in ("protected_area", "national_park") or tags.get("leisure") == "nature_reserve":
            protected_names.append(tags.get("name", "(no name)"))
    print(f"\n=== {label} ===")
    print(f"Element types: {types}")
    print(f"Protected-area-like names: {protected_names}")
    return data


# 1) Current combined (bbox) query
bbox_query = f"""[out:json][timeout:25];
(
  relation["boundary"="administrative"]["admin_level"~"2|4|6|8"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["boundary"="protected_area"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["leisure"="nature_reserve"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["boundary"="national_park"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["leisure"="park"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["landuse"="recreation_ground"]({SOUTH},{WEST},{NORTH},{EAST});
  way["boundary"="protected_area"]({SOUTH},{WEST},{NORTH},{EAST});
  way["leisure"="park"]({SOUTH},{WEST},{NORTH},{EAST});
  way["landuse"="recreation_ground"]({SOUTH},{WEST},{NORTH},{EAST});
  way["natural"="water"]["name"](around:{LAKE_M},{LAT},{LON});
  relation["natural"="water"]["name"](around:{LAKE_M},{LAT},{LON});
  way["water"="lake"]["name"](around:{LAKE_M},{LAT},{LON});
  relation["water"="lake"]["name"](around:{LAKE_M},{LAT},{LON});
  node["place"~"town|city|village"](around:{CITY_M},{LAT},{LON});
)->.all;
.all out tags geom center bb;
"""
# 2) is_in protected only (areas)
is_in_query = f"""[out:json][timeout:25];
is_in({LAT},{LON})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
  area.a["leisure"="park"];
  area.a["landuse"="recreation_ground"];
);
out tags;
"""

for server_label, base_url, insecure in [
    ("Official (overpass-api.de)", OFFICIAL_API, False),
    ("Custom (OVERPASS_URL)", os.environ.get("OVERPASS_URL"), os.environ.get("OVERPASS_INSECURE") == "1"),
]:
    if not base_url:
        continue
    run_query(bbox_query, f"{server_label} – bbox combined (40.34, -105.68)", base_url, insecure=insecure)
    time.sleep(2)
    run_query(is_in_query, f"{server_label} – is_in protected (40.34, -105.68)", base_url, insecure=insecure)
    time.sleep(2)

print("\nDone.")
