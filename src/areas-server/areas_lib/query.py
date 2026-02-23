"""
PostGIS query orchestration for is_in area server.
Runs admin, protected_areas, and water lookups; exposes query_single, query_batch, check_health, get_stats.
"""
from concurrent.futures import ThreadPoolExecutor, wait
from typing import Any, Dict, List, Optional, Tuple

from config import SCHEMA
from areas_lib import lookup_admin, lookup_water, lookup_protected_areas, lookup_ocean, lookup_places


def query_single(
        pool: Any,
        lat: float,
        lon: float,
        lake_radius_miles: float = 1.0,
        ocean_radius_miles: float = 1.0,
        city_radius_miles: float = 3.0,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str]]:
    """Run admin + protected + water + ocean (+ optional place) queries in parallel; return (admin_hierarchy, protected_areas, nearby_lakes, oceans)."""
    conn1 = pool.getconn()
    conn2 = pool.getconn()
    conn3 = pool.getconn()
    conn4 = pool.getconn()
    conn5 = pool.getconn() if city_radius_miles > 0 else None
    try:
        with ThreadPoolExecutor(max_workers=5 if conn5 else 4) as ex:
            f_admin = ex.submit(lookup_admin.run_admin_single, conn1, lat, lon)
            f_protected = ex.submit(lookup_protected_areas.run_protected_single, conn2, lat, lon)
            f_water = ex.submit(lookup_water.run_water_single, conn3, lat, lon, lake_radius_miles)
            f_ocean = ex.submit(lookup_ocean.run_ocean_single, conn4, lat, lon, ocean_radius_miles)
            f_place = ex.submit(lookup_places.run_place_single, conn5, lat, lon, city_radius_miles) if conn5 is not None else None
            futures = [f_admin, f_protected, f_water, f_ocean]
            if f_place is not None:
                futures.append(f_place)
            wait(futures)
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
            water_rows = f_water.result()
            oceans = f_ocean.result()
            place_name = f_place.result() if f_place is not None else None
        admin_hierarchy = lookup_admin.build_admin_hierarchy(admin_rows)
        if admin_hierarchy.get("city") is None and place_name:
            admin_hierarchy["city"] = place_name
        return (
            admin_hierarchy,
            lookup_protected_areas.build_protected_list(protected_rows),
            lookup_water.build_nearby_lakes(water_rows),
            oceans if oceans is not None else [],
        )
    finally:
        conn1.rollback()
        conn2.rollback()
        conn3.rollback()
        conn4.rollback()
        pool.putconn(conn1)
        pool.putconn(conn2)
        pool.putconn(conn3)
        pool.putconn(conn4)
        if conn5 is not None:
            conn5.rollback()
            pool.putconn(conn5)


