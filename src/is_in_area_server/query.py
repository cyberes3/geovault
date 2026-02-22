"""
PostGIS queries and response building for is_in area server.
Single-point and batch queries; builds admin_hierarchy and protected_areas response shape.
"""
from concurrent.futures import ThreadPoolExecutor, wait
from typing import Any, Dict, List, Optional, Tuple

import pycountry

from config import SCHEMA

_COUNTRY_NAME_ALIASES: Dict[str, str] = {
    "United States": "United States of America",
}


def _country_code_to_name(code: Optional[str]) -> Optional[str]:
    if not code or not isinstance(code, str):
        return None
    code = code.strip().upper()
    if len(code) != 2:
        return None
    try:
        c = pycountry.countries.get(alpha_2=code)
        if not c:
            return None
        name = getattr(c, "official_name", None) or c.name
        return _COUNTRY_NAME_ALIASES.get(name, name) if name else None
    except (KeyError, AttributeError):
        return None


def _normalize_country_name(name: Optional[str]) -> Optional[str]:
    if not name or not name.strip():
        return None
    return _COUNTRY_NAME_ALIASES.get(name.strip(), name.strip())


def _get_name_from_tags(tags: Dict[str, Any]) -> Optional[str]:
    if not tags:
        return None
    name = tags.get("name:en") or tags.get("name") or tags.get("int_name")
    return str(name).strip() if name else None


def _build_admin_hierarchy(rows: List[Tuple[Any, ...]]) -> Dict[str, Optional[str]]:
    """Build admin_hierarchy dict from query rows (osm_id, admin_level, name, tags)."""
    result: Dict[str, Optional[str]] = {
        "country": None,
        "state": None,
        "county": None,
        "city": None,
    }
    for row in rows:
        if len(row) < 4:
            continue
        _osm_id, admin_level, name, tags = row[0], row[1], row[2], row[3]
        tags = tags or {}
        name = name or _get_name_from_tags(tags)
        if not name:
            continue
        name = str(name).strip()
        if admin_level == 2:
            result["country"] = _normalize_country_name(name) or name
        elif admin_level == 4:
            result["state"] = name
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
        elif admin_level == 6:
            result["county"] = name
            if result["country"] is None:
                code_name = _country_code_to_name(tags.get("is_in:country_code"))
                if code_name:
                    result["country"] = code_name
            if result["state"] is None and tags.get("is_in:state"):
                result["state"] = str(tags.get("is_in:state", "")).strip()
        elif admin_level == 8:
            result["city"] = name
    return result


def _build_protected_list(rows: List[Tuple[Any, ...]]) -> List[Dict[str, str]]:
    """Build protected_areas list from query rows (osm_id, name, tags)."""
    out: List[Dict[str, str]] = []
    for row in rows:
        if len(row) < 3:
            continue
        _osm_id, name, tags = row[0], row[1], row[2]
        tags = tags or {}
        name = name or _get_name_from_tags(tags)
        if not name:
            continue
        out.append({
            "name": str(name).strip(),
            "protection_title": str(tags.get("protection_title") or ""),
            "protect_class": str(tags.get("protect_class") or ""),
            "designation": str(tags.get("designation") or ""),
            "operator": str(tags.get("operator") or ""),
            "leisure": str(tags.get("leisure") or ""),
            "landuse": str(tags.get("landuse") or ""),
            "boundary": str(tags.get("boundary") or ""),
        })
    return out


def _build_nearby_lakes(rows: List[Tuple[Any, ...]]) -> List[Dict[str, Any]]:
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


def _run_admin_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    # Schema-qualify PostGIS (public.) so it resolves when search_path is is_in only
    point_wkt = f"POINT({lon} {lat})"
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, admin_level, name, tags
            FROM {SCHEMA}.admin_areas
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
            ORDER BY admin_level ASC
            """,
            (point_wkt,),
        )
        return cur.fetchall()


def _run_protected_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    point_wkt = f"POINT({lon} {lat})"
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, name, tags
            FROM {SCHEMA}.protected_areas
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
            LIMIT 100
            """,
            (point_wkt,),
        )
        return cur.fetchall()


# 1 mile ≈ 1609.34 m
_MILES_TO_M = 1609.34


