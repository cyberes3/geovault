"""Admin hierarchy lookup (country, state, county, city) from admin_areas table."""
import functools
from typing import Any, Dict, List, Optional, Tuple

import pycountry

from config import SCHEMA
from .lookup_common import extent_from_row, get_name_from_tags

TABLE_NAME = "admin_areas"

_COUNTRY_NAME_ALIASES: Dict[str, str] = {
    "United States": "United States of America",
}


@functools.lru_cache(maxsize=256)
def _country_code_to_name(code: Optional[str]) -> Optional[str]:
    if not code or not isinstance(code, str):
        return None
    code = code.strip().upper()
    if len(code) != 2:
        return None
    try:
        c = pycountry.countries.get(alpha_2=code)
        if not c:
            return None
        name = getattr(c, "official_name", None) or c.name
        return _COUNTRY_NAME_ALIASES.get(name, name) if name else None
    except (KeyError, AttributeError):
        return None


def _normalize_country_name(name: Optional[str]) -> Optional[str]:
    if not name or not name.strip():
        return None
    return _COUNTRY_NAME_ALIASES.get(name.strip(), name.strip())


def _level6_is_city(tags: Dict[str, Any]) -> bool:
    """True if this admin_level=6 boundary is tagged as city (e.g. consolidated city-county).
    Nominatim uses extratags.place; OSM uses border_type=city or border_type=county;city."""
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
        if not name:
            continue
        name = str(name).strip()
        if admin_level == 2:
            # Prefer canonical name from country code (Nominatim uses country_name table).
            code = tags.get("ISO3166-1-alpha-2") or tags.get("ISO3166-1")
            if isinstance(code, str) and len(code) >= 2:
                code = code[:2].upper()
            result["country"] = (
                _country_code_to_name(code)
                or _normalize_country_name(name)
                or name
            )
        elif admin_level == 4:
            result["state"] = name
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
        elif admin_level == 6:
            result["county"] = name
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
            if result["state"] is None and tags.get("is_in:state"):
                result["state"] = str(tags.get("is_in:state", "")).strip()
            # Level-6 as city when boundary is tagged (Nominatim uses extratags.place; OSM uses border_type).
            if result["city"] is None and _level6_is_city(tags):
                result["city"] = name
        elif admin_level == 8:
            result["city"] = name
    return result


def run_admin_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, admin_level, name, tags
            FROM {SCHEMA}.{TABLE_NAME}
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
            ORDER BY admin_level ASC
            """,
            (lon, lat),
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
            joined AS (
                SELECT p.point_idx, a.osm_id, a.admin_level, a.name, a.tags
                FROM p
                JOIN {SCHEMA}.{TABLE_NAME} a
                    ON public.ST_Contains(a.geom, public.ST_SetSRID(public.ST_GeomFromText(('POINT(' || p.lon::text || ' ' || p.lat::text || ')')::text), 4326))
            )
            SELECT DISTINCT ON (point_idx, admin_level) point_idx, osm_id, admin_level, name, tags
            FROM joined
            ORDER BY point_idx, admin_level, osm_id
            """,
            (indices, lons, lats),
        )
        return cur.fetchall()


def _ts_str(val: Any) -> Optional[str]:
    if val is None:
        return None
    if hasattr(val, "isoformat"):
        return val.isoformat()
    return str(val)


def get_admin_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for admin_areas: count, extent, by_admin_level, oldest_feature, newest_feature."""
    out: Dict[str, Any] = {
        "count": 0,
        "extent": None,
        "by_admin_level": {},
        "oldest_feature": None,
        "newest_feature": None,
    }
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT COUNT(*),
                   public.ST_XMin(public.ST_Extent(geom)),
                   public.ST_YMin(public.ST_Extent(geom)),
                   public.ST_XMax(public.ST_Extent(geom)),
                   public.ST_YMax(public.ST_Extent(geom)),
                   MIN(created), MAX(created)
            FROM {SCHEMA}.{TABLE_NAME}
            """
        )
        row = cur.fetchone()
        if row and row[0] is not None:
            out["count"] = row[0]
        if row and row[1] is not None:
            out["extent"] = extent_from_row((row[1], row[2], row[3], row[4]))
        if row and len(row) >= 7:
            out["oldest_feature"] = _ts_str(row[5])
            out["newest_feature"] = _ts_str(row[6])

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
