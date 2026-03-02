"""Admin hierarchy lookup (country, state, county, city) from admin_areas table."""
import functools
from typing import Any, Dict, List, Optional, Tuple

import pycountry

from config import SCHEMA
from .lookup_common import get_name_from_tags, get_table_stats, normalize_name_for_response

TABLE_NAME = "admin_areas"


@functools.lru_cache(maxsize=256)
def _country_code_to_name(code: Optional[str]) -> Optional[str]:
    """Return pycountry common name (c.name) for alpha_2 code, e.g. DE -> Germany."""
    if not code or not isinstance(code, str):
        return None
    code = code.strip().upper()
    if len(code) != 2:
        return None
    try:
        c = pycountry.countries.get(alpha_2=code)
        return c.name if c else None
    except (KeyError, AttributeError):
        return None


def _normalize_country_name(name: Optional[str]) -> Optional[str]:
    """Return stripped boundary name as-is (no alias mapping)."""
    if not name or not name.strip():
        return None
    return name.strip()


def _level6_is_city(tags: Dict[str, Any]) -> bool:
    """True if this admin_level=6 boundary is tagged as city (e.g. consolidated city-county).
    Nominatim uses extratags.place; OSM uses border_type=city or place=city."""
    if (tags.get("place") or "").strip() == "city":
        return True
    bt = (tags.get("border_type") or "").strip()
    return any(p.strip() == "city" for p in bt.split(";"))


def build_admin_hierarchy(rows: List[Tuple[Any, ...]]) -> Dict[str, Optional[str]]:
    """Build admin_hierarchy dict from query rows (osm_id, admin_level, name, tags)."""
    result: Dict[str, Optional[str]] = {
        "country": None,
        "state": None,
        "county": None,
        "city": None,
    }
    for row in rows:
        if len(row) < 4:
            continue
        _osm_id, admin_level, name, tags = row[0], row[1], row[2], row[3]
        tags = tags or {}
        name = name or get_name_from_tags(tags)
        name = str(name).strip() if name else ""
        # Skip row if no name, except level-2 when we can set country from code
        code = tags.get("ISO3166-1-alpha-2") or tags.get("ISO3166-1")
        if isinstance(code, str) and len(code) >= 2:
            code = code[:2].upper()
        else:
            code = None
        if not name and not (admin_level == 2 and code):
            continue
        if admin_level == 2:
            if result["country"] is None:
                # Prefer boundary-derived name, then country code (Nominatim: country_name from largest boundary).
                raw = _normalize_country_name(name) or _country_code_to_name(code) or (name or None)
                result["country"] = (normalize_name_for_response(raw) or None) if raw else None
        elif admin_level == 4:
            if result["state"] is None:
                result["state"] = (normalize_name_for_response(name) or None) if name else None
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
        elif admin_level == 6:
            if result["county"] is None:
                result["county"] = (normalize_name_for_response(name) or None) if name else None
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
            if result["state"] is None and tags.get("is_in:state"):
                result["state"] = (normalize_name_for_response(str(tags.get("is_in:state", "")).strip()) or None)
            # Level-6 as city when boundary is tagged (Nominatim uses extratags.place; OSM uses border_type).
            if result["city"] is None and _level6_is_city(tags):
                result["city"] = (normalize_name_for_response(name) or None) if name else None
                result["_city_admin_level"] = 6
        elif admin_level == 8:
            # Level-8 (more specific) overrides level-6 for city; same-level tie-break is first row.
            if result["city"] is None or result.get("_city_admin_level") == 6:
                result["city"] = (normalize_name_for_response(name) or None) if name else None
                result["_city_admin_level"] = 8
    # Remove sentinel used for level-8 vs level-6 override
    result.pop("_city_admin_level", None)
    return result


def run_admin_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT a.osm_id, a.admin_level, a.name, a.tags
            FROM {SCHEMA}.{TABLE_NAME} a
            WHERE public.ST_Contains(a.geom, public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
            ORDER BY a.admin_level ASC,
                     public.ST_Distance(
                         public.ST_PointOnSurface(a.geom),
                         public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326)
                     ),
                     a.osm_id
            """,
            (lon, lat, lon, lat),
        )
        return cur.fetchall()


def run_admin_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
) -> List[Tuple[int, Any, Any, Any, Any]]:
    """Returns (point_idx, osm_id, admin_level, name, tags). At most one row per (point_idx, admin_level)."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            ),
            pt AS (
                SELECT point_idx,
                       public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326) AS geom
                FROM p
            ),
            joined AS (
                    SELECT pt.point_idx, a.osm_id, a.admin_level, a.name, a.tags,
                           public.ST_Distance(
                               public.ST_PointOnSurface(a.geom),
                               pt.geom
                           ) AS dist
                FROM pt
                JOIN {SCHEMA}.{TABLE_NAME} a ON public.ST_Contains(a.geom, pt.geom)
            )
            SELECT DISTINCT ON (point_idx, admin_level) point_idx, osm_id, admin_level, name, tags
            FROM joined
            ORDER BY point_idx, admin_level, dist ASC, osm_id
            """,
            (indices, lons, lats),
        )
        return cur.fetchall()


def get_admin_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for admin_areas: count, extent, by_admin_level, oldest_feature, newest_feature."""
    out = get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
    out["by_admin_level"] = {}
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT admin_level, COUNT(*)
            FROM {SCHEMA}.{TABLE_NAME}
            GROUP BY admin_level
            ORDER BY admin_level
            """,
        )
        for row in cur.fetchall():
            if row and len(row) >= 2:
                out["by_admin_level"][int(row[0])] = row[1]
    return out
