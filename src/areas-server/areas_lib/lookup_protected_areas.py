"""Protected areas lookup (parks, nature reserves, etc.) from protected_areas table."""
from typing import Any, Dict, List, Tuple

from config import SCHEMA
from .lookup_common import get_name_from_tags, get_table_stats, normalize_name_for_response
from .lookup_water import TABLE_NAME as WATER_TABLE_NAME

TABLE_NAME = "protected_areas"

# Top N most relevant protected areas per point (single and batch)
PROTECTED_LIMIT_PER_POINT = 5


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
            "name": normalize_name_for_response(name),
            "protection_title": str(tags.get("protection_title") or ""),
            "protect_class": str(tags.get("protect_class") or ""),
            "designation": str(tags.get("designation") or ""),
            "operator": str(tags.get("operator") or ""),
            "leisure": str(tags.get("leisure") or ""),
            "landuse": str(tags.get("landuse") or ""),
            "boundary": str(tags.get("boundary") or ""),
        })
    return out


def sql_fragment(*, batch: bool) -> str:
    """UNION ALL branch for protected areas (contains + on-water park touch). Cap is SQL-only."""
    payload = "jsonb_build_object('osm_id', a.osm_id, 'name', a.name, 'tags', a.tags)"
    if batch:
        return f"""
        SELECT point_idx, 'protected' AS layer, payload FROM (
            SELECT pt.point_idx, {payload} AS payload,
                   ROW_NUMBER() OVER (PARTITION BY pt.point_idx ORDER BY a.osm_id) AS rn
            FROM pt
            CROSS JOIN LATERAL (
                (SELECT a.osm_id, a.name, a.tags
                 FROM {SCHEMA}.{TABLE_NAME} a
                 WHERE a.geom && pt.geom AND public.ST_Contains(a.geom, pt.geom)
                 LIMIT {PROTECTED_LIMIT_PER_POINT})
                UNION
                (SELECT a.osm_id, a.name, a.tags
                 FROM {SCHEMA}.{WATER_TABLE_NAME} w
                 JOIN {SCHEMA}.{TABLE_NAME} a
                      ON a.geom && w.geom AND public.ST_Touches(a.geom, w.geom)
                      AND public.ST_Contains(public.ST_ConvexHull(a.geom), pt.geom)
                 WHERE w.geom && pt.geom AND public.ST_Contains(w.geom, pt.geom)
                 LIMIT {PROTECTED_LIMIT_PER_POINT})
            ) a
        ) sub WHERE rn <= {PROTECTED_LIMIT_PER_POINT}
        """
    return f"""
        (SELECT 'protected' AS layer, jsonb_build_object('osm_id', p.osm_id, 'name', p.name, 'tags', p.tags) AS payload
        FROM (
            (SELECT p.osm_id, p.name, p.tags
             FROM {SCHEMA}.{TABLE_NAME} p, pt
             WHERE p.geom && pt.geom AND public.ST_Contains(p.geom, pt.geom)
             LIMIT {PROTECTED_LIMIT_PER_POINT})
            UNION
            (SELECT a.osm_id, a.name, a.tags
             FROM pt
             JOIN {SCHEMA}.{WATER_TABLE_NAME} w
                  ON w.geom && pt.geom AND public.ST_Contains(w.geom, pt.geom)
             JOIN {SCHEMA}.{TABLE_NAME} a
                  ON a.geom && w.geom AND public.ST_Touches(a.geom, w.geom)
                  AND public.ST_Contains(public.ST_ConvexHull(a.geom), pt.geom)
             LIMIT {PROTECTED_LIMIT_PER_POINT})
        ) p
        LIMIT {PROTECTED_LIMIT_PER_POINT})
        """


def get_protected_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for protected_areas: count, extent, oldest_feature, newest_feature."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
