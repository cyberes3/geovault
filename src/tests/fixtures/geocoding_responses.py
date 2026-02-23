"""
Areas server response fixtures for reverse geocoding tests.

- Areas server responses: fixtures/areas_server/{lat}_{lon}.json
  (admin_hierarchy, protected_areas, nearby_lakes, ocean, ski_resort)
"""
import json
import os
from typing import Optional, Tuple

from geo_lib.spatial.coordinates import round_coordinate

# Test points for ski resort lookup (areas server is_in.ski_resorts table).
# (expected_name, lat, lon). Use for tests that assert ski_resort in response.
SKI_RESORT_TEST_POINTS: Tuple[Tuple[str, float, float], ...] = (
    ("Vail", 39.61259099698669, -106.35683442323163),
    ("Breckenridge", 39.48050041625039, -106.07818993106984),
    ("Jackson Hole", 43.591287434883135, -110.85327582346859),
)


def get_areas_fixture(lat: float, lon: float) -> Optional[dict]:
    """
    Load areas server response fixture for (lat, lon).
    Returns full response dict (admin_hierarchy, protected_areas, nearby_lakes, ocean, ski_resort) or None if file missing/invalid.
    """
    lat_r, lon_r = round_coordinate(lat, lon)
    filename = f"{lat_r}_{lon_r}.json"
    dir_path = os.path.join(os.path.dirname(__file__), 'areas_server')
    file_path = os.path.join(dir_path, filename)
    if not os.path.isfile(file_path):
        return None
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if isinstance(data, dict) and 'admin_hierarchy' in data and 'protected_areas' in data:
            return data
    except (json.JSONDecodeError, OSError):
        pass
    return None
