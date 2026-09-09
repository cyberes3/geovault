"""
PostGIS query orchestration for is_in area server.
Runs admin, protected_areas, and water lookups; exposes query_single, query_batch, check_health, get_stats.
"""
import traceback
from typing import Any, Dict, List, Optional, Tuple

from config import SCHEMA
from areas_lib import lookup_admin, lookup_common, lookup_water, lookup_protected_areas, lookup_ocean, lookup_places, lookup_ski_resort, lookup_waterway

_MILES_TO_M = 1609.34


def _query_single_sql(include_place: bool, include_waterway: bool = True) -> str:
    """Build one UNION ALL query for a single point. Named params: lon, lat, lake_radius_m, ocean_radius_m[, waterway_radius_m][, city_radius_m]."""
    pt_cte = "WITH pt AS (SELECT public.ST_SetSRID(public.ST_MakePoint(%(lon)s, %(lat)s), 4326) AS geom)"
    parts = [
        lookup_admin.sql_fragment(batch=False),
        lookup_protected_areas.sql_fragment(batch=False),
        lookup_water.sql_fragment(batch=False),
        lookup_ocean.sql_fragment(batch=False, table_name=lookup_ocean.TABLE_OCEAN_REGIONS, layer="ocean_region"),
        lookup_ocean.sql_fragment(batch=False, table_name=lookup_ocean.TABLE_OCEANS, layer="ocean_main"),
        lookup_ski_resort.sql_fragment(batch=False),
    ]
    if include_waterway:
        parts.append(lookup_waterway.sql_fragment(batch=False))
    if include_place:
        parts.append(lookup_places.sql_fragment(batch=False))
    return pt_cte + "\n" + "\nUNION ALL\n".join(parts)


def _query_batch_sql(include_place: bool, include_waterway: bool = True) -> str:
    """Build one UNION ALL query for a batch. Named params: indices, lons, lats, lake_radius_m, ocean_radius_m[, waterway_radius_m][, city_radius_m]."""
    cte = """
    WITH p AS (
        SELECT * FROM unnest(%(indices)s::bigint[], %(lons)s::double precision[], %(lats)s::double precision[])
        AS t(point_idx, lon, lat)
    ),
    pt AS (
        SELECT point_idx,
               public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326) AS geom
        FROM p
    )"""
    parts = [
        lookup_admin.sql_fragment(batch=True),
        lookup_protected_areas.sql_fragment(batch=True),
        lookup_water.sql_fragment(batch=True),
        lookup_ocean.sql_fragment(batch=True, table_name=lookup_ocean.TABLE_OCEAN_REGIONS, layer="ocean_region"),
        lookup_ocean.sql_fragment(batch=True, table_name=lookup_ocean.TABLE_OCEANS, layer="ocean_main"),
        lookup_ski_resort.sql_fragment(batch=True),
    ]
    if include_waterway:
        parts.append(lookup_waterway.sql_fragment(batch=True))
    if include_place:
        parts.append(lookup_places.sql_fragment(batch=True))
    return cte + "\n" + "\nUNION ALL\n".join(parts)


