"""
PostGIS query orchestration for is_in area server.
Runs admin, protected_areas, and water lookups; exposes query_single, query_batch, check_health, get_stats.
"""
from typing import Any, Dict, List, Optional, Tuple

from config import SCHEMA
from areas_lib import lookup_admin, lookup_water, lookup_protected_areas, lookup_ocean, lookup_places, lookup_ski_resort

# Limits for unified single-point query (must match lookup module constants)
_PROTECTED_LIMIT = 5
_NEARBY_LAKES_LIMIT = 5
_MILES_TO_M = 1609.34


def _query_single_unified_sql(include_place: bool) -> Tuple[str, List[Any]]:
    """Build one UNION ALL query for single point and return (sql, params). Params: lon, lat, lake_radius_m, ocean_radius_m[, city_radius_m]."""
    pt_cte = f"WITH pt AS (SELECT public.ST_SetSRID(public.ST_MakePoint(%s, %s), 4326) AS geom)"
    parts = [
        f"""
        SELECT 'admin' AS layer, jsonb_build_object('osm_id', a.osm_id, 'admin_level', a.admin_level, 'name', a.name, 'tags', a.tags) AS payload
        FROM {SCHEMA}.{lookup_admin.TABLE_NAME} a, pt
        WHERE public.ST_Contains(a.geom, pt.geom)
        ORDER BY a.admin_level
        """,
        f"""
        SELECT 'protected' AS layer, jsonb_build_object('osm_id', p.osm_id, 'name', p.name, 'tags', p.tags) AS payload
        FROM {SCHEMA}.{lookup_protected_areas.TABLE_NAME} p, pt
        WHERE public.ST_Contains(p.geom, pt.geom)
        LIMIT {_PROTECTED_LIMIT}
        """,
        f"""
        (SELECT 'water' AS layer, jsonb_build_object('name', w.name, 'water_type', w.water_type, 'distance_miles', 0, 'on_water', true) AS payload
         FROM {SCHEMA}.{lookup_water.TABLE_NAME} w, pt
         WHERE public.ST_Contains(w.geom, pt.geom)
         LIMIT {_NEARBY_LAKES_LIMIT})
        UNION ALL
        (SELECT 'water' AS layer, jsonb_build_object('name', w.name, 'water_type', w.water_type,
                 'distance_miles', (public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / {_MILES_TO_M})::double precision,
                 'on_water', false) AS payload
         FROM {SCHEMA}.{lookup_water.TABLE_NAME} w, pt
         WHERE NOT public.ST_Contains(w.geom, pt.geom)
           AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %s)
         ORDER BY public.ST_Distance(public.geography(w.geom), public.geography(pt.geom))
         LIMIT {_NEARBY_LAKES_LIMIT})
        """,
        f"""
        SELECT 'ocean_region' AS layer, jsonb_build_object('name', sub.name) AS payload
        FROM (
            SELECT o.name FROM {SCHEMA}.{lookup_ocean.TABLE_OCEAN_REGIONS} o, pt
            WHERE public.ST_Contains(o.geom, pt.geom)
               OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
            ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                     public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
            LIMIT 1
        ) sub
        """,
        f"""
        SELECT 'ocean_main' AS layer, jsonb_build_object('name', o.name) AS payload
        FROM {SCHEMA}.{lookup_ocean.TABLE_OCEANS} o, pt
        WHERE public.ST_Contains(o.geom, pt.geom)
        LIMIT 1
        """,
        f"""
        SELECT 'ski' AS layer, jsonb_build_object('name', s.name) AS payload
        FROM {SCHEMA}.{lookup_ski_resort.TABLE_NAME} s, pt
        WHERE public.ST_Contains(s.geom, pt.geom)
        LIMIT 1
        """,
    ]
    if include_place:
        parts.append(
            f"""
            SELECT 'place' AS layer, jsonb_build_object('name', n.name) AS payload
            FROM {SCHEMA}.{lookup_places.TABLE_NAME} n, pt
            WHERE public.ST_DWithin(public.geography(n.geom), public.geography(pt.geom), %s)
            ORDER BY public.ST_Distance(public.geography(n.geom), public.geography(pt.geom))
            LIMIT 1
            """
        )
    sql = pt_cte + "\n" + "\nUNION ALL\n".join(parts)
    return sql, []


