"""Nearby water bodies (lakes, reservoirs, ponds) from water_bodies table."""
from typing import Any, Dict, List, Tuple

from config import SCHEMA
from .lookup_common import get_table_stats, normalize_name_for_response

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
            "name": normalize_name_for_response(name),
            "water_type": str(water_type or "water").strip(),
            "distance_miles": round(float(distance_miles), 2) if distance_miles is not None else 0.0,
            "on_water": bool(on_water),
        })
    return out


def sql_fragment(*, batch: bool) -> str:
    """UNION ALL branch for lakes. Cap 5; rank on-water first, then geography distance."""
    payload = f"""jsonb_build_object('name', w.name, 'water_type', w.water_type,
                       'distance_miles', (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                             ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / {_MILES_TO_M} END)::double precision,
                       'on_water', public.ST_Contains(w.geom, pt.geom))"""
    rank = """public.ST_Contains(w.geom, pt.geom) DESC NULLS LAST,
                                 (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                                       ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) END)"""
    match = f"""public.ST_Contains(w.geom, pt.geom)
                 OR (NOT public.ST_Contains(w.geom, pt.geom)
                     AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %(lake_radius_m)s))"""
    if batch:
        return f"""
        SELECT point_idx, 'water' AS layer, payload FROM (
            SELECT pt.point_idx, {payload} AS payload,
                   ROW_NUMBER() OVER (PARTITION BY pt.point_idx ORDER BY {rank}) AS rn
            FROM pt
            JOIN {SCHEMA}.{TABLE_NAME} w ON {match}
        ) sub WHERE rn <= {NEARBY_LAKES_LIMIT}
        """
    return f"""
        SELECT 'water' AS layer, payload FROM (
            SELECT {payload} AS payload,
                   ROW_NUMBER() OVER (ORDER BY {rank}) AS rn
            FROM {SCHEMA}.{TABLE_NAME} w, pt
            WHERE {match}
        ) sub WHERE rn <= {NEARBY_LAKES_LIMIT}
        """


def get_water_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for water_bodies: count, extent, oldest_feature, newest_feature."""
    return get_table_stats(conn, SCHEMA, TABLE_NAME, include_created=True)
