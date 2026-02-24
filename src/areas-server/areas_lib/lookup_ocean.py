"""Ocean name lookup from ocean_regions (sub-regions) and oceans (main oceans) tables."""
from typing import Any, Dict, List, Optional

from config import SCHEMA
from .lookup_common import get_table_stats

TABLE_OCEAN_REGIONS = "ocean_regions"
TABLE_OCEANS = "oceans"

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34

_MAX_OCEAN_NAMES = 2


def _merge_ocean_names(region: Optional[str], ocean: Optional[str]) -> List[str]:
    """Return list of 0–2 names: region first, then ocean if different. Cap at 2."""
    out: List[str] = []
    if region:
        out.append(region)
    if ocean and ocean != region and len(out) < _MAX_OCEAN_NAMES:
        out.append(ocean)
    return out[: _MAX_OCEAN_NAMES]


def run_ocean_single(
        conn: Any,
        lat: float,
        lon: float,
        ocean_radius_miles: float = 1.0,
) -> List[str]:
    """Return list of 0–2 ocean names: from ocean_regions (sub-region) then oceans (main ocean). One query. Fails hard if tables are missing."""
    radius_m = ocean_radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH pt AS (SELECT public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326) AS geom)
            SELECT
                (SELECT o.name FROM {SCHEMA}.{TABLE_OCEAN_REGIONS} o, pt
                 WHERE public.ST_Contains(o.geom, pt.geom)
                    OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
                 ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                          public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
                 LIMIT 1),
                (SELECT o.name FROM {SCHEMA}.{TABLE_OCEANS} o, pt
                 WHERE public.ST_Contains(o.geom, pt.geom)
                    OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
                 ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                          public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
                 LIMIT 1)
            FROM pt
            """,
            (lon, lat, radius_m, radius_m),
        )
        row = cur.fetchone()
        region = str(row[0]).strip() if row and row[0] else None
        ocean = str(row[1]).strip() if row and len(row) > 1 and row[1] else None
        return _merge_ocean_names(region, ocean)


def run_ocean_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        ocean_radius_miles: float = 1.0,
) -> Dict[int, List[str]]:
    """Return dict point_idx -> list of 0–2 ocean names. One query for region + ocean. Fails hard if tables are missing."""
    if not indices:
        return {}
    radius_m = ocean_radius_miles * _MILES_TO_M
    out: Dict[int, List[str]] = {i: _merge_ocean_names(None, None) for i in indices}
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
            SELECT pt.point_idx, reg.name AS region_name, oc.name AS ocean_name
            FROM pt
            LEFT JOIN LATERAL (
                SELECT o.name
                FROM {SCHEMA}.{TABLE_OCEAN_REGIONS} o
                WHERE public.ST_Contains(o.geom, pt.geom)
                   OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
                ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                         public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
                LIMIT 1
            ) reg ON true
            LEFT JOIN LATERAL (
                SELECT o.name
                FROM {SCHEMA}.{TABLE_OCEANS} o
                WHERE public.ST_Contains(o.geom, pt.geom)
                   OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
                ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                         public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
                LIMIT 1
            ) oc ON true
            """,
            (indices, lons, lats, radius_m, radius_m),
        )
        for row in cur.fetchall():
            if row and row[0] is not None:
                idx = row[0]
                region = str(row[1]).strip() if row[1] else None
                ocean = str(row[2]).strip() if len(row) > 2 and row[2] else None
                out[idx] = _merge_ocean_names(region, ocean)
    return out


def get_ocean_regions_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for ocean_regions: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_OCEAN_REGIONS, include_created=False)


def get_oceans_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for oceans: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_OCEANS, include_created=False)