def _query_batch_unified_sql(include_place: bool) -> str:
    """Build one UNION ALL query for batch (all points, all layers). Params: indices, lons, lats, lake_radius_m, ocean_radius_m[, city_radius_m]."""
    cte = f"""
    WITH p AS (
        SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
        AS t(point_idx, lon, lat)
    ),
    pt AS (
        SELECT point_idx,
               public.ST_SetSRID(public.ST_MakePoint(lon, lat), 4326) AS geom
        FROM p
    )"""
    parts = [
        f"""
        SELECT pt.point_idx, 'admin' AS layer, jsonb_build_object('osm_id', a.osm_id, 'admin_level', a.admin_level, 'name', a.name, 'tags', a.tags) AS payload
        FROM pt
        JOIN {SCHEMA}.{lookup_admin.TABLE_NAME} a ON public.ST_Contains(a.geom, pt.geom)
        ORDER BY pt.point_idx, a.admin_level
        """,
        f"""
        SELECT point_idx, 'protected' AS layer, payload FROM (
            SELECT pt.point_idx, jsonb_build_object('osm_id', a.osm_id, 'name', a.name, 'tags', a.tags) AS payload,
                   ROW_NUMBER() OVER (PARTITION BY pt.point_idx ORDER BY a.osm_id) AS rn
            FROM pt
            JOIN {SCHEMA}.{lookup_protected_areas.TABLE_NAME} a ON public.ST_Contains(a.geom, pt.geom)
        ) sub WHERE rn <= {_PROTECTED_LIMIT}
        """,
        f"""
        SELECT point_idx, 'water' AS layer, payload FROM (
            SELECT pt.point_idx,
                   jsonb_build_object('name', w.name, 'water_type', w.water_type,
                       'distance_miles', (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                             ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / {_MILES_TO_M} END)::double precision,
                       'on_water', public.ST_Contains(w.geom, pt.geom)) AS payload,
                   ROW_NUMBER() OVER (PARTITION BY pt.point_idx
                        ORDER BY public.ST_Contains(w.geom, pt.geom) DESC NULLS LAST,
                                 (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                                       ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / {_MILES_TO_M} END)::double precision) AS rn
            FROM pt
            JOIN {SCHEMA}.{lookup_water.TABLE_NAME} w
                 ON public.ST_Contains(w.geom, pt.geom)
                 OR (NOT public.ST_Contains(w.geom, pt.geom)
                     AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %s))
        ) sub WHERE rn <= {_NEARBY_LAKES_LIMIT}
        """,
        f"""
        SELECT pt.point_idx, 'ocean_region' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', o.name) AS payload
            FROM {SCHEMA}.{lookup_ocean.TABLE_OCEAN_REGIONS} o
            WHERE public.ST_Contains(o.geom, pt.geom)
               OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %s)
            ORDER BY public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                     public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """,
        f"""
        SELECT pt.point_idx, 'ocean_main' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', o.name) AS payload
            FROM {SCHEMA}.{lookup_ocean.TABLE_OCEANS} o
            WHERE public.ST_Contains(o.geom, pt.geom)
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """,
        f"""
        SELECT pt.point_idx, 'ski' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', s.name) AS payload
            FROM {SCHEMA}.{lookup_ski_resort.TABLE_NAME} s
            WHERE public.ST_Contains(s.geom, pt.geom)
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """,
    ]
    if include_place:
        parts.append(
            f"""
            SELECT pt.point_idx, 'place' AS layer, sub.payload
            FROM pt
            LEFT JOIN LATERAL (
                SELECT jsonb_build_object('name', n.name) AS payload
                FROM {SCHEMA}.{lookup_places.TABLE_NAME} n
                WHERE public.ST_DWithin(public.geography(n.geom), public.geography(pt.geom), %s)
                ORDER BY public.ST_Distance(public.geography(n.geom), public.geography(pt.geom))
                LIMIT 1
            ) sub ON true
            WHERE sub.payload IS NOT NULL
            """
        )
    return cte + "\n" + "\nUNION ALL\n".join(parts)


def _parse_batch_unified_rows(
    rows: List[Tuple[Any, ...]],
    n: int,
    include_place: bool,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]]:
    """Partition (point_idx, layer, payload) rows and build one result tuple per point index."""
    admin_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    protected_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    water_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    ocean_region_by_idx: Dict[int, Optional[str]] = {}
    ocean_main_by_idx: Dict[int, Optional[str]] = {}
    ski_by_idx: Dict[int, Optional[str]] = {}
    place_by_idx: Dict[int, Optional[str]] = {}

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
                n = p.get("name")
                ocean_region_by_idx[idx] = str(n).strip() if n else None
        elif layer == "ocean_main":
            p = payload if isinstance(payload, dict) else {}
            if idx not in ocean_main_by_idx:
                n = p.get("name")
                ocean_main_by_idx[idx] = str(n).strip() if n else None
        elif layer == "ski":
            p = payload if isinstance(payload, dict) else {}
            if idx not in ski_by_idx:
                n = p.get("name")
                ski_by_idx[idx] = str(n).strip() if n else None
        elif layer == "place" and include_place:
            p = payload if isinstance(payload, dict) else {}
            if idx not in place_by_idx:
                n = p.get("name")
                place_by_idx[idx] = str(n).strip() if n else None

    results: List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]] = [None] * n
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
            lookup_water.build_nearby_lakes(water_rows_i),
            oceans_list,
            ski_by_idx.get(i),
        )
    return results