def _parse_batch_rows(
    rows: List[Tuple[Any, ...]],
    n: int,
    include_place: bool,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str], Optional[Dict[str, Any]]]]:
    """Partition (point_idx, layer, payload) rows and build one result tuple per point index."""
    admin_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    protected_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    water_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    ocean_region_by_idx: Dict[int, Optional[str]] = {}
    ocean_main_by_idx: Dict[int, Optional[str]] = {}
    ski_by_idx: Dict[int, Optional[str]] = {}
    place_by_idx: Dict[int, Optional[str]] = {}
    waterway_by_idx: Dict[int, Optional[Dict[str, Any]]] = {}

    for row in rows or []:
        if not row or len(row) < 3:
            continue
        point_idx, layer, payload = row[0], row[1], row[2]
        if point_idx is None or payload is None:
            continue
        idx = int(point_idx)
        if layer == "admin":
            p = payload if isinstance(payload, dict) else {}
            admin_by_idx.setdefault(idx, []).append((p.get("osm_id"), p.get("admin_level"), p.get("name"), p.get("tags")))
        elif layer == "protected":
            p = payload if isinstance(payload, dict) else {}
            protected_by_idx.setdefault(idx, []).append((p.get("osm_id"), p.get("name"), p.get("tags")))
        elif layer == "water":
            p = payload if isinstance(payload, dict) else {}
            water_by_idx.setdefault(idx, []).append((p.get("name"), p.get("water_type"), p.get("distance_miles"), p.get("on_water")))
        elif layer == "ocean_region":
            p = payload if isinstance(payload, dict) else {}
            if idx not in ocean_region_by_idx:
                name_val = p.get("name")
                ocean_region_by_idx[idx] = lookup_common.normalize_name_for_response(name_val) or None
        elif layer == "ocean_main":
            p = payload if isinstance(payload, dict) else {}
            if idx not in ocean_main_by_idx:
                name_val = p.get("name")
                ocean_main_by_idx[idx] = lookup_common.normalize_name_for_response(name_val) or None
        elif layer == "ski":
            p = payload if isinstance(payload, dict) else {}
            if idx not in ski_by_idx:
                name_val = p.get("name")
                ski_by_idx[idx] = lookup_common.normalize_name_for_response(name_val) or None
        elif layer == "place" and include_place:
            p = payload if isinstance(payload, dict) else {}
            if idx not in place_by_idx:
                name_val = p.get("name")
                place_by_idx[idx] = lookup_common.normalize_name_for_response(name_val) or None
        elif layer == "waterway":
            p = payload if isinstance(payload, dict) else {}
            if idx not in waterway_by_idx and (p.get("name") or p.get("distance_m") is not None):
                name_val = lookup_common.normalize_name_for_response(p.get("name")) or None
                waterway_by_idx[idx] = {"name": name_val, "distance_m": p.get("distance_m")}

    results: List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str], Optional[Dict[str, Any]]]] = [None] * n
    for i in range(n):
        admin_rows_i = admin_by_idx.get(i, [])
        protected_rows_i = protected_by_idx.get(i, [])
        water_rows_i = water_by_idx.get(i, [])
        region = ocean_region_by_idx.get(i)
        ocean = ocean_main_by_idx.get(i)
        oceans_list = lookup_ocean._merge_ocean_names(region, ocean)
        admin_hierarchy = lookup_admin.build_admin_hierarchy(admin_rows_i)
        place_name = place_by_idx.get(i)
        if admin_hierarchy.get("city") is None and place_name:
            admin_hierarchy["city"] = place_name
        results[i] = (
            admin_hierarchy,
            lookup_protected_areas.build_protected_list(protected_rows_i),
            lookup_water.build_lakes(water_rows_i),
            oceans_list,
            ski_by_idx.get(i),
            waterway_by_idx.get(i),
        )
    return results


def _parse_single_rows(
    rows: List[Tuple[Any, ...]],
    include_place: bool,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str], Optional[Dict[str, Any]]]:
    """Partition (layer, payload) rows and build single-point result tuple."""
    admin_rows: List[Tuple[Any, ...]] = []
    protected_rows: List[Tuple[Any, ...]] = []
    water_rows: List[Tuple[Any, ...]] = []
    ocean_region_name: Optional[str] = None
    ocean_main_name: Optional[str] = None
    ski_name: Optional[str] = None
    place_name: Optional[str] = None
    waterway_payload: Optional[Dict[str, Any]] = None

    for row in rows or []:
        if not row or len(row) < 2:
            continue
        layer, payload = row[0], row[1]
        if payload is None:
            continue
        if layer == "admin":
            p = payload if isinstance(payload, dict) else {}
            admin_rows.append((p.get("osm_id"), p.get("admin_level"), p.get("name"), p.get("tags")))
        elif layer == "protected":
            p = payload if isinstance(payload, dict) else {}
            protected_rows.append((p.get("osm_id"), p.get("name"), p.get("tags")))
        elif layer == "water":
            p = payload if isinstance(payload, dict) else {}
            water_rows.append((p.get("name"), p.get("water_type"), p.get("distance_miles"), p.get("on_water")))
        elif layer == "ocean_region" and ocean_region_name is None:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            ocean_region_name = lookup_common.normalize_name_for_response(n) or None
        elif layer == "ocean_main" and ocean_main_name is None:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            ocean_main_name = lookup_common.normalize_name_for_response(n) or None
        elif layer == "ski" and ski_name is None:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            ski_name = lookup_common.normalize_name_for_response(n) or None
        elif layer == "place" and place_name is None and include_place:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            place_name = lookup_common.normalize_name_for_response(n) or None
        elif layer == "waterway" and waterway_payload is None:
            p = payload if isinstance(payload, dict) else {}
            if p.get("name") or p.get("distance_m") is not None:
                name_val = lookup_common.normalize_name_for_response(p.get("name")) or None
                waterway_payload = {"name": name_val, "distance_m": p.get("distance_m")}

    oceans_list = lookup_ocean._merge_ocean_names(ocean_region_name, ocean_main_name)
    admin_hierarchy = lookup_admin.build_admin_hierarchy(admin_rows)
    if admin_hierarchy.get("city") is None and place_name:
        admin_hierarchy["city"] = place_name
    return (
        admin_hierarchy,
        lookup_protected_areas.build_protected_list(protected_rows),
        lookup_water.build_lakes(water_rows),
        oceans_list,
        ski_name,
        waterway_payload,
    )


