"""Nearby water bodies (lakes, reservoirs, ponds) from water_bodies table."""
from typing import Any, Dict, List, Tuple

from config import SCHEMA
from .lookup_common import extent_from_row

TABLE_NAME = "water_bodies"

# Top N most relevant nearby lakes per point (on water + near shore)
NEARBY_LAKES_LIMIT = 5

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34


def build_nearby_lakes(rows: List[Tuple[Any, ...]]) -> List[Dict[str, Any]]:
    """Build nearby_lakes list from query rows (name, water_type, distance_miles, on_water)."""
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
            "distance_miles": float(distance_miles) if distance_miles is not None else 0.0,
            "on_water": bool(on_water),
        })
    return out


def run_water_single(
        conn: Any,
        lat: float,
        lon: float,
        lake_radius_miles: float,
) -> List[Tuple[Any, ...]]:
    """Return rows (name, water_type, distance_miles, on_water): on-water first, then near shore by distance (top N)."""
    point_wkt = f"POINT({lon} {lat})"
    radius_m = lake_radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT name, water_type, 0::float, true
            FROM {SCHEMA}.{TABLE_NAME}
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
            LIMIT %s
            """,
            (point_wkt, NEARBY_LAKES_LIMIT),
        )
        on_water_rows = list(cur.fetchall())

        # Use literal for divisor to avoid locale-dependent param formatting; cast so alias is unambiguous
        cur.execute(
            f"""
            SELECT name, water_type,
                   (public.ST_Distance(public.geography(w.geom), public.geography(public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))) / 1609.34)::double precision AS distance_miles,
                   false AS on_water
            FROM {SCHEMA}.{TABLE_NAME} w
            WHERE NOT public.ST_Contains(w.geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
              AND public.ST_DWithin(public.geography(w.geom), public.geography(public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326)), %s)
            ORDER BY 3
            LIMIT %s
            """,
            (point_wkt, point_wkt, point_wkt, radius_m, NEARBY_LAKES_LIMIT),
        )
        near_rows = cur.fetchall()

    return on_water_rows + list(near_rows)


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
