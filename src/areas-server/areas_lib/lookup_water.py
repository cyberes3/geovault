"""Nearby water bodies (lakes, reservoirs, ponds) from water_bodies table."""
from typing import Any, Dict, List, Tuple

from config import SCHEMA
from .lookup_common import get_table_stats

TABLE_NAME = "water_bodies"

# Top N most relevant nearby lakes per point (on water + near shore)
NEARBY_LAKES_LIMIT = 5

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34


def build_lakes(rows: List[Tuple[Any, ...]]) -> List[Dict[str, Any]]:
    """Build lakes list from query rows (name, water_type, distance_miles, on_water)."""
    out: List[Dict[str, Any]] = []
    for row in rows:
        if len(row) < 4:
            continue
        name, water_type, distance_miles, on_water = row[0], row[1], row[2], row[3]
        if not name:
            continue
        out.append({
            "name": str(name).strip(),
            "water_type": str(water_type or "water").strip(),
            "distance_miles": round(float(distance_miles), 2) if distance_miles is not None else 0.0,
            "on_water": bool(on_water),
        })
    return out


def run_water_single(
        conn: Any,
        lat: float,
        lon: float,
        lake_radius_miles: float,
) -> List[Tuple[Any, ...]]:
    """Return rows (name, water_type, distance_miles, on_water): on-water first, then near shore by distance (top N). One round-trip."""
    radius_m = lake_radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH pt AS (
                SELECT public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326) AS geom
            )
            (SELECT w.name, w.water_type, 0::float, true
             FROM {SCHEMA}.{TABLE_NAME} w, pt
             WHERE public.ST_Contains(w.geom, pt.geom)
             LIMIT %s)
            UNION ALL
            (SELECT w.name, w.water_type,
                    (public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / 1609.34)::double precision,
                    false
             FROM {SCHEMA}.{TABLE_NAME} w, pt
             WHERE NOT public.ST_Contains(w.geom, pt.geom)
               AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %s)
             ORDER BY 3
             LIMIT %s)
            """,
            (lon, lat, NEARBY_LAKES_LIMIT, radius_m, NEARBY_LAKES_LIMIT),
        )
        return list(cur.fetchall())


def run_water_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        lake_radius_miles: float,
) -> Dict[int, List[Tuple[Any, ...]]]:
    """Returns dict point_idx -> list of (name, water_type, distance_miles, on_water)."""
    radius_m = lake_radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            ),
            pt AS (
                SELECT point_idx, lon, lat,
                       public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326) AS geom
                FROM p
            ),
            matches AS (
                SELECT pt.point_idx, w.name, w.water_type,
                       (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                             ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / 1609.34 END)::double precision AS distance_miles,
                       public.ST_Contains(w.geom, pt.geom) AS on_water,
                       ROW_NUMBER() OVER (
                           PARTITION BY pt.point_idx
                           ORDER BY public.ST_Contains(w.geom, pt.geom) DESC NULLS LAST,
                                 (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                                       ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / 1609.34 END)::double precision
                       ) AS rn
                FROM pt
                JOIN {SCHEMA}.{TABLE_NAME} w
                     ON public.ST_Contains(w.geom, pt.geom)
                     OR (NOT public.ST_Contains(w.geom, pt.geom)
                         AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %s))
            )
            SELECT point_idx, name, water_type, distance_miles, on_water
            FROM matches
            WHERE rn <= %s
            ORDER BY point_idx, rn
            """,
            (indices, lons, lats, radius_m, NEARBY_LAKES_LIMIT),
        )
        rows = cur.fetchall()

    by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in rows:
        idx = row[0]
        if idx not in by_idx:
            by_idx[idx] = []
        by_idx[idx].append(row[1:])
    return by_idx


def get_water_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for water_bodies: count, extent, oldest_feature, newest_feature."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
