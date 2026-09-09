"""Major river/canal lookup from waterways.major_waterways (osm-lump-ways output)."""
from typing import Any, Dict, Optional

from .lookup_common import get_table_stats

WATERWAYS_SCHEMA = "waterways"
TABLE_NAME = "major_waterways"

# Default: 300 feet in miles (used when waterway-radius-miles not provided)
DEFAULT_WATERWAY_RADIUS_MILES = 300 / 5280.0

# Cached result of table_exists to avoid a catalog round-trip on every query
_waterway_table_exists: Optional[bool] = None


def get_waterway_stats(conn: Any) -> Optional[Dict[str, Any]]:
    """Return stats for major_waterways if table exists; else None."""
    if not table_exists(conn):
        return None
    return get_table_stats(conn, WATERWAYS_SCHEMA, TABLE_NAME, include_created=False)


def table_exists(conn: Any) -> bool:
    """Return True if waterways.major_waterways exists. Result is cached per process."""
    global _waterway_table_exists
    if _waterway_table_exists is not None:
        return _waterway_table_exists
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s)", (f"{WATERWAYS_SCHEMA}.{TABLE_NAME}",))
        row = cur.fetchone()
        _waterway_table_exists = row is not None and row[0] is not None
    return _waterway_table_exists


def sql_fragment(*, batch: bool) -> str:
    """UNION ALL branch for nearest major waterway. Radius via %(waterway_radius_m)s."""
    if batch:
        return f"""
        SELECT pt.point_idx, 'waterway' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', w.tag_group_value,
                     'distance_m', (public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)))::int) AS payload
            FROM {WATERWAYS_SCHEMA}.{TABLE_NAME} w
            WHERE public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %(waterway_radius_m)s)
            ORDER BY public.ST_Distance(public.geography(w.geom), public.geography(pt.geom))
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """
    return f"""
        (SELECT 'waterway' AS layer, jsonb_build_object('name', w.tag_group_value,
                 'distance_m', (public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)))::int) AS payload
         FROM {WATERWAYS_SCHEMA}.{TABLE_NAME} w, pt
         WHERE public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %(waterway_radius_m)s)
         ORDER BY public.ST_Distance(public.geography(w.geom), public.geography(pt.geom))
         LIMIT 1)
        """
