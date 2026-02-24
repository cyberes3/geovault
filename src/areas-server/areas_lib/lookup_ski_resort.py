"""Ski resort name lookup from is_in.ski_resorts table (ST_Contains only)."""
from typing import Any, Dict, List, Optional

from config import SCHEMA

from .lookup_common import get_table_stats

TABLE_NAME = "ski_resorts"


def run_ski_resort_single(conn: Any, lat: float, lon: float) -> Optional[str]:
    """Return resort name if point is inside a polygon; else None. Fails hard if table is missing."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT name FROM {SCHEMA}.{TABLE_NAME}
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
            LIMIT 1
            """,
            (lon, lat),
        )
        row = cur.fetchone()
        if row and row[0]:
            return str(row[0]).strip()
    return None


def run_ski_resort_batch(
    conn: Any,
    indices: List[int],
    lons: List[float],
    lats: List[float],
) -> Dict[int, Optional[str]]:
    """Return dict point_idx -> resort name or None. Fails hard if table is missing."""
    out: Dict[int, Optional[str]] = {i: None for i in indices}
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
            )
            SELECT DISTINCT ON (pt.point_idx) pt.point_idx, s.name
            FROM pt
            JOIN {SCHEMA}.{TABLE_NAME} s ON public.ST_Contains(s.geom, pt.geom)
            ORDER BY pt.point_idx
            """,
            (indices, lons, lats),
        )
        for row in cur.fetchall():
            if row and len(row) >= 2 and row[0] is not None and row[1]:
                out[row[0]] = str(row[1]).strip()
    return out


def get_ski_resort_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for ski_resorts: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=False)
