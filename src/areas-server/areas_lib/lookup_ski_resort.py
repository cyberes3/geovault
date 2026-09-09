"""Ski resort name lookup from is_in.ski_resorts table (ST_Contains only)."""
from typing import Any, Dict

from config import SCHEMA

from .lookup_common import get_table_stats

TABLE_NAME = "ski_resorts"


def sql_fragment(*, batch: bool) -> str:
    """UNION ALL branch for ski resort (point in polygon)."""
    if batch:
        return f"""
        SELECT pt.point_idx, 'ski' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', s.name) AS payload
            FROM {SCHEMA}.{TABLE_NAME} s
            WHERE public.ST_Contains(s.geom, pt.geom)
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """
    return f"""
        (SELECT 'ski' AS layer, jsonb_build_object('name', s.name) AS payload
        FROM {SCHEMA}.{TABLE_NAME} s, pt
        WHERE public.ST_Contains(s.geom, pt.geom)
        LIMIT 1)
        """


def get_ski_resort_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for ski_resorts: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=False)
