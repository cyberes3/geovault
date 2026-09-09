"""Nearest place node (place=city|town|village) within radius for filling city when admin has none."""
from typing import Any, Dict

from config import SCHEMA
from .lookup_common import get_table_stats

TABLE_NAME = "place_nodes"


def sql_fragment(*, batch: bool) -> str:
    """UNION ALL branch for nearest place node. Radius via %(city_radius_m)s."""
    if batch:
        return f"""
            SELECT pt.point_idx, 'place' AS layer, sub.payload
            FROM pt
            LEFT JOIN LATERAL (
                SELECT jsonb_build_object('name', n.name) AS payload
                FROM {SCHEMA}.{TABLE_NAME} n
                WHERE public.ST_DWithin(public.geography(n.geom), public.geography(pt.geom), %(city_radius_m)s)
                ORDER BY public.ST_Distance(public.geography(n.geom), public.geography(pt.geom))
                LIMIT 1
            ) sub ON true
            WHERE sub.payload IS NOT NULL
            """
    return f"""
            (SELECT 'place' AS layer, jsonb_build_object('name', n.name) AS payload
            FROM {SCHEMA}.{TABLE_NAME} n, pt
            WHERE public.ST_DWithin(public.geography(n.geom), public.geography(pt.geom), %(city_radius_m)s)
            ORDER BY public.ST_Distance(public.geography(n.geom), public.geography(pt.geom))
            LIMIT 1)
            """


def get_place_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for place_nodes: count, extent, timestamps. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
