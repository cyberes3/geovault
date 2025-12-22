#!/usr/bin/env python3
"""
Helper script to fetch real Overpass API responses for test fixtures.

This script helps verify which coordinates actually return empty responses
from Overpass API, and captures real responses for coordinates that have data.

Usage:
    python3 fetch_overpass_responses.py <lat> <lon> <query_type>
    
Query types: 'admin', 'protected', 'cities', 'lakes'

Example:
    python3 fetch_overpass_responses.py 40.34 -105.68 lakes
"""
import sys
import json
import requests

OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"

def build_query(lat, lon, query_type):
    """Build Overpass QL query based on query type."""
    if query_type == 'admin':
        return f"""[out:json];
is_in({lat},{lon})->.a;
(
  area.a["admin_level"="2"]["boundary"="administrative"];
  area.a["admin_level"="4"]["boundary"="administrative"];
  area.a["admin_level"="6"]["boundary"="administrative"];
  area.a["admin_level"="8"]["boundary"="administrative"];
);
out tags;"""
    
    elif query_type == 'protected':
        return f"""[out:json];
is_in({lat},{lon})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
  area.a["leisure"="park"];
  area.a["landuse"="recreation_ground"];
);
out tags;"""
    
    elif query_type == 'cities':
        # Default radius ~5 miles
        radius_meters = int(5.0 * 1609.34)
        return f"""[out:json];
(
  node["place"~"town|city|village"](around:{radius_meters},{lat},{lon});
);
out center;"""
    
    elif query_type == 'lakes':
        # Default radius ~1 mile
        radius_meters = int(1.0 * 1609.34)
        return f"""[out:json];
(
  way["natural"="water"]["name"](around:{radius_meters},{lat},{lon});
  relation["natural"="water"]["name"](around:{radius_meters},{lat},{lon});
  way["water"="lake"]["name"](around:{radius_meters},{lat},{lon});
  relation["water"="lake"]["name"](around:{radius_meters},{lat},{lon});
);
out tags center;"""
    
    else:
        raise ValueError(f"Unknown query type: {query_type}")

def fetch_response(lat, lon, query_type):
    """Fetch Overpass API response."""
    query = build_query(lat, lon, query_type)
    
    print(f"Fetching {query_type} response for ({lat}, {lon})...")
    print(f"Query:\n{query}\n")
    
    try:
        response = requests.post(
            OVERPASS_API_URL,
            data=query,
            timeout=45,
            headers={'Content-Type': 'text/plain; charset=utf-8'}
        )
        
        if response.status_code != 200:
            print(f"Error: Status code {response.status_code}")
            print(response.text)
            return None
        
        data = response.json()
        return data
    
    except Exception as e:
        print(f"Error fetching response: {e}")
        return None

def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(1)
    
    try:
        lat = float(sys.argv[1])
        lon = float(sys.argv[2])
        query_type = sys.argv[3]
    except ValueError:
        print("Error: Invalid arguments")
        print(__doc__)
        sys.exit(1)
    
    response = fetch_response(lat, lon, query_type)
    
    if response is None:
        print("Failed to fetch response")
        sys.exit(1)
    
    # Round coordinates to 4 decimal places for consistency
    lat_rounded = round(lat, 4)
    lon_rounded = round(lon, 4)
    
    print(f"\nResponse for ({lat_rounded}, {lon_rounded}, '{query_type}'):")
    print("=" * 80)
    print(json.dumps(response, indent=2))
    print("=" * 80)
    
    element_count = len(response.get('elements', []))
    if element_count == 0:
        print(f"\n✓ Verified: This response is EMPTY (no elements)")
        print(f"  You can use EMPTY_RESPONSE for this coordinate/query_type")
    else:
        print(f"\n✓ Found {element_count} element(s)")
        print(f"  Add this as a real response constant in geocoding_responses.py")
        print(f"  Key: ({lat_rounded}, {lon_rounded}, '{query_type}')")

if __name__ == '__main__':
    main()

