#!/usr/bin/env python3
"""
Test Overpass API for Yellowstone (44.60384, -110.47567): bbox query and relation by id.

Run:
  python scripts/test_overpass_yellowstone.py
  OVERPASS_URL=https://your-server/api/interpreter python scripts/test_overpass_yellowstone.py
"""
import json
import os
import ssl
import sys
import urllib.request

LAT, LON = 44.604, -110.476  # rounded; test point 44.60384, -110.47567
YELLOWSTONE_RELATION_ID = 1453306
# Try multiple bbox sizes: Overpass returns relation only if a member (way node) is in bbox
HALF_SIZES = [0.25, 0.5, 1.0]
SOUTH, NORTH = None, None  # set per run
WEST, EAST = None, None


def run_query(query: str, timeout: int = 90) -> dict:
    url = os.environ.get("OVERPASS_URL", "https://overpass-api.de/api/interpreter")
    insecure = os.environ.get("OVERPASS_INSECURE") == "1"
    req = urllib.request.Request(
        url,
        data=query.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST",
    )
    ctx = None
    if insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode())


def main():
    # 1) Get Yellowstone relation by id to see its bounds
    by_id_query = f"""[out:json][timeout:30];
relation(id:{YELLOWSTONE_RELATION_ID});
out tags bb;
"""
    print("=== relation(id:1453306) ===")
    try:
        data = run_query(by_id_query, timeout=35)
        elements = data.get("elements", [])
        for el in elements:
            tags = el.get("tags", {})
            bounds = el.get("bounds", {})
            print(f"  name={tags.get('name')} bounds: {bounds}")
    except Exception as e:
        print(f"  Error: {e}")
    print()

    # 2) Try several bbox sizes: Overpass returns relation only if a member way has a node in bbox
    for half in HALF_SIZES:
        SOUTH, NORTH = LAT - half, LAT + half
        WEST, EAST = LON - half, LON + half
        print(f"=== bbox half={half} (south={SOUTH} north={NORTH} west={WEST} east={EAST}) ===")
        bbox_protected_only = f"""[out:json][timeout:60];
(
  relation["boundary"="protected_area"]({SOUTH},{WEST},{NORTH},{EAST});
  relation["boundary"="national_park"]({SOUTH},{WEST},{NORTH},{EAST});
);
out tags bb;
"""
        try:
            data = run_query(bbox_protected_only, timeout=70)
            elements = data.get("elements", [])
            found = any(el.get("id") == YELLOWSTONE_RELATION_ID for el in elements)
            print(f"  Relations: {len(elements)}  Yellowstone present: {found}")
            if found:
                print(f"  -> half={half} is sufficient; use this or larger in app.")
                break
        except Exception as e:
            print(f"  Error: {e}")
        print()

    # 3) If we found a working half, run full combined with that half and confirm
    # (keep 0.25 for normal use; we may need a fallback for known large parks)
    print("Done.")


if __name__ == "__main__":
    main()