def _run_water_single(
    conn: Any,
    lat: float,
    lon: float,
    lake_radius_miles: float,
    nearby_lakes_limit: int,
) -> List[Tuple[Any, ...]]:
    """Return rows (name, water_type, distance_miles, on_water): on-water first (distance 0), then near shore by distance."""
    point_wkt = f"POINT({lon} {lat})"
    radius_m = lake_radius_miles * _MILES_TO_M
    with conn.cursor() as cur:
        # On water: ST_Contains (indexed). Limit to avoid huge result sets for overlapping water bodies.
        cur.execute(
            f"""
            SELECT name, water_type, 0::float, true
            FROM {SCHEMA}.water_bodies
            WHERE public.ST_Contains(geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
            LIMIT 100
            """,
            (point_wkt,),
        )
        on_water_rows = list(cur.fetchall())

        # Near shoreline: exclude containing, use ST_DWithin for index-friendly filter, then distance + limit
        cur.execute(
            f"""
            SELECT name, water_type,
                   (public.ST_Distance(public.geography(w.geom), public.geography(public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))) / %s AS distance_miles,
                   false
            FROM {SCHEMA}.water_bodies w
            WHERE NOT public.ST_Contains(w.geom, public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326))
              AND public.ST_DWithin(public.geography(w.geom), public.geography(public.ST_SetSRID(public.ST_GeomFromText(%s::text), 4326)), %s)
            ORDER BY distance_miles
            LIMIT %s
            """,
            (point_wkt, _MILES_TO_M, point_wkt, point_wkt, radius_m, nearby_lakes_limit),
        )
        near_rows = cur.fetchall()

    return on_water_rows + list(near_rows)


def query_single(
    pool: Any,
    lat: float,
    lon: float,
    lake_radius_miles: float = 1.0,
    nearby_lakes_limit: int = 10,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]], List[Dict[str, Any]]]:
    """Run admin + protected + water queries in parallel; return (admin_hierarchy, protected_areas, nearby_lakes)."""
    conn1 = pool.getconn()
    conn2 = pool.getconn()
    conn3 = pool.getconn()
    try:
        with ThreadPoolExecutor(max_workers=3) as ex:
            f_admin = ex.submit(_run_admin_single, conn1, lat, lon)
            f_protected = ex.submit(_run_protected_single, conn2, lat, lon)
            f_water = ex.submit(
                _run_water_single, conn3, lat, lon, lake_radius_miles, nearby_lakes_limit
            )
            wait([f_admin, f_protected, f_water])
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
            water_rows = f_water.result()
        return (
            _build_admin_hierarchy(admin_rows),
            _build_protected_list(protected_rows),
            _build_nearby_lakes(water_rows),
        )
    finally:
        conn1.rollback()
        conn2.rollback()
        conn3.rollback()
        pool.putconn(conn1)
        pool.putconn(conn2)
        pool.putconn(conn3)


def _run_admin_batch(
    conn: Any,
    indices: List[int],
    lons: List[float],
    lats: List[float],
) -> List[Tuple[int, Any, Any, Any, Any]]:
    """Returns (point_idx, osm_id, admin_level, name, tags)."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            )
            SELECT p.point_idx, a.osm_id, a.admin_level, a.name, a.tags
            FROM p
            JOIN {SCHEMA}.admin_areas a
                ON public.ST_Contains(a.geom, public.ST_SetSRID(public.ST_GeomFromText(('POINT(' || p.lon::text || ' ' || p.lat::text || ')')::text), 4326))
            ORDER BY p.point_idx, a.admin_level
            """,
            (indices, lons, lats),
        )
        return cur.fetchall()


# Max protected areas returned per point in batch (avoids unbounded result sets in dense areas)
_PROTECTED_BATCH_LIMIT_PER_POINT = 100


