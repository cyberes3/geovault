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


def _run_admin_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, admin_level, name, tags
            FROM {SCHEMA}.admin_areas
            WHERE ST_Contains(geom, ST_SetSRID(ST_MakePoint(%s, %s), 4326))
            ORDER BY admin_level ASC
            """,
            (lon, lat),
        )
        return cur.fetchall()


def _run_protected_single(conn: Any, lat: float, lon: float) -> List[Tuple[Any, ...]]:
    with conn.cursor() as cur:
        cur.execute(
            f"""
            SELECT osm_id, name, tags
            FROM {SCHEMA}.protected_areas
            WHERE ST_Contains(geom, ST_SetSRID(ST_MakePoint(%s, %s), 4326))
            LIMIT 100
            """,
            (lon, lat),
        )
        return cur.fetchall()


def query_single(
    pool: Any,
    lat: float,
    lon: float,
) -> Tuple[Dict[str, Optional[str]], List[Dict[str, str]]]:
    """Run admin + protected queries in parallel; return (admin_hierarchy, protected_areas)."""
    conn1 = pool.getconn()
    conn2 = pool.getconn()
    try:
        with ThreadPoolExecutor(max_workers=2) as ex:
            f_admin = ex.submit(_run_admin_single, conn1, lat, lon)
            f_protected = ex.submit(_run_protected_single, conn2, lat, lon)
            wait([f_admin, f_protected])
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
        return (
            _build_admin_hierarchy(admin_rows),
            _build_protected_list(protected_rows),
        )
    finally:
        pool.putconn(conn1)
        pool.putconn(conn2)


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
                ON ST_Contains(a.geom, ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326))
            ORDER BY p.point_idx, a.admin_level
            """,
            (indices, lons, lats),
        )
        return cur.fetchall()


def _run_protected_batch(
    conn: Any,
    indices: List[int],
    lons: List[float],
    lats: List[float],
) -> List[Tuple[int, Any, Any, Any]]:
    """Returns (point_idx, osm_id, name, tags)."""
    with conn.cursor() as cur:
        cur.execute(
            f"""
            WITH p AS (
                SELECT * FROM unnest(%s::bigint[], %s::double precision[], %s::double precision[])
                AS t(point_idx, lon, lat)
            )
            SELECT p.point_idx, a.osm_id, a.name, a.tags
            FROM p
            JOIN {SCHEMA}.protected_areas a
                ON ST_Contains(a.geom, ST_SetSRID(ST_MakePoint(p.lon, p.lat), 4326))
            ORDER BY p.point_idx
            """,
            (indices, lons, lats),
        )
        return cur.fetchall()


def query_batch(
    pool: Any,
    points: List[Tuple[float, float]],
) -> List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]]]]:
    """Run two batch queries in parallel; return list of (admin_hierarchy, protected_areas) in order."""
    if not points:
        return []
    n = len(points)
    indices = list(range(n))
    lons = [p[1] for p in points]
    lats = [p[0] for p in points]

    conn1 = pool.getconn()
    conn2 = pool.getconn()
    try:
        with ThreadPoolExecutor(max_workers=2) as ex:
            f_admin = ex.submit(_run_admin_batch, conn1, indices, lons, lats)
            f_protected = ex.submit(_run_protected_batch, conn2, indices, lons, lats)
            wait([f_admin, f_protected])
            admin_rows = f_admin.result()
            protected_rows = f_protected.result()
    finally:
        pool.putconn(conn1)
        pool.putconn(conn2)

    # Group by point_idx
    admin_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in admin_rows:
        idx = row[0]
        admin_by_idx.setdefault(idx, []).append(row[1:])

    protected_by_idx: Dict[int, List[Tuple[Any, ...]]] = {}
    for row in protected_rows:
        idx = row[0]
        protected_by_idx.setdefault(idx, []).append(row[1:])

    results: List[Tuple[Dict[str, Optional[str]], List[Dict[str, str]]]] = []
    for i in range(n):
        admin_rows_i = admin_by_idx.get(i, [])
        protected_rows_i = protected_by_idx.get(i, [])
        results.append((
            _build_admin_hierarchy(admin_rows_i),
            _build_protected_list(protected_rows_i),
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
                )
                """,
                (SCHEMA, SCHEMA),
            )
            row = cur.fetchone()
            if not row or not row[0]:
                return False, f"Tables {SCHEMA}.admin_areas or {SCHEMA}.protected_areas not found"
        return True, None
    except Exception as e:
        return False, str(e)
