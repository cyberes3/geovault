"""
PostGIS query orchestration for is_in area server.
Runs admin, protected_areas, and water lookups; exposes query_single, query_batch, check_health, get_stats.
"""
from concurrent.futures import ThreadPoolExecutor, wait
from typing import Any, Dict, List, Optional, Tuple

from config import SCHEMA
from areas_lib import lookup_admin, lookup_water, lookup_protected_areas


def query_single(
        pool: Any,
        lat: float,
        lon: float,
        lake_radius_miles: float = 1.0,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]]]:
    """Run admin + protected + water queries in parallel; return (admin_hierarchy, protected_areas, nearby_lakes)."""
    conn1 = pool.getconn()
    conn2 = pool.getconn()
    conn3 = pool.getconn()
    try:
        with ThreadPoolExecutor(max_workers=3) as ex:
            f_admin = ex.submit(lookup_admin.run_admin_single, conn1, lat, lon)
            f_protected = ex.submit(lookup_protected_areas.run_protected_single, conn2, lat, lon)
            f_water = ex.submit(lookup_water.run_water_single, conn3, lat, lon, lake_radius_miles)
            wait([f_admin, f_protected, f_water])
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
            water_rows = f_water.result()
        return (
            lookup_admin.build_admin_hierarchy(admin_rows),
            lookup_protected_areas.build_protected_list(protected_rows),
            lookup_water.build_nearby_lakes(water_rows),
        )
    finally:
        conn1.rollback()
        conn2.rollback()
        conn3.rollback()
        pool.putconn(conn1)
        pool.putconn(conn2)
        pool.putconn(conn3)


def query_batch(
        pool: Any,
        points: List[Tuple[float, float]],
        lake_radius_miles: float = 1.0,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]]]]:
    """Run admin + protected + water batch queries; return list of (admin_hierarchy, protected_areas, nearby_lakes) in order."""
    if not points:
        return []
    n = len(points)
    indices = list(range(n))
    lons = [p[1] for p in points]
    lats = [p[0] for p in points]

    conn1 = pool.getconn()
    conn2 = pool.getconn()
    conn3 = pool.getconn()
    try:
        with ThreadPoolExecutor(max_workers=3) as ex:
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
            wait([f_admin, f_protected, f_water])
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
            water_by_idx = f_water.result()
    finally:
        conn1.rollback()
        conn2.rollback()
        conn3.rollback()
        pool.putconn(conn1)
        pool.putconn(conn2)
        pool.putconn(conn3)

    admin_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in admin_rows:
        idx = row[0]
        admin_by_idx.setdefault(idx, []).append(row[1:])

    protected_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in protected_rows:
        idx = row[0]
        protected_by_idx.setdefault(idx, []).append(row[1:])

    results: List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]]]] = []
    for i in range(n):
        admin_rows_i = admin_by_idx.get(i, [])
        protected_rows_i = protected_by_idx.get(i, [])
        water_rows_i = water_by_idx.get(i, [])
        results.append((
            lookup_admin.build_admin_hierarchy(admin_rows_i),
            lookup_protected_areas.build_protected_list(protected_rows_i),
            lookup_water.build_nearby_lakes(water_rows_i),
        ))
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
    except Exception as e:
        return False, str(e)


def get_stats(conn: Any) -> Dict[str, Any]:
    """Return database stats: feature counts, extent, and timestamps per layer."""
    return {
        "admin_areas": lookup_admin.get_admin_stats(conn),
        "protected_areas": lookup_protected_areas.get_protected_stats(conn),
        "water_bodies": lookup_water.get_water_stats(conn),
    }