def _run_protected_batch(
    conn: Any,
    indices: List[int],
    lons: List[float],
    lats: List[float],
) -> List[Tuple[int, Any, Any, Any]]:
    """Returns (point_idx, osm_id, name, tags). Limited per point for scale."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            ),
            ranked AS (
                SELECT p.point_idx, a.osm_id, a.name, a.tags,
                       ROW_NUMBER() OVER (PARTITION BY p.point_idx ORDER BY a.osm_id) AS rn
                FROM p
                JOIN {SCHEMA}.protected_areas a
                    ON public.ST_Contains(a.geom, public.ST_SetSRID(public.ST_GeomFromText(('POINT(' || p.lon::text || ' ' || p.lat::text || ')')::text), 4326))
            )
            SELECT point_idx, osm_id, name, tags
            FROM ranked
            WHERE rn <= %s
            ORDER BY point_idx, rn
            """,
            (indices, lons, lats, _PROTECTED_BATCH_LIMIT_PER_POINT),
        )
        return cur.fetchall()


def _run_water_batch(
    conn: Any,
    indices: List[int],
    lons: List[float],
    lats: List[float],
    lake_radius_miles: float,
    nearby_lakes_limit: int,
) -> Dict[int, List[Tuple[Any, ...]]]:
    """Returns dict point_idx -> list of (name, water_type, distance_miles, on_water). Limits per point in SQL to avoid huge result sets."""
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
                       CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                            ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / %s END AS distance_miles,
                       public.ST_Contains(w.geom, pt.geom) AS on_water,
                       ROW_NUMBER() OVER (
                           PARTITION BY pt.point_idx
                           ORDER BY public.ST_Contains(w.geom, pt.geom) DESC NULLS LAST,
                                    (CASE WHEN public.ST_Contains(w.geom, pt.geom) THEN 0.0
                                          ELSE public.ST_Distance(public.geography(w.geom), public.geography(pt.geom)) / %s END)
                       ) AS rn
                FROM pt
                JOIN {SCHEMA}.water_bodies w
                     ON public.ST_Contains(w.geom, pt.geom)
                     OR (NOT public.ST_Contains(w.geom, pt.geom)
                         AND public.ST_DWithin(public.geography(w.geom), public.geography(pt.geom), %s))
            )
            SELECT point_idx, name, water_type, distance_miles, on_water
            FROM matches
            WHERE rn <= %s
            ORDER BY point_idx, rn
            """,
            (indices, lons, lats, _MILES_TO_M, _MILES_TO_M, radius_m, nearby_lakes_limit),
        )
        rows = cur.fetchall()

    # ROW_NUMBER in SQL already limited to nearby_lakes_limit per point; preserve order per point
    by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in rows:
        idx = row[0]
        if idx not in by_idx:
            by_idx[idx] = []
        by_idx[idx].append(row[1:])  # (name, water_type, distance_miles, on_water)
    return by_idx


def query_batch(
    pool: Any,
    points: List[Tuple[float, float]],
    lake_radius_miles: float = 1.0,
    nearby_lakes_limit: int = 10,
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
            f_admin = ex.submit(_run_admin_batch, conn1, indices, lons, lats)
            f_protected = ex.submit(_run_protected_batch, conn2, indices, lons, lats)
            f_water = ex.submit(
                _run_water_batch,
                conn3,
                indices,
                lons,
                lats,
                lake_radius_miles,
                nearby_lakes_limit,
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

    # Group by point_idx
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
            _build_admin_hierarchy(admin_rows_i),
            _build_protected_list(protected_rows_i),
            _build_nearby_lakes(water_rows_i),
        ))
    return results


def check_health(conn: Any) -> Tuple[bool, Optional[str]]:
    """Check DB connectivity and that tables exist. Returns (ok, error_message)."""
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = %s AND table_name = 'admin_areas'
                ) AND EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = %s AND table_name = 'protected_areas'
                ) AND EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = %s AND table_name = 'water_bodies'
                )
                """,
                (SCHEMA, SCHEMA, SCHEMA),
            )
            row = cur.fetchone()
            if not row or not row[0]:
                return False, f"Tables {SCHEMA}.admin_areas, {SCHEMA}.protected_areas or {SCHEMA}.water_bodies not found"
        return True, None
    except Exception as e:
        return False, str(e)


