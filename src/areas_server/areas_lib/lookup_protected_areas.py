"""Protected areas lookup (parks, nature reserves, etc.) from protected_areas table."""
from typing import Any, Dict, List, Tuple

from config import SCHEMA
from .lookup_common import extent_from_row, get_name_from_tags

TABLE_NAME = "protected_areas"

# Max rows per point in batch to avoid unbounded result sets in dense areas
BATCH_LIMIT_PER_POINT = 100


def build_protected_list(rows: List[Tuple[Any, ...]]) -> List[Dict[str, str]]:
    """Build protected_areas list from query rows (osm_id, name, tags)."""
    out: List[Dict[str, str]] = []
    for row in rows:
        if len(row) < 3:
            continue
        _osm_id, name, tags = row[0], row[1], row[2]
        tags = tags or {}
        name = name or get_name_from_tags(tags)
        if not name:
            continue
        out.append({
            "name": str(name).strip(),
            "protection_title": str(tags.get("protection_title") or ""),
            "protect_class": str(tags.get("protect_class") or ""),
            "designation": str(tags.get("designation") or ""),
            "operator": str(tags.get("operator") or ""),
            "leisure": str(tags.get("leisure") or ""),
            "landuse": str(tags.get("landuse") or ""),
            "boundary": str(tags.get("boundary") or ""),
        })
    return out


def run_protected_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    point_wkt = f"POINT({lon} {lat})"
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, name, tags
            FROM {SCHEMA}.{TABLE_NAME}
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
            LIMIT 100
            """,
            (point_wkt,),
        )
        return cur.fetchall()


def run_protected_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
) -> List[Tuple[int, Any, Any, Any]]:
    """Returns (point_idx, osm_id, name, tags). Limited per point for scale."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            ),
            ranked AS (
                SELECT p.point_idx, a.osm_id, a.name, a.tags,
                       ROW_NUMBER() OVER (PARTITION BY p.point_idx ORDER BY a.osm_id) AS rn
                FROM p
                JOIN {SCHEMA}.{TABLE_NAME} a
                    ON public.ST_Contains(a.geom, public.ST_SetSRID(public.ST_GeomFromText(('POINT(' || p.lon::text || ' ' || p.lat::text || ')')::text), 4326))
            )
            SELECT point_idx, osm_id, name, tags
            FROM ranked
            WHERE rn <= %s
            ORDER BY point_idx, rn
            """,
            (indices, lons, lats, BATCH_LIMIT_PER_POINT),
        )
        return cur.fetchall()


def get_protected_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for protected_areas: count, extent, oldest_feature, newest_feature."""
    out: Dict[str, Any] = {
        "count": 0,
        "extent": None,
        "oldest_feature": None,
        "newest_feature": None,
    }
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.{TABLE_NAME}")
        row = cur.fetchone()
        if row and row[0] is not None:
            out["count"] = row[0]

        cur.execute(
            f"""
            SELECT public.ST_XMin(e), public.ST_YMin(e), public.ST_XMax(e), public.ST_YMax(e)
            FROM (SELECT public.ST_Extent(geom) AS e FROM {SCHEMA}.{TABLE_NAME}) _t
            """,
        )
        row = cur.fetchone()
        if row and row[0] is not None:
            out["extent"] = extent_from_row(tuple(row))

        try:
            cur.execute(f"SELECT MIN(created), MAX(created) FROM {SCHEMA}.{TABLE_NAME}")
            row = cur.fetchone()
            if row:
                out["oldest_feature"] = _ts_str(row[0])
                out["newest_feature"] = _ts_str(row[1])
        except Exception:
            pass
    return out


def _ts_str(val: Any) -> Any:
    if val is None:
        return None
    if hasattr(val, "isoformat"):
        return val.isoformat()
    return str(val)
