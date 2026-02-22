"""
Flask app for is_in area server.
GET /query?lat=&lon= (single), POST /query (batch), GET /health, GET /stats (DB stats).
"""
import json
import logging
import sys
from typing import Any, Dict, List, Optional, Tuple

from flask import Flask, request, Response
from werkzeug.exceptions import HTTPException

from areas_lib.query import check_health, get_stats, query_single, query_batch

logger = logging.getLogger(__name__)
if not logger.handlers:
    _h = logging.StreamHandler(sys.stderr)
    _h.setFormatter(logging.Formatter("%(levelname)s: %(message)s"))
    logger.addHandler(_h)
    logger.setLevel(logging.DEBUG)

from psycopg_pool import ConnectionPool

from config import (
    CACHE_COORD_DECIMALS,
    CACHE_TTL_SECONDS,
    get_conninfo,
    MAX_BATCH_SIZE,
)

app = Flask(__name__)

_pool: Optional[ConnectionPool] = None
_cache: Optional[Any] = None


def _configure_read_only(conn):
    """Set session to read-only so this connection cannot modify data."""
    conn.execute("SET default_transaction_read_only = on")
    conn.rollback()


def get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        _pool = ConnectionPool(
            get_conninfo(),
            min_size=1,
            max_size=4,
            configure=_configure_read_only,
        )
    return _pool


def get_cache():
    global _cache
    if _cache is None and CACHE_TTL_SECONDS > 0:
        try:
            from cachetools import TTLCache
            _cache = TTLCache(maxsize=10000, ttl=CACHE_TTL_SECONDS)
        except ImportError:
            _cache = None
    return _cache


def _round_coord(x: float) -> float:
    if CACHE_COORD_DECIMALS <= 0:
        return x
    r = 10 ** CACHE_COORD_DECIMALS
    return round(x * r) / r


def _validate_lat_lon(lat: Optional[float], lon: Optional[float]) -> Optional[str]:
    if lat is None or lon is None:
        return "lat and lon are required"
    try:
        lat_f = float(lat)
        lon_f = float(lon)
    except (TypeError, ValueError):
        return "lat and lon must be numbers"
    if not (-90 <= lat_f <= 90):
        return "lat must be between -90 and 90"
    if not (-180 <= lon_f <= 180):
        return "lon must be between -180 and 180"
    return None


def _parse_float_arg(value: Optional[str], default: float, name: str) -> Tuple[float, Optional[str]]:
    """Return (parsed_value, error_message). error_message is None on success."""
    if value is None or value == "":
        return default, None
    try:
        v = float(value)
        if v < 0:
            return default, f"{name} must be non-negative"
        return v, None
    except ValueError:
        return default, f"{name} must be a number"


def _parse_int_arg(value: Optional[str], default: int, name: str) -> Tuple[int, Optional[str]]:
    """Return (parsed_value, error_message). error_message is None on success."""
    if value is None or value == "":
        return default, None
    try:
        v = int(float(value))
        if v < 1:
            return default, f"{name} must be at least 1"
        return v, None
    except (ValueError, TypeError):
        return default, f"{name} must be an integer"


def _make_response(
        admin_hierarchy: Dict[str, Optional[str]],
        protected_areas: List[Dict[str, str]],
        nearby_lakes: List[Dict[str, Any]],
) -> Dict[str, Any]:
    return {
        "admin_hierarchy": admin_hierarchy,
        "protected_areas": protected_areas,
        "nearby_lakes": nearby_lakes,
    }


@app.route("/health")
def health():
    """Check DB connectivity and that is_in tables exist."""
    try:
        pool = get_pool()
        conn = pool.getconn()
        try:
            ok, err = check_health(conn)
            if not ok:
                return Response(
                    json.dumps({"status": "unhealthy", "error": err}),
                    status=503,
                    mimetype="application/json",
                )
            return {"status": "ok"}
        finally:
            conn.rollback()
            pool.putconn(conn)
    except Exception as e:
        logger.exception("health check failed")
        return Response(
            json.dumps({"status": "unhealthy", "error": str(e)}),
            status=503,
            mimetype="application/json",
        )


@app.route("/stats")
def stats():
    """Return database stats: feature counts, geographic extent, admin level breakdown."""
    try:
        pool = get_pool()
        conn = pool.getconn()
        try:
            return get_stats(conn)
        finally:
            conn.rollback()
            pool.putconn(conn)
    except Exception as e:
        logger.exception("stats failed")
        return Response(
            json.dumps({"error": str(e)}),
            status=500,
            mimetype="application/json",
        )


