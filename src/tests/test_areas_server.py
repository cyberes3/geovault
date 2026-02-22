"""
Tests for the standalone is_in area server (Flask + PostGIS).
All tests hit the real server at reverse_geocoding.areas_server.api_url (AREAS_SERVER_URL).
Validation and error-path tests use the in-process client with mocks where needed.

Uses urllib for HTTP so real-server tests are not affected by conftest's requests mocks.
"""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Areas server uses bare imports (config, query); add its dir to path so we can import/patch app
_areas_server_dir = Path(__file__).resolve().parent.parent / "areas_server"
if str(_areas_server_dir) not in sys.path:
    sys.path.insert(0, str(_areas_server_dir))


def _areas_server_base_url():
    from django.conf import settings
    url = (getattr(settings, "AREAS_SERVER_URL", None) or "").strip()
    return url.rstrip("/") if url else ""


def _http_get(url, timeout=5):
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.getcode()
            body = resp.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        status = e.code
        try:
            data = json.loads(body) if body.strip().startswith("{") else None
        except json.JSONDecodeError:
            data = None
        return status, body, data
    try:
        data = json.loads(body) if body else None
    except json.JSONDecodeError:
        data = None
    return status, body, data


def _http_post(url, data_bytes=None, content_type="application/json", timeout=5):
    req = urllib.request.Request(url, data=data_bytes, method="POST")
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.getcode()
            body = resp.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode() if e.fp else ""
        try:
            data = json.loads(body) if body.strip().startswith("{") else None
        except json.JSONDecodeError:
            data = None
        return e.code, body, data
    try:
        data = json.loads(body) if body else None
    except json.JSONDecodeError:
        data = None
    return status, body, data


@pytest.fixture
def areas_server_url():
    """Base URL of the areas server from settings (reverse_geocoding.areas_server.api_url)."""
    url = _areas_server_base_url()
    if not url:
        pytest.fail("AREAS_SERVER_URL not set")
    return url


@pytest.fixture
def require_areas_server(areas_server_url):
    """Require the areas server to be up; fail test if /health is not ok."""
    try:
        status, body, data = _http_get(areas_server_url + "/health")
    except OSError as e:
        pytest.fail(f"areas server not reachable: {e}")
    if status != 200:
        pytest.fail(f"areas server /health returned {status}")
    if not data or data.get("status") != "ok":
        pytest.fail("areas server unhealthy")
    return areas_server_url


@pytest.fixture
def client():
    """In-process test client (used only for tests that mock server internals)."""
    from app import app as is_in_app
    is_in_app.config["TESTING"] = True
    return is_in_app.test_client()


@pytest.fixture
def mock_pool():
    pool = MagicMock()
    conn = MagicMock()
    pool.getconn.return_value = conn
    pool.putconn.side_effect = lambda c: None
    return pool


class TestHealth:
    def test_health_ok(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_get(url + "/health")
        assert status == 200
        assert data and data.get("status") == "ok"

    def test_health_unhealthy(self, client, mock_pool):
        """Server returns 503 when DB health check fails (mocked)."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.check_health", return_value=(False, "no tables")):
            r = client.get("/health")
        assert r.status_code == 503
        assert r.json["status"] == "unhealthy"
        assert "no tables" in r.json["error"]


class TestStats:
    def test_stats_ok(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_get(url + "/stats")
        assert status == 200
        assert data
        assert "admin_areas" in data
        assert "protected_areas" in data
        assert "count" in data["admin_areas"]
        assert "count" in data["protected_areas"]


class TestQueryGet:
    def test_query_get_missing_params(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_get(url + "/query")
        assert status == 400
        assert "required" in ((data or {}).get("error") or "").lower()

        status, _, _ = _http_get(url + "/query?lat=40")
        assert status == 400

    def test_query_get_invalid_coords(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_get(url + "/query?lat=x&lon=-105")
        assert status == 400
        assert "number" in ((data or {}).get("error") or "").lower()

        status, _, data = _http_get(url + "/query?lat=40&lon=200")
        assert status == 400
        assert "180" in ((data or {}).get("error") or "")

        status, _, data = _http_get(url + "/query?lat=91&lon=-105")
        assert status == 400
        assert "90" in ((data or {}).get("error") or "")

    def test_query_get_ok(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_get(url + "/query?lat=40.34&lon=-105.68")
        assert status == 200
        assert data
        assert "admin_hierarchy" in data
        assert "protected_areas" in data
        assert isinstance(data["admin_hierarchy"], dict)
        assert isinstance(data["protected_areas"], list)


class TestQueryPost:
    def test_query_post_not_json(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_post(
            url + "/query",
            data_bytes=b"not json",
            content_type="text/plain",
        )
        assert status == 400
        assert "application/json" in ((data or {}).get("error") or "").lower()

    def test_query_post_no_points(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_post(url + "/query", data_bytes=b"{}")
        assert status == 400
        assert "points" in ((data or {}).get("error") or "").lower()

    def test_query_post_points_not_list(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_post(url + "/query", data_bytes=b'{"points": "x"}')
        assert status == 400
        assert "array" in ((data or {}).get("error") or "").lower()

    def test_query_post_invalid_point(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_post(url + "/query", data_bytes=b'{"points": [[40, "x"]]}')
        assert status == 400
        assert "index" in ((data or {}).get("error") or "").lower()

    def test_query_post_ok(self, require_areas_server):
        url = require_areas_server
        status, _, data = _http_post(
            url + "/query",
            data_bytes=json.dumps({"points": [[40.34, -105.68], [37.77, -122.42]]}).encode(),
        )
        assert status == 200
        assert data
        assert "results" in data
        assert isinstance(data["results"], list)
        assert len(data["results"]) == 2
        for item in data["results"]:
            assert "admin_hierarchy" in item
            assert "protected_areas" in item