def _extent_from_row(row: Optional[Tuple[Any, ...]]) -> Optional[Dict[str, float]]:
    """Build {min_lon, min_lat, max_lon, max_lat} from (minx, miny, maxx, maxy) or None."""
    if not row or len(row) < 4 or any(v is None for v in row):
        return None
    minx, miny, maxx, maxy = row[0], row[1], row[2], row[3]
    return {
        "min_lon": float(minx),
        "min_lat": float(miny),
        "max_lon": float(maxx),
        "max_lat": float(maxy),
    }


def get_stats(conn: Any) -> Dict[str, Any]:
    """Return database stats: feature counts, geographic extent, admin level breakdown, oldest/newest feature timestamps."""
    stats: Dict[str, Any] = {
        "admin_areas": {"count": 0, "extent": None, "by_admin_level": {}, "oldest_feature": None, "newest_feature": None},
        "protected_areas": {"count": 0, "extent": None, "oldest_feature": None, "newest_feature": None},
        "water_bodies": {"count": 0, "extent": None, "oldest_feature": None, "newest_feature": None},
    }
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.admin_areas")
        row = cur.fetchone()
        if row and row[0] is not None:
            stats["admin_areas"]["count"] = row[0]

        cur.execute(
            f"""
            SELECT public.ST_XMin(e), public.ST_YMin(e), public.ST_XMax(e), public.ST_YMax(e)
            FROM (SELECT public.ST_Extent(geom) AS e FROM {SCHEMA}.admin_areas) _t
            """,
        )
        row = cur.fetchone()
        if row and row[0] is not None:
            stats["admin_areas"]["extent"] = _extent_from_row(tuple(row))

        cur.execute(
            f"""
            SELECT admin_level, COUNT(*)
            FROM {SCHEMA}.admin_areas
            GROUP BY admin_level
            ORDER BY admin_level
            """,
        )
        for row in cur.fetchall():
            if row and len(row) >= 2:
                stats["admin_areas"]["by_admin_level"][int(row[0])] = row[1]

        cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.protected_areas")
        row = cur.fetchone()
        if row and row[0] is not None:
            stats["protected_areas"]["count"] = row[0]

        cur.execute(
            f"""
            SELECT public.ST_XMin(e), public.ST_YMin(e), public.ST_XMax(e), public.ST_YMax(e)
            FROM (SELECT public.ST_Extent(geom) AS e FROM {SCHEMA}.protected_areas) _t
            """,
        )
        row = cur.fetchone()
        if row and row[0] is not None:
            stats["protected_areas"]["extent"] = _extent_from_row(tuple(row))

        # Oldest/newest feature timestamps (require re-import with -x if column missing)
        def _ts_str(val: Any) -> Optional[str]:
            if val is None:
                return None
            if hasattr(val, "isoformat"):
                return val.isoformat()
            return str(val)

        try:
            cur.execute(
                f"SELECT MIN(created), MAX(created) FROM {SCHEMA}.admin_areas",
            )
            row = cur.fetchone()
            if row:
                stats["admin_areas"]["oldest_feature"] = _ts_str(row[0])
                stats["admin_areas"]["newest_feature"] = _ts_str(row[1])
            cur.execute(
                f"SELECT MIN(created), MAX(created) FROM {SCHEMA}.protected_areas",
            )
            row = cur.fetchone()
            if row:
                stats["protected_areas"]["oldest_feature"] = _ts_str(row[0])
                stats["protected_areas"]["newest_feature"] = _ts_str(row[1])
            cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.water_bodies")
            row = cur.fetchone()
            if row and row[0] is not None:
                stats["water_bodies"]["count"] = row[0]
            cur.execute(
                f"""
                SELECT public.ST_XMin(e), public.ST_YMin(e), public.ST_XMax(e), public.ST_YMax(e)
                FROM (SELECT public.ST_Extent(geom) AS e FROM {SCHEMA}.water_bodies) _t
                """,
            )
            row = cur.fetchone()
            if row and row[0] is not None:
                stats["water_bodies"]["extent"] = _extent_from_row(tuple(row))
            cur.execute(
                f"SELECT MIN(created), MAX(created) FROM {SCHEMA}.water_bodies",
            )
            row = cur.fetchone()
            if row:
                stats["water_bodies"]["oldest_feature"] = _ts_str(row[0])
                stats["water_bodies"]["newest_feature"] = _ts_str(row[1])
        except Exception:
            pass
    return stats