def _query_params(
    *,
    lake_radius_miles: float,
    ocean_radius_miles: float,
    city_radius_miles: float,
    waterway_radius_miles: Optional[float],
) -> Tuple[Dict[str, Any], bool, float]:
    """Shared radius conversion for single and batch queries."""
    if waterway_radius_miles is None:
        waterway_radius_miles = lookup_waterway.DEFAULT_WATERWAY_RADIUS_MILES
    include_place = city_radius_miles > 0
    params: Dict[str, Any] = {
        "lake_radius_m": lake_radius_miles * _MILES_TO_M,
        "ocean_radius_m": ocean_radius_miles * _MILES_TO_M,
        "city_radius_m": city_radius_miles * _MILES_TO_M,
        "waterway_radius_m": waterway_radius_miles * _MILES_TO_M,
    }
    return params, include_place, waterway_radius_miles


def query_single(
    pool: Any,
    lat: float,
    lon: float,
    lake_radius_miles: float = 1.0,
    ocean_radius_miles: float = 1.0,
    city_radius_miles: float = 3.0,
    waterway_radius_miles: Optional[float] = None,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str], Optional[Dict[str, Any]]]:
    """Run admin + protected + water + ocean + ski_resort + waterway (+ optional place). One SQL query. Fails hard if any required table is missing."""
    params, include_place, _ = _query_params(
        lake_radius_miles=lake_radius_miles,
        ocean_radius_miles=ocean_radius_miles,
        city_radius_miles=city_radius_miles,
        waterway_radius_miles=waterway_radius_miles,
    )
    params["lon"] = lon
    params["lat"] = lat
    conn = pool.getconn()
    try:
        include_waterway = lookup_waterway.table_exists(conn)
        sql = _query_single_sql(include_place, include_waterway=include_waterway)
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return _parse_single_rows(rows, include_place)
    finally:
        try:
            conn.rollback()
        except Exception:
            traceback.print_exc()
        pool.putconn(conn)


def query_batch(
    pool: Any,
    points: List[Tuple[float, float]],
    lake_radius_miles: float = 1.0,
    ocean_radius_miles: float = 1.0,
    city_radius_miles: float = 3.0,
    waterway_radius_miles: Optional[float] = None,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str], Optional[Dict[str, Any]]]]:
    """Run admin + protected + water + ocean + ski_resort + waterway (+ optional place) batch. One SQL query. Fails hard if any required table is missing."""
    if not points:
        return []
    n = len(points)
    params, include_place, _ = _query_params(
        lake_radius_miles=lake_radius_miles,
        ocean_radius_miles=ocean_radius_miles,
        city_radius_miles=city_radius_miles,
        waterway_radius_miles=waterway_radius_miles,
    )
    params["indices"] = list(range(n))
    params["lons"] = [p[1] for p in points]
    params["lats"] = [p[0] for p in points]
    conn = pool.getconn()
    try:
        include_waterway = lookup_waterway.table_exists(conn)
        sql = _query_batch_sql(include_place, include_waterway=include_waterway)
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return _parse_batch_rows(rows, n, include_place)
    finally:
        try:
            conn.rollback()
        except Exception:
            traceback.print_exc()
        pool.putconn(conn)


def check_health(conn: Any) -> Tuple[bool, Optional[str]]:
    """Check DB connectivity (SELECT 1) and that required tables exist (to_regclass)."""
    admin_reg = f"{SCHEMA}.{lookup_admin.TABLE_NAME}"
    protected_reg = f"{SCHEMA}.{lookup_protected_areas.TABLE_NAME}"
    water_reg = f"{SCHEMA}.{lookup_water.TABLE_NAME}"
    with conn.cursor() as cur:
        cur.execute(
            "SELECT 1, to_regclass(%s), to_regclass(%s), to_regclass(%s)",
            (admin_reg, protected_reg, water_reg),
        )
        row = cur.fetchone()
    if not row or row[0] != 1 or row[1] is None or row[2] is None or row[3] is None:
        return False, (
            f"Tables {admin_reg}, {protected_reg} or {water_reg} not found"
        )
    return True, None


def get_stats(conn: Any) -> Dict[str, Any]:
    """Return database stats: feature counts, extent, and timestamps per layer. Fails hard if any required table is missing."""
    stats: Dict[str, Any] = {
        "admin_areas": lookup_admin.get_admin_stats(conn),
        "protected_areas": lookup_protected_areas.get_protected_stats(conn),
        "water_bodies": lookup_water.get_water_stats(conn),
        "ocean_regions": lookup_ocean.get_ocean_regions_stats(conn),
        "oceans": lookup_ocean.get_oceans_stats(conn),
        "place_nodes": lookup_places.get_place_stats(conn),
        "ski_resorts": lookup_ski_resort.get_ski_resort_stats(conn),
    }
    waterway_stats = lookup_waterway.get_waterway_stats(conn)
    if waterway_stats is not None:
        stats["major_waterways"] = waterway_stats
    return stats
