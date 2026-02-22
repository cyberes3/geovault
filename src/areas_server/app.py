"""
Flask app for is_in area server.
GET /query?lat=&lon= (single), POST /query (batch), GET /health, GET /stats (DB stats).
"""
import json
import logging
import sys
import traceback
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
    POOL_MAX_SIZE,
    REDIS_URL,
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
            max_size=POOL_MAX_SIZE,
            configure=_configure_read_only,
        )
    return _pool


_CACHE_KEY_PREFIX = "areas:query:"


def _cache_key_tuple(lat: float, lon: float, lake_radius_miles: float, ocean_radius_miles: float) -> str:
    """Serializable Redis key from query params."""
    return f"{_CACHE_KEY_PREFIX}{lat}:{lon}:{lake_radius_miles}:{ocean_radius_miles}"


class _RedisResponseCache:
    """Redis-backed cache for GET /query responses; shared across Gunicorn workers."""

    def __init__(self, redis_url: str, ttl_seconds: int):
        import redis
        self._client = redis.Redis.from_url(redis_url, decode_responses=True)
        self._client.ping()
        self._ttl = ttl_seconds

    def __contains__(self, key: tuple) -> bool:
        k = _cache_key_tuple(key[0], key[1], key[2], key[3])
        return self._client.exists(k) > 0

    def __getitem__(self, key: tuple):
        k = _cache_key_tuple(key[0], key[1], key[2], key[3])
        raw = self._client.get(k)
        if raw is None:
            raise KeyError(key)
        return json.loads(raw)

    def __setitem__(self, key: tuple, value: dict) -> None:
        k = _cache_key_tuple(key[0], key[1], key[2], key[3])
        self._client.setex(k, self._ttl, json.dumps(value))


def get_cache():
    global _cache
    if _cache is None and CACHE_TTL_SECONDS > 0:
        try:
            _cache = _RedisResponseCache(REDIS_URL, CACHE_TTL_SECONDS)
        except Exception as e:
            logger.warning("Areas server Redis cache disabled: %s", e)
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
        ocean: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "admin_hierarchy": admin_hierarchy,
        "protected_areas": protected_areas,
        "nearby_lakes": nearby_lakes,
        "ocean": ocean,
    }


def _error_response_with_traceback(exc: BaseException, status: int = 500) -> Response:
    """Print traceback to stderr and return JSON response with error and traceback."""
    tb_str = traceback.format_exc()
    traceback.print_exc(file=sys.stderr)
    return Response(
        json.dumps({"error": str(exc), "traceback": tb_str}),
        status=status,
        mimetype="application/json",
    )


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
        tb_str = traceback.format_exc()
        traceback.print_exc(file=sys.stderr)
        return Response(
            json.dumps({"status": "unhealthy", "error": str(e), "traceback": tb_str}),
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
        return _error_response_with_traceback(e)


@app.route("/query", methods=["GET"])
def get_query():
    """Single-point query: GET /query?lat=40.34&lon=-105.68. Optional: lake-radius-miles (default 1), ocean-radius-miles (default 1)."""
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

    lake_radius_miles, err = _parse_float_arg(request.args.get("lake-radius-miles"), 1.0, "lake-radius-miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")
    ocean_radius_miles, err = _parse_float_arg(request.args.get("ocean-radius-miles"), 1.0, "ocean-radius-miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")

    cache = get_cache()
    if cache is not None:
        key = (_round_coord(lat_f), _round_coord(lon_f), lake_radius_miles, ocean_radius_miles)
        if key in cache:
            return cache[key]

    try:
        pool = get_pool()
        admin_hierarchy, protected_areas, nearby_lakes, ocean = query_single(
            pool, lat_f, lon_f,
            lake_radius_miles=lake_radius_miles,
            ocean_radius_miles=ocean_radius_miles,
        )
        out = _make_response(admin_hierarchy, protected_areas, nearby_lakes, ocean)
        if cache is not None:
            cache[key] = out
        return out
    except Exception as e:
        return _error_response_with_traceback(e)


@app.route("/query", methods=["POST"])
def post_query():
    """Batch query: POST /query with body {"points": [[lat, lon], ...]}. Optional: lake-radius-miles (default 1), ocean-radius-miles (default 1)."""
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
    lake_radius_arg = request.args.get("lake-radius-miles")
    if lake_radius_arg is None and data and "lake-radius-miles" in data:
        lake_radius_arg = str(data["lake-radius-miles"])
    lake_radius_miles, err = _parse_float_arg(lake_radius_arg, 1.0, "lake-radius-miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")
    ocean_radius_arg = request.args.get("ocean-radius-miles")
    if ocean_radius_arg is None and data and "ocean-radius-miles" in data:
        ocean_radius_arg = str(data["ocean-radius-miles"])
    ocean_radius_miles, err = _parse_float_arg(ocean_radius_arg, 1.0, "ocean-radius-miles")
    if err:
        return Response(json.dumps({"error": err}), status=400, mimetype="application/json")

    try:
        pool = get_pool()
        results = query_batch(
            pool, points,
            lake_radius_miles=lake_radius_miles,
            ocean_radius_miles=ocean_radius_miles,
        )
        out = {
            "results": [
                _make_response(admin_hierarchy, protected_areas, nearby_lakes, ocean)
                for admin_hierarchy, protected_areas, nearby_lakes, ocean in results
            ]
        }
        return out
    except Exception as e:
        return _error_response_with_traceback(e)


@app.errorhandler(Exception)
def handle_exception(exc):
    """Print traceback to console and return error + traceback in response for uncaught errors."""
    if isinstance(exc, HTTPException):
        return exc.get_response()
    return _error_response_with_traceback(exc)


def create_app() -> Flask:
    return app


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