def _parse_single_unified_rows(
    rows: List[Tuple[Any, ...]],
    include_place: bool,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]:
    """Partition (layer, payload) rows and build same return shape as query_single."""
    admin_rows: List[Tuple[Any, ...]] = []
    protected_rows: List[Tuple[Any, ...]] = []
    water_rows: List[Tuple[Any, ...]] = []
    ocean_region_name: Optional[str] = None
    ocean_main_name: Optional[str] = None
    ski_name: Optional[str] = None
    place_name: Optional[str] = None

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
            ocean_region_name = str(n).strip() if n else None
        elif layer == "ocean_main" and ocean_main_name is None:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            ocean_main_name = str(n).strip() if n else None
        elif layer == "ski" and ski_name is None:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            ski_name = str(n).strip() if n else None
        elif layer == "place" and place_name is None and include_place:
            p = payload if isinstance(payload, dict) else {}
            n = p.get("name")
            place_name = str(n).strip() if n else None

    oceans_list = lookup_ocean._merge_ocean_names(ocean_region_name, ocean_main_name)
    admin_hierarchy = lookup_admin.build_admin_hierarchy(admin_rows)
    if admin_hierarchy.get("city") is None and place_name:
        admin_hierarchy["city"] = place_name
    return (
        admin_hierarchy,
        lookup_protected_areas.build_protected_list(protected_rows),
        lookup_water.build_nearby_lakes(water_rows),
        oceans_list,
        ski_name,
    )


def query_single_unified(
    pool: Any,
    lat: float,
    lon: float,
    lake_radius_miles: float = 1.0,
    ocean_radius_miles: float = 1.0,
    city_radius_miles: float = 3.0,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]:
    """Single-point query in one SQL round-trip. Same return shape as query_single. Raises on DB/table errors."""
    lake_radius_m = lake_radius_miles * _MILES_TO_M
    ocean_radius_m = ocean_radius_miles * _MILES_TO_M
    city_radius_m = city_radius_miles * _MILES_TO_M
    include_place = city_radius_miles > 0
    sql, _ = _query_single_unified_sql(include_place)
    params: List[Any] = [lon, lat, lake_radius_m, ocean_radius_m]
    if include_place:
        params.append(city_radius_m)
    conn = pool.getconn()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return _parse_single_unified_rows(rows, include_place)
    finally:
        conn.rollback()
        pool.putconn(conn)


def query_single(
        pool: Any,
        lat: float,
        lon: float,
        lake_radius_miles: float = 1.0,
        ocean_radius_miles: float = 1.0,
        city_radius_miles: float = 3.0,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]:
    """Run admin + protected + water + ocean + ski_resort (+ optional place). One SQL query. Fails hard if any table is missing."""
    return query_single_unified(
        pool, lat, lon,
        lake_radius_miles=lake_radius_miles,
        ocean_radius_miles=ocean_radius_miles,
        city_radius_miles=city_radius_miles,
    )


def query_batch_unified(
    pool: Any,
    points: List[Tuple[float, float]],
    lake_radius_miles: float = 1.0,
    ocean_radius_miles: float = 1.0,
    city_radius_miles: float = 3.0,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]]:
    """Batch query in one SQL round-trip. Same return shape as query_batch. Fails hard if any table is missing."""
    if not points:
        return []
    n = len(points)
    indices = list(range(n))
    lons = [p[1] for p in points]
    lats = [p[0] for p in points]
    lake_radius_m = lake_radius_miles * _MILES_TO_M
    ocean_radius_m = ocean_radius_miles * _MILES_TO_M
    city_radius_m = city_radius_miles * _MILES_TO_M
    include_place = city_radius_miles > 0
    sql = _query_batch_unified_sql(include_place)
    params: List[Any] = [indices, lons, lats, lake_radius_m, ocean_radius_m]
    if include_place:
        params.append(city_radius_m)
    conn = pool.getconn()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        return _parse_batch_unified_rows(rows, n, include_place)
    finally:
        conn.rollback()
        pool.putconn(conn)


def query_batch(
        pool: Any,
        points: List[Tuple[float, float]],
        lake_radius_miles: float = 1.0,
        ocean_radius_miles: float = 1.0,
        city_radius_miles: float = 3.0,
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]], List[str], Optional[str]]]:
    """Run admin + protected + water + ocean + ski_resort (+ optional place) batch. One SQL query. Fails hard if any table is missing."""
    return query_batch_unified(
        pool, points,
        lake_radius_miles=lake_radius_miles,
        ocean_radius_miles=ocean_radius_miles,
        city_radius_miles=city_radius_miles,
    )


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
    """Return database stats: feature counts, extent, and timestamps per layer. Fails hard if any table is missing."""
    return {
        "admin_areas": lookup_admin.get_admin_stats(conn),
        "protected_areas": lookup_protected_areas.get_protected_stats(conn),
        "water_bodies": lookup_water.get_water_stats(conn),
        "ocean_regions": lookup_ocean.get_ocean_regions_stats(conn),
        "oceans": lookup_ocean.get_oceans_stats(conn),
        "place_nodes": lookup_places.get_place_stats(conn),
        "ski_resorts": lookup_ski_resort.get_ski_resort_stats(conn),
    }
