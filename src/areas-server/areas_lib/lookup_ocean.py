"""Ocean name lookup from ocean_regions (sub-regions) and oceans (main oceans) tables."""
from typing import Any, Dict, List, Optional

from config import SCHEMA
from .lookup_common import get_table_stats, normalize_name_for_response

TABLE_OCEAN_REGIONS = "ocean_regions"
TABLE_OCEANS = "oceans"

_MAX_OCEAN_NAMES = 2


def _merge_ocean_names(region: Optional[str], ocean: Optional[str]) -> List[str]:
    """Return list of 0–2 names: region first, then ocean if different. Cap at 2. Names normalized (underscores to spaces)."""
    r = normalize_name_for_response(region) if region else ""
    o = normalize_name_for_response(ocean) if ocean else ""
    out: List[str] = []
    if r:
        out.append(r)
    if o and o != r and len(out) < _MAX_OCEAN_NAMES:
        out.append(o)
    return out[:_MAX_OCEAN_NAMES]


def sql_fragment(*, batch: bool, table_name: str, layer: str) -> str:
    """UNION ALL branch for one ocean table. Geography radius via %(ocean_radius_m)s."""
    match = f"""public.ST_Contains(o.geom, pt.geom)
               OR public.ST_DWithin(public.geography(o.geom), public.geography(pt.geom), %(ocean_radius_m)s)"""
    order = """public.ST_Contains(o.geom, pt.geom) DESC NULLS LAST,
                     public.ST_Distance(public.geography(o.geom), public.geography(pt.geom))"""
    if batch:
        return f"""
        SELECT pt.point_idx, '{layer}' AS layer, sub.payload
        FROM pt
        LEFT JOIN LATERAL (
            SELECT jsonb_build_object('name', o.name) AS payload
            FROM {SCHEMA}.{table_name} o
            WHERE {match}
            ORDER BY {order}
            LIMIT 1
        ) sub ON true
        WHERE sub.payload IS NOT NULL
        """
    return f"""
        (SELECT '{layer}' AS layer, jsonb_build_object('name', sub.name) AS payload
        FROM (
            SELECT o.name FROM {SCHEMA}.{table_name} o, pt
            WHERE {match}
            ORDER BY {order}
            LIMIT 1
        ) sub)
        """


def get_ocean_regions_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for ocean_regions: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_OCEAN_REGIONS, include_created=False)


def get_oceans_stats(conn: Any) -> Dict[str, Any]:
    """Return stats for oceans: count, extent. Fails hard if table is missing."""
    return get_table_stats(conn, SCHEMA, TABLE_OCEANS, include_created=False)