@app.route("/query", methods=["GET"])
def get_query():
    """Single-point query: GET /query?lat=40.34&lon=-105.68. Optional: lake_radius_miles (default 1), nearby_lakes_limit (default 10)."""
    lat = request.args.get("lat")
    lon = request.args.get("lon")
    err = _validate_lat_lon(lat, lon)
    if err:
        return Response(
            json.dumps({"error": err}),
            status=400,
            mimetype="application/json",
        )
    lat_f = float(lat)
    lon_f = float(lon)

    lake_radius_miles, err = _parse_float_arg(request.args.get("lake_radius_miles"), 1.0, "lake_radius_miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")
    nearby_lakes_limit, err = _parse_int_arg(request.args.get("nearby_lakes_limit"), 10, "nearby_lakes_limit")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")

    cache = get_cache()
    if cache is not None:
        key = (_round_coord(lat_f), _round_coord(lon_f), lake_radius_miles, nearby_lakes_limit)
        if key in cache:
            return cache[key]

    try:
        pool = get_pool()
        admin_hierarchy, protected_areas, nearby_lakes = query_single(
            pool, lat_f, lon_f,
            lake_radius_miles=lake_radius_miles,
            nearby_lakes_limit=nearby_lakes_limit,
        )
        out = _make_response(admin_hierarchy, protected_areas, nearby_lakes)
        if cache is not None:
            cache[key] = out
        return out
    except Exception as e:
        logger.exception("GET /query failed")
        return Response(
            json.dumps({"error": str(e)}),
            status=500,
            mimetype="application/json",
        )


@app.route("/query", methods=["POST"])
def post_query():
    """Batch query: POST /query with body {"points": [[lat, lon], ...]}. Optional query args or body keys: lake_radius_miles (default 1), nearby_lakes_limit (default 10)."""
    if not request.is_json:
        return Response(
            json.dumps({"error": "Content-Type must be application/json"}),
            status=400,
            mimetype="application/json",
        )
    data = request.get_json()
    if not data or "points" not in data:
        return Response(
            json.dumps({"error": "body must contain 'points' array"}),
            status=400,
            mimetype="application/json",
        )
    raw = data["points"]
    if not isinstance(raw, list):
        return Response(
            json.dumps({"error": "'points' must be an array"}),
            status=400,
            mimetype="application/json",
        )
    if len(raw) > MAX_BATCH_SIZE:
        return Response(
            json.dumps({"error": f"batch size exceeds maximum {MAX_BATCH_SIZE}"}),
            status=400,
            mimetype="application/json",
        )

    points: List[Tuple[float, float]] = []
    for i, p in enumerate(raw):
        if not isinstance(p, (list, tuple)) or len(p) < 2:
            return Response(
                json.dumps({"error": f"point at index {i} must be [lat, lon]"}),
                status=400,
                mimetype="application/json",
            )
        err = _validate_lat_lon(p[0], p[1])
        if err:
            return Response(
                json.dumps({"error": f"point at index {i}: {err}"}),
                status=400,
                mimetype="application/json",
            )
        points.append((float(p[0]), float(p[1])))

    # Optional params: query args override body keys
    lake_radius_arg = request.args.get("lake_radius_miles")
    if lake_radius_arg is None and data and "lake_radius_miles" in data:
        lake_radius_arg = str(data["lake_radius_miles"])
    nearby_limit_arg = request.args.get("nearby_lakes_limit")
    if nearby_limit_arg is None and data and "nearby_lakes_limit" in data:
        nearby_limit_arg = str(data["nearby_lakes_limit"])
    lake_radius_miles, err = _parse_float_arg(lake_radius_arg, 1.0, "lake_radius_miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")
    nearby_lakes_limit, err = _parse_int_arg(nearby_limit_arg, 10, "nearby_lakes_limit")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")

    try:
        pool = get_pool()
        results = query_batch(
            pool, points,
            lake_radius_miles=lake_radius_miles,
            nearby_lakes_limit=nearby_lakes_limit,
        )
        out = {
            "results": [
                _make_response(admin_hierarchy, protected_areas, nearby_lakes)
                for admin_hierarchy, protected_areas, nearby_lakes in results
            ]
        }
        return out
    except Exception as e:
        logger.exception("POST /query failed")
        return Response(
            json.dumps({"error": str(e)}),
            status=500,
            mimetype="application/json",
        )


@app.errorhandler(Exception)
def handle_exception(exc):
    """Log full traceback for uncaught server errors; pass through 404/405 etc. without logging."""
    if isinstance(exc, HTTPException):
        return exc.get_response()
    logger.exception("uncaught exception")
    return Response(
        json.dumps({"error": str(exc)}),
        status=500,
        mimetype="application/json",
    )


def create_app() -> Flask:
    return app


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
