"""Major river/canal lookup from waterways.major_waterways (osm-lump-ways output)."""
from typing import Any, Dict, Optional

from .lookup_common import get_table_stats

WATERWAYS_SCHEMA = "waterways"
TABLE_NAME = "major_waterways"

# Default: 300 feet in miles (used when waterway-radius-miles not provided)
DEFAULT_WATERWAY_RADIUS_MILES = 300 / 5280.0


def get_waterway_stats(conn: Any) -> Optional[Dict[str, Any]]:
    """Return stats for major_waterways if table exists; else None."""
    try:
        return get_table_stats(conn, WATERWAYS_SCHEMA, TABLE_NAME, include_created=False)
    except Exception:
        return None


def table_exists(conn: Any) -> bool:
    """Return True if waterways.major_waterways exists."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = %s AND table_name = %s
            """,
            (WATERWAYS_SCHEMA, TABLE_NAME),
        )
        return cur.fetchone() is not None
