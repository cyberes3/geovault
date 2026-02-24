"""Shared helpers for is_in area server lookups."""
from typing import Any, Dict, Optional, Tuple


def _ts_str(val: Any) -> Optional[str]:
    """Format timestamp/datetime for stats (isoformat if available, else str)."""
    if val is None:
        return None
    if hasattr(val, "isoformat"):
        return val.isoformat()
    return str(val)


def get_name_from_tags(tags: Optional[Dict[str, Any]]) -> Optional[str]:
    """Return display name from OSM tags. Key order (default EN): name:en, name, official_name, int_name, alt_name."""
    if not tags:
        return None
    name = (
        tags.get("name:en")
        or tags.get("name")
        or tags.get("official_name")
        or tags.get("int_name")
        or tags.get("alt_name")
    )
    if name is None:
        return None
    s = str(name).strip()
    return s if s else None


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


def get_table_stats(
    conn: Any,
    schema: str,
    table_name: str,
    *,
    include_created: bool = True,
) -> Dict[str, Any]:
    """Return count, extent, and optionally oldest/newest created for a geometry table."""
    out: Dict[str, Any] = {"count": 0, "extent": None}
    if include_created:
        out["oldest_feature"] = None
        out["newest_feature"] = None
    with conn.cursor() as cur:
        if include_created:
            cur.execute(
                f"""
                SELECT COUNT(*),
                       public.ST_XMin(public.ST_Extent(geom)),
                       public.ST_YMin(public.ST_Extent(geom)),
                       public.ST_XMax(public.ST_Extent(geom)),
                       public.ST_YMax(public.ST_Extent(geom)),
                       MIN(created), MAX(created)
                FROM "{schema}"."{table_name}"
                """
            )
        else:
            cur.execute(
                f"""
                SELECT COUNT(*),
                       public.ST_XMin(public.ST_Extent(geom)),
                       public.ST_YMin(public.ST_Extent(geom)),
                       public.ST_XMax(public.ST_Extent(geom)),
                       public.ST_YMax(public.ST_Extent(geom))
                FROM "{schema}"."{table_name}"
                """
            )
        row = cur.fetchone()
        if row and row[0] is not None:
            out["count"] = row[0]
        if row and len(row) >= 5 and row[1] is not None:
            out["extent"] = extent_from_row((row[1], row[2], row[3], row[4]))
        if include_created and row and len(row) >= 7:
            out["oldest_feature"] = _ts_str(row[5])
            out["newest_feature"] = _ts_str(row[6])
    return out