def query_batch(
        pool: Any,
        points: List[Tuple[float, float]],
        lake_radius_miles: float = 1.0,
        ocean_radius_miles: float = 1.0,
        city_radius_miles: float = 3.0,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str]]]:
    """Run admin + protected + water + ocean (+ optional place) batch queries; return list of (admin_hierarchy, protected_areas, nearby_lakes, oceans) in order."""
    if not points:
        return []
    n = len(points)
    indices = list(range(n))
    lons = [p[1] for p in points]
    lats = [p[0] for p in points]

    conn1 = pool.getconn()
    conn2 = pool.getconn()
    conn3 = pool.getconn()
    conn4 = pool.getconn()
    conn5 = pool.getconn() if city_radius_miles > 0 else None
    try:
        with ThreadPoolExecutor(max_workers=5 if conn5 else 4) as ex:
            f_admin = ex.submit(lookup_admin.run_admin_batch, conn1, indices, lons, lats)
            f_protected = ex.submit(lookup_protected_areas.run_protected_batch, conn2, indices, lons, lats)
            f_water = ex.submit(
                lookup_water.run_water_batch,
                conn3,
                indices,
                lons,
                lats,
                lake_radius_miles,
            )
            f_ocean = ex.submit(lookup_ocean.run_ocean_batch, conn4, indices, lons, lats, ocean_radius_miles)
            f_place = ex.submit(lookup_places.run_place_batch, conn5, indices, lons, lats, city_radius_miles) if conn5 is not None else None
            futures = [f_admin, f_protected, f_water, f_ocean]
            if f_place is not None:
                futures.append(f_place)
            wait(futures)
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
            water_by_idx = f_water.result()
            ocean_by_idx = f_ocean.result()
            place_by_idx = f_place.result() if f_place is not None else {}
    finally:
        conn1.rollback()
        conn2.rollback()
        conn3.rollback()
        conn4.rollback()
        pool.putconn(conn1)
        pool.putconn(conn2)
        pool.putconn(conn3)
        pool.putconn(conn4)
        if conn5 is not None:
            conn5.rollback()
            pool.putconn(conn5)

    admin_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in admin_rows:
        idx = row[0]
        admin_by_idx.setdefault(idx, []).append(row[1:])

    protected_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in protected_rows:
        idx = row[0]
        protected_by_idx.setdefault(idx, []).append(row[1:])

    results: List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str]]] = [None] * n
    for i in range(n):
        admin_rows_i = admin_by_idx.get(i, [])
        protected_rows_i = protected_by_idx.get(i, [])
        water_rows_i = water_by_idx.get(i, [])
        oceans = ocean_by_idx.get(i) or []
        admin_hierarchy = lookup_admin.build_admin_hierarchy(admin_rows_i)
        if admin_hierarchy.get("city") is None and place_by_idx.get(i):
            admin_hierarchy["city"] = place_by_idx[i]
        results[i] = (
            admin_hierarchy,
            lookup_protected_areas.build_protected_list(protected_rows_i),
            lookup_water.build_nearby_lakes(water_rows_i),
            oceans,
        )
    return results


def check_health(conn: Any) -> Tuple[bool, Optional[str]]:
    """Check DB connectivity and that tables exist. Returns (ok, error_message)."""
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT EXISTS (SELECT 1
                               FROM information_schema.tables
                               WHERE table_schema = %s
                                 AND table_name = %s) AND EXISTS (SELECT 1
                                                                  FROM information_schema.tables
                                                                  WHERE table_schema = %s
                                                                    AND table_name = %s) AND EXISTS (SELECT 1
                                                                                                     FROM information_schema.tables
                                                                                                     WHERE table_schema = %s
                                                                                                       AND table_name = %s)
                """,
                (SCHEMA, lookup_admin.TABLE_NAME, SCHEMA, lookup_protected_areas.TABLE_NAME, SCHEMA, lookup_water.TABLE_NAME),
            )
            row = cur.fetchone()
            if not row or not row[0]:
                return False, (
                    f"Tables {SCHEMA}.{lookup_admin.TABLE_NAME}, {SCHEMA}.{lookup_protected_areas.TABLE_NAME} "
                    f"or {SCHEMA}.{lookup_water.TABLE_NAME} not found"
                )
        return True, None
    except Exception:
        raise


def get_stats(conn: Any) -> Dict[str, Any]:
    """Return database stats: feature counts, extent, and timestamps per layer."""
    out = {
        "admin_areas": lookup_admin.get_admin_stats(conn),
        "protected_areas": lookup_protected_areas.get_protected_stats(conn),
        "water_bodies": lookup_water.get_water_stats(conn),
    }
    ocean_regions_stats = lookup_ocean.get_ocean_regions_stats(conn)
    if ocean_regions_stats is not None:
        out["ocean_regions"] = ocean_regions_stats
    oceans_stats = lookup_ocean.get_oceans_stats(conn)
    if oceans_stats is not None:
        out["oceans"] = oceans_stats
    place_stats = lookup_places.get_place_stats(conn)
    if place_stats is not None:
        out["place_nodes"] = place_stats
    return out
