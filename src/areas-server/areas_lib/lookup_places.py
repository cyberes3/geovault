"""Nearest place node (place=city|town|village) within radius for filling city when admin has none."""
from typing import Any, Dict, List, Optional

from config import SCHEMA
from .lookup_common import get_table_stats

TABLE_NAME = "place_nodes"

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34


def run_place_single(
        conn: Any,
        lat: float,
        lon: float,
        radius_miles: float,
) -> Optional[str]:
    """Return name of closest place node within radius_miles, or None if none in range or radius 0."""
    if radius_miles <= 0:
        return None
    radius_m = radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT name FROM {SCHEMA}.{TABLE_NAME}
            WHERE public.ST_DWithin(
                public.geography(geom),
                public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326)),
                %s
            )
            ORDER BY public.ST_Distance(
                public.geography(geom),
                public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
            )
            LIMIT 1
            """,
            (lon, lat, radius_m, lon, lat),
        )
        row = cur.fetchone()
        if row and row[0]:
            return str(row[0]).strip()
    return None


def run_place_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        radius_miles: float,
) -> Dict[int, Optional[str]]:
    """Return dict point_idx -> closest place name within radius, or None."""
    out: Dict[int, Optional[str]] = {i: None for i in indices}
    if radius_miles <= 0:
        return out
    radius_m = radius_miles * _MILES_TO_M
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
            ranked AS (
                SELECT pt.point_idx, n.name,
                       ROW_NUMBER() OVER (
                           PARTITION BY pt.point_idx
                           ORDER BY public.ST_Distance(public.geography(n.geom), public.geography(pt.geom))
                       ) AS rn
                FROM pt
                JOIN {SCHEMA}.{TABLE_NAME} n
                     ON public.ST_DWithin(public.geography(n.geom), public.geography(pt.geom), %s)
            )
            SELECT point_idx, name FROM ranked WHERE rn = 1
            """,
            (indices, lons, lats, radius_m),
        )
        for row in cur.fetchall():
            if row and len(row) >= 2 and row[0] is not None:
                idx = row[0]
                name = row[1]
                if name:
                    out[idx] = str(name).strip()
    return out


def get_place_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for place_nodes: count, extent, timestamps. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
