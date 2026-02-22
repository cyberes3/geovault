"""Ocean name lookup from ocean_polygons table (Natural Earth marine)."""
from typing import Any, Dict, List, Optional

from config import SCHEMA
from .lookup_common import extent_from_row

TABLE_NAME = "ocean_polygons"

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34


def run_ocean_single(
        conn: Any,
        lat: float,
        lon: float,
        ocean_radius_miles: float = 1.0,
) -> Optional[str]:
    """Return ocean name if point is inside or within ocean_radius_miles of a polygon; else None."""
    radius_m = ocean_radius_miles * _MILES_TO_M
    try:
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
            cur.execute(
                f"""
                SELECT name FROM {SCHEMA}.{TABLE_NAME}
                WHERE public.ST_DWithin(public.geography(geom), public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326)), %s)
                ORDER BY public.ST_Distance(public.geography(geom), public.geography(public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326)))
                LIMIT 1
                """,
                (lon, lat, radius_m, lon, lat),
            )
            row = cur.fetchone()
            if row and row[0]:
                return str(row[0]).strip()
    except Exception:
        pass
    return None


def run_ocean_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        ocean_radius_miles: float = 1.0,
) -> Dict[int, Optional[str]]:
    """Return dict point_idx -> ocean name or None."""
    out: Dict[int, Optional[str]] = {i: None for i in indices}
    radius_m = ocean_radius_miles * _MILES_TO_M
    try:
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
                    SELECT pt.point_idx, o.name,
                           ROW_NUMBER() OVER (
                               PARTITION BY pt.point_idx
                               ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                                        public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
                           ) AS rn
                    FROM pt
                    JOIN {SCHEMA}.{TABLE_NAME} o
                         ON public.ST_Contains(o.geom, pt.geom)
                         OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
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
    except Exception:
        pass
    return out


def get_ocean_stats(conn: Any) -> Optional[Dict[str, Any]]:
    """Return stats for ocean_polygons when table exists: count, extent. Else None."""
    out: Dict[str, Any] = {
        "count": 0,
        "extent": None,
    }
    try:
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
        return out
    except Exception:
        return None
