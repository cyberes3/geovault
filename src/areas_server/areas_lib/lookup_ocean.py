"""Ocean name lookup from ocean_regions (sub-regions) and oceans (main oceans) tables."""
from typing import Any, Dict, List, Optional

from config import SCHEMA
from .lookup_common import extent_from_row

TABLE_OCEAN_REGIONS = "ocean_regions"
TABLE_OCEANS = "oceans"

# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34

_MAX_OCEAN_NAMES = 2


def _query_region_single(conn: Any, lat: float, lon: float, radius_m: float) -> Optional[str]:
    """Return name from ocean_regions if point inside or within radius; else None. Table may not exist."""
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT name FROM {SCHEMA}.{TABLE_OCEAN_REGIONS}
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
                SELECT name FROM {SCHEMA}.{TABLE_OCEAN_REGIONS}
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


def _query_oceans_single(conn: Any, lat: float, lon: float) -> Optional[str]:
    """Return name from oceans (GOaS) if point inside; else None. Table may not exist."""
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT name FROM {SCHEMA}.{TABLE_OCEANS}
                WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326))
                LIMIT 1
                """,
                (lon, lat),
            )
            row = cur.fetchone()
            if row and row[0]:
                return str(row[0]).strip()
    except Exception:
        pass
    return None


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
    """Return list of 0–2 ocean names: from ocean_regions (sub-region) then oceans (main ocean)."""
    radius_m = ocean_radius_miles * _MILES_TO_M
    region = _query_region_single(conn, lat, lon, radius_m)
    ocean = _query_oceans_single(conn, lat, lon)
    return _merge_ocean_names(region, ocean)


def _query_region_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        radius_m: float,
) -> Dict[int, Optional[str]]:
    """Return dict point_idx -> region name or None. Table may not exist."""
    out: Dict[int, Optional[str]] = {i: None for i in indices}
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
                    JOIN {SCHEMA}.{TABLE_OCEAN_REGIONS} o
                         ON public.ST_Contains(o.geom, pt.geom)
                         OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
                )
                SELECT point_idx, name FROM ranked WHERE rn = 1
                """,
                (indices, lons, lats, radius_m),
            )
            for row in cur.fetchall():
                if row and len(row) >= 2 and row[0] is not None and row[1]:
                    out[row[0]] = str(row[1]).strip()
    except Exception:
        pass
    return out


def _query_oceans_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
) -> Dict[int, Optional[str]]:
    """Return dict point_idx -> ocean name or None. Table may not exist."""
    out: Dict[int, Optional[str]] = {i: None for i in indices}
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
                )
                SELECT DISTINCT ON (pt.point_idx) pt.point_idx, o.name
                FROM pt
                JOIN {SCHEMA}.{TABLE_OCEANS} o ON public.ST_Contains(o.geom, pt.geom)
                ORDER BY pt.point_idx
                """,
                (indices, lons, lats),
            )
            for row in cur.fetchall():
                if row and len(row) >= 2 and row[0] is not None and row[1]:
                    out[row[0]] = str(row[1]).strip()
    except Exception:
        pass
    return out


def run_ocean_batch(
        conn: Any,
        indices: List[int],
        lons: List[float],
        lats: List[float],
        ocean_radius_miles: float = 1.0,
) -> Dict[int, List[str]]:
    """Return dict point_idx -> list of 0–2 ocean names."""
    radius_m = ocean_radius_miles * _MILES_TO_M
    by_region = _query_region_batch(conn, indices, lons, lats, radius_m)
    by_ocean = _query_oceans_batch(conn, indices, lons, lats)
    out: Dict[int, List[str]] = {}
    for i in indices:
        out[i] = _merge_ocean_names(by_region.get(i), by_ocean.get(i))
    return out


def _stats_for_table(conn: Any, table: str) -> Optional[Dict[str, Any]]:
    """Return count and extent for table; None if table missing or error."""
    out: Dict[str, Any] = {"count": 0, "extent": None}
    try:
        with conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.{table}")
            row = cur.fetchone()
            if row and row[0] is not None:
                out["count"] = row[0]
            cur.execute(
                f"""
                SELECT public.ST_XMin(e), public.ST_YMin(e), public.ST_XMax(e), public.ST_YMax(e)
                FROM (SELECT public.ST_Extent(geom) AS e FROM {SCHEMA}.{table}) _t
                """
            )
            row = cur.fetchone()
            if row and row[0] is not None:
                out["extent"] = extent_from_row(tuple(row))
        return out
    except Exception:
        return None


def get_ocean_regions_stats(conn: Any) -> Optional[Dict[str, Any]]:
    """Return stats for ocean_regions when table exists: count, extent. Else None."""
    return _stats_for_table(conn, TABLE_OCEAN_REGIONS)


def get_oceans_stats(conn: Any) -> Optional[Dict[str, Any]]:
    """Return stats for oceans when table exists: count, extent. Else None."""
    return _stats_for_table(conn, TABLE_OCEANS)