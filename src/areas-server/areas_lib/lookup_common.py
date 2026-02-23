"""Shared helpers for is_in area server lookups."""
from typing import Any, Dict, Optional, Tuple


def get_name_from_tags(tags: Optional[Dict[str, Any]]) -> Optional[str]:
    if not tags:
        return None
    name = tags.get("name:en") or tags.get("name") or tags.get("int_name")
    return str(name).strip() if name else None


def extent_from_row(row: Optional[Tuple[Any, ...]]) -> Optional[Dict[str, float]]:
    """Build {min_lon, min_lat, max_lon, max_lat} from (minx, miny, maxx, maxy) or None. Coords rounded to 4 decimals."""
    if not row or len(row) < 4 or any(v is None for v in row):
        return None
    minx, miny, maxx, maxy = row[0], row[1], row[2], row[3]
    return {
        "min_lon": round(float(minx), 4),
        "min_lat": round(float(miny), 4),
        "max_lon": round(float(maxx), 4),
        "max_lat": round(float(maxy), 4),
    }
