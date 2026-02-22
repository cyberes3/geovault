"""
Flask app for is_in area server.
GET /is_in?lat=&lon= (single), POST /is_in (batch), GET /health.
"""
import json
from typing import Any, Dict, List, Optional, Tuple

from flask import Flask, request, Response

import psycopg
from psycopg.pool import ThreadedConnectionPool

from config import (
    CACHE_COORD_DECIMALS,
    CACHE_TTL_SECONDS,
    get_conninfo,
    MAX_BATCH_SIZE,
)
from query import check_health, query_batch, query_single

app = Flask(__name__)

_pool: Optional[ThreadedConnectionPool] = None
_cache: Optional[Any] = None


def get_pool() -> ThreadedConnectionPool:
    global _pool
    if _pool is None:
        _pool = ThreadedConnectionPool(
            min_size=1,
            max_size=4,
            kwargs={"conninfo": get_conninfo()},
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


def _make_response(
    admin_hierarchy: Dict[str, Optional[str]],
    protected_areas: List[Dict[str, str]],
) -> Dict[str, Any]:
    return {
        "admin_hierarchy": admin_hierarchy,
        "protected_areas": protected_areas,
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
            pool.putconn(conn)
    except Exception as e:
        return Response(
            json.dumps({"status": "unhealthy", "error": str(e)}),
            status=503,
            mimetype="application/json",
        )


@app.route("/is_in", methods=["GET"])
def is_in_single():
    """Single-point query: GET /is_in?lat=40.34&lon=-105.68"""
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

    cache = get_cache()
    if cache is not None:
        key = (_round_coord(lat_f), _round_coord(lon_f))
        if key in cache:
            return cache[key]

    try:
        pool = get_pool()
        admin_hierarchy, protected_areas = query_single(pool, lat_f, lon_f)
        out = _make_response(admin_hierarchy, protected_areas)
        if cache is not None:
            cache[key] = out
        return out
    except Exception as e:
        return Response(
            json.dumps({"error": str(e)}),
            status=500,
            mimetype="application/json",
        )


@app.route("/is_in", methods=["POST"])
def is_in_batch():
    """Batch query: POST /is_in with body {"points": [[lat, lon], ...]}"""
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

    try:
        pool = get_pool()
        results = query_batch(pool, points)
        out = {
            "results": [
                _make_response(admin_hierarchy, protected_areas)
                for admin_hierarchy, protected_areas in results
            ]
        }
        return out
    except Exception as e:
        return Response(
            json.dumps({"error": str(e)}),
            status=500,
            mimetype="application/json",
        )


def create_app() -> Flask:
    return app


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
