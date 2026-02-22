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

from areas_lib import lookup_places, lookup_protected_areas, lookup_water


def _areas_server_base_url():
    from website.settings_utils import get_setting
    url = (get_setting("AREAS_SERVER_URL") or "").strip()
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
        assert "ocean" in data
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
            assert "ocean" in item


# Park County, CO: admin has no city; nearest place node is Fairplay (town). Closer point than ~3 mi.
PARK_COUNTY_LAT, PARK_COUNTY_LON = 39.22337887866515, -105.94799963185382


class TestNearbyCityPlaceNodes:
    """Test that when admin has no city, the closest place node within city-radius-miles is used as city."""

    def test_park_county_point_city_fairplay_with_city_radius(self, require_areas_server):
        """Park County point gets city=Fairplay when city-radius-miles >= 3 (nearest place node)."""
        url = require_areas_server
        status, _, data = _http_get(
            url + "/query?lat={}&lon={}&city-radius-miles=3".format(PARK_COUNTY_LAT, PARK_COUNTY_LON)
        )
        assert status == 200, (data or {})
        assert data and "admin_hierarchy" in data
        admin = data["admin_hierarchy"]
        assert admin.get("county") == "Park County"
        assert admin.get("city") == "Fairplay", (
            "Expected city=Fairplay from nearest place node; ensure place_nodes table is populated (re-import with flex config)."
        )

    def test_park_county_point_city_fairplay_with_default_city_radius(self, require_areas_server):
        """Park County point with default city-radius-miles (3) gets city=Fairplay."""
        url = require_areas_server
        status, _, data = _http_get(
            url + "/query?lat={}&lon={}".format(PARK_COUNTY_LAT, PARK_COUNTY_LON)
        )
        assert status == 200
        assert data and data["admin_hierarchy"].get("county") == "Park County"
        assert data["admin_hierarchy"].get("city") == "Fairplay"

    def test_park_county_point_city_null_when_city_radius_zero(self, require_areas_server):
        """Park County point with city-radius-miles=0 has no city (place lookup disabled)."""
        url = require_areas_server
        status, _, data = _http_get(
            url + "/query?lat={}&lon={}&city-radius-miles=0".format(PARK_COUNTY_LAT, PARK_COUNTY_LON)
        )
        assert status == 200
        assert data and data["admin_hierarchy"].get("county") == "Park County"
        assert data["admin_hierarchy"].get("city") is None

    def test_park_county_point_batch_city_fairplay(self, require_areas_server):
        """POST /query with Park County point and city-radius-miles=3 returns city=Fairplay."""
        url = require_areas_server
        status, _, data = _http_post(
            url + "/query",
            data_bytes=json.dumps({
                "points": [[PARK_COUNTY_LAT, PARK_COUNTY_LON]],
                "city-radius-miles": 3,
            }).encode(),
        )
        assert status == 200
        assert data and "results" in data and len(data["results"]) == 1
        admin = data["results"][0]["admin_hierarchy"]
        assert admin.get("county") == "Park County"
        assert admin.get("city") == "Fairplay"


class TestQueryArgs:
    """Test query arg parsing and forwarding (in-process client, mocked pool/query)."""

    def test_get_query_passes_lake_ocean_and_city_radius_to_query_single(self, client, mock_pool):
        """GET /query with lake-radius-miles, ocean-radius-miles, city-radius-miles forwards them to query_single."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_single") as mock_query:
            mock_query.return_value = (
                {"country": "US", "state": None, "county": None, "city": None},
                [],
                [],
                None,
            )
            r = client.get(
                "/query?lat=40.34&lon=-105.68&lake-radius-miles=2.5&ocean-radius-miles=0.5&city-radius-miles=5"
            )
        assert r.status_code == 200
        mock_query.assert_called_once()
        call_kw = mock_query.call_args[1]
        assert call_kw["lake_radius_miles"] == 2.5
        assert call_kw["ocean_radius_miles"] == 0.5
        assert call_kw["city_radius_miles"] == 5.0

    def test_get_query_defaults_lake_ocean_and_city_radius(self, client, mock_pool):
        """GET /query without optional args uses default 1.0 for lake/ocean, 3.0 for city radius."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_single") as mock_query:
            mock_query.return_value = (
                {"country": "US", "state": None, "county": None, "city": None},
                [],
                [],
                None,
            )
            r = client.get("/query?lat=40.34&lon=-105.68")
        assert r.status_code == 200
        call_kw = mock_query.call_args[1]
        assert call_kw["lake_radius_miles"] == 1.0
        assert call_kw["ocean_radius_miles"] == 1.0
        assert call_kw["city_radius_miles"] == 3.0

    def test_get_query_invalid_lake_radius_miles_returns_400(self, client):
        """GET /query with negative lake-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&lake-radius-miles=-1")
        assert r.status_code == 400
        assert "non-negative" in (r.json or {}).get("error", "").lower()

    def test_get_query_invalid_ocean_radius_miles_returns_400(self, client):
        """GET /query with negative ocean-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&ocean-radius-miles=-0.5")
        assert r.status_code == 400
        assert "non-negative" in (r.json or {}).get("error", "").lower()

    def test_get_query_non_numeric_lake_radius_returns_400(self, client):
        """GET /query with non-numeric lake-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&lake-radius-miles=abc")
        assert r.status_code == 400
        assert "number" in (r.json or {}).get("error", "").lower()

    def test_get_query_non_numeric_ocean_radius_returns_400(self, client):
        """GET /query with non-numeric ocean-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&ocean-radius-miles=xyz")
        assert r.status_code == 400
        assert "number" in (r.json or {}).get("error", "").lower()

    def test_get_query_invalid_city_radius_miles_returns_400(self, client):
        """GET /query with negative city-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&city-radius-miles=-1")
        assert r.status_code == 400
        assert "non-negative" in (r.json or {}).get("error", "").lower()

    def test_get_query_non_numeric_city_radius_returns_400(self, client):
        """GET /query with non-numeric city-radius-miles returns 400."""
        r = client.get("/query?lat=40&lon=-105&city-radius-miles=abc")
        assert r.status_code == 400
        assert "number" in (r.json or {}).get("error", "").lower()

    def test_post_query_passes_city_radius_miles_from_body(self, client, mock_pool):
        """POST /query with city-radius-miles in body forwards to query_batch."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch") as mock_batch:
            mock_batch.return_value = [
                (
                    {"country": "US", "state": None, "county": None, "city": None},
                    [],
                    [],
                    None,
                ),
            ]
            r = client.post(
                "/query",
                data=json.dumps({"points": [[40.34, -105.68]], "city-radius-miles": 4.0}),
                content_type="application/json",
            )
        assert r.status_code == 200
        call_kw = mock_batch.call_args[1]
        assert call_kw["city_radius_miles"] == 4.0

    def test_post_query_passes_lake_and_ocean_radius_from_body(self, client, mock_pool):
        """POST /query with lake-radius-miles and ocean-radius-miles in body forwards to query_batch."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch") as mock_batch:
            mock_batch.return_value = [
                (
                    {"country": "US", "state": None, "county": None, "city": None},
                    [],
                    [],
                    None,
                ),
            ]
            r = client.post(
                "/query",
                data=json.dumps({
                    "points": [[40.34, -105.68]],
                    "lake-radius-miles": 3.0,
                    "ocean-radius-miles": 2.0,
                }),
                content_type="application/json",
            )
        assert r.status_code == 200
        mock_batch.assert_called_once()
        call_kw = mock_batch.call_args[1]
        assert call_kw["lake_radius_miles"] == 3.0
        assert call_kw["ocean_radius_miles"] == 2.0

    def test_post_query_passes_lake_and_ocean_radius_from_query_string(self, client, mock_pool):
        """POST /query with lake-radius-miles and ocean-radius-miles in query string forwards to query_batch."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch") as mock_batch:
            mock_batch.return_value = [
                (
                    {"country": "US", "state": None, "county": None, "city": None},
                    [],
                    [],
                    None,
                ),
            ]
            r = client.post(
                "/query?lake-radius-miles=1.5&ocean-radius-miles=0.25",
                data=json.dumps({"points": [[40.34, -105.68]]}),
                content_type="application/json",
            )
        assert r.status_code == 200
        call_kw = mock_batch.call_args[1]
        assert call_kw["lake_radius_miles"] == 1.5
        assert call_kw["ocean_radius_miles"] == 0.25

    def test_post_query_query_string_overrides_body_radius(self, client, mock_pool):
        """POST /query: query string radius overrides body."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch") as mock_batch:
            mock_batch.return_value = [
                (
                    {"country": "US", "state": None, "county": None, "city": None},
                    [],
                    [],
                    None,
                ),
            ]
            r = client.post(
                "/query?lake-radius-miles=10&ocean-radius-miles=5",
                data=json.dumps({
                    "points": [[40.34, -105.68]],
                    "lake-radius-miles": 1,
                    "ocean-radius-miles": 1,
                }),
                content_type="application/json",
            )
        assert r.status_code == 200
        call_kw = mock_batch.call_args[1]
        assert call_kw["lake_radius_miles"] == 10.0
        assert call_kw["ocean_radius_miles"] == 5.0

    def test_post_query_default_city_radius_miles(self, client, mock_pool):
        """POST /query without city-radius-miles uses default 3.0."""
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch") as mock_batch:
            mock_batch.return_value = [
                (
                    {"country": "US", "state": None, "county": None, "city": None},
                    [],
                    [],
                    None,
                ),
            ]
            r = client.post(
                "/query",
                data=json.dumps({"points": [[40.34, -105.68]]}),
                content_type="application/json",
            )
        assert r.status_code == 200
        assert mock_batch.call_args[1]["city_radius_miles"] == 3.0

    def test_post_query_invalid_ocean_radius_in_body_returns_400(self, client):
        """POST /query with invalid ocean-radius-miles in body returns 400."""
        r = client.post(
            "/query",
            data=json.dumps({"points": [[40, -105]], "ocean-radius-miles": -1}),
            content_type="application/json",
        )
        assert r.status_code == 400
        assert "non-negative" in (r.json or {}).get("error", "").lower()

    def test_post_query_invalid_city_radius_in_body_returns_400(self, client):
        """POST /query with invalid city-radius-miles in body returns 400."""
        r = client.post(
            "/query",
            data=json.dumps({"points": [[40, -105]], "city-radius-miles": -1}),
            content_type="application/json",
        )
        assert r.status_code == 400
        assert "non-negative" in (r.json or {}).get("error", "").lower()


# --- Unit tests: place lookup and query_single/query_batch filling city from nearest place. ---


class TestNearbyPlaceLookup:
    """Unit tests for lookup_places and query layer filling city when admin has none."""

    def test_run_place_single_returns_none_when_radius_zero(self):
        """run_place_single returns None when radius_miles is 0 (no DB call)."""
        conn = MagicMock()
        assert lookup_places.run_place_single(conn, 40.0, -105.0, 0.0) is None
        conn.cursor.assert_not_called()

    def test_run_place_single_returns_closest_name_when_in_radius(self):
        """run_place_single returns closest place name when within radius."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchone.return_value = ("Fairplay",)
        name = lookup_places.run_place_single(conn, PARK_COUNTY_LAT, PARK_COUNTY_LON, 3.0)
        assert name == "Fairplay"

    def test_run_place_single_returns_none_when_no_row(self):
        """run_place_single returns None when no place in radius."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchone.return_value = None
        assert lookup_places.run_place_single(conn, 40.0, -105.0, 3.0) is None

    def test_run_place_batch_returns_empty_dict_when_radius_zero(self):
        """run_place_batch returns all None when radius_miles is 0."""
        conn = MagicMock()
        out = lookup_places.run_place_batch(conn, [0, 1], [-105.0, -106.0], [40.0, 41.0], 0.0)
        assert out == {0: None, 1: None}
        conn.cursor.assert_not_called()

    def test_run_place_batch_returns_names_by_index(self):
        """run_place_batch returns dict point_idx -> place name."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [(0, "Fairplay"), (1, "Leadville")]
        out = lookup_places.run_place_batch(conn, [0, 1], [-105.0, -106.0], [39.2, 39.25], 3.0)
        assert out == {0: "Fairplay", 1: "Leadville"}

    def test_query_single_fills_city_from_place_when_admin_has_no_city(self, mock_pool):
        """query_single sets admin_hierarchy['city'] from place lookup when admin city is None."""
        from areas_lib.query import query_single
        conn1, conn2, conn3, conn4, conn5 = [MagicMock() for _ in range(5)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4, conn5]
        for c in (conn1, conn2, conn3, conn4, conn5):
            cur = MagicMock()
            c.cursor.return_value.__enter__ = MagicMock(return_value=cur)
            c.cursor.return_value.__exit__ = MagicMock(return_value=None)
            cur.fetchall.return_value = []
            cur.fetchone.return_value = None
        with patch("areas_lib.query.lookup_admin.run_admin_single", return_value=[]), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_single", return_value=[]), \
             patch("areas_lib.query.lookup_water.run_water_single", return_value=[]), \
             patch("areas_lib.query.lookup_ocean.run_ocean_single", return_value=None), \
             patch("areas_lib.query.lookup_places.run_place_single", return_value="Fairplay"):
            admin, _, _, _ = query_single(mock_pool, PARK_COUNTY_LAT, PARK_COUNTY_LON, city_radius_miles=3.0)
        assert admin["city"] == "Fairplay"

    def test_query_single_does_not_override_admin_city(self, mock_pool):
        """query_single keeps admin city when admin already has a city (place lookup not used for override)."""
        from areas_lib.query import query_single
        conn1, conn2, conn3, conn4, conn5 = [MagicMock() for _ in range(5)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4, conn5]
        for c in (conn1, conn2, conn3, conn4, conn5):
            cur = MagicMock()
            c.cursor.return_value.__enter__ = MagicMock(return_value=cur)
            c.cursor.return_value.__exit__ = MagicMock(return_value=None)
            cur.fetchall.return_value = []
            cur.fetchone.return_value = None
        admin_rows = [(1, 8, "Denver", {"name": "Denver"})]
        with patch("areas_lib.query.lookup_admin.run_admin_single", return_value=admin_rows), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_single", return_value=[]), \
             patch("areas_lib.query.lookup_water.run_water_single", return_value=[]), \
             patch("areas_lib.query.lookup_ocean.run_ocean_single", return_value=None), \
             patch("areas_lib.query.lookup_places.run_place_single", return_value="Fairplay"):
            admin, _, _, _ = query_single(mock_pool, 39.7, -105.0, city_radius_miles=3.0)
        assert admin["city"] == "Denver"

    def test_query_single_no_place_lookup_when_city_radius_zero(self, mock_pool):
        """query_single does not call place lookup when city_radius_miles is 0 (only 4 conns)."""
        from areas_lib.query import query_single
        conn1, conn2, conn3, conn4 = [MagicMock() for _ in range(4)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4]
        for c in (conn1, conn2, conn3, conn4):
            cur = MagicMock()
            c.cursor.return_value.__enter__ = MagicMock(return_value=cur)
            c.cursor.return_value.__exit__ = MagicMock(return_value=None)
            cur.fetchall.return_value = []
            cur.fetchone.return_value = None
        with patch("areas_lib.query.lookup_admin.run_admin_single", return_value=[]), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_single", return_value=[]), \
             patch("areas_lib.query.lookup_water.run_water_single", return_value=[]), \
             patch("areas_lib.query.lookup_ocean.run_ocean_single", return_value=None), \
             patch("areas_lib.query.lookup_places.run_place_single") as mock_place:
            admin, _, _, _ = query_single(mock_pool, PARK_COUNTY_LAT, PARK_COUNTY_LON, city_radius_miles=0.0)
        mock_place.assert_not_called()
        assert admin["city"] is None

    def test_query_batch_fills_city_from_place_when_admin_has_no_city(self, mock_pool):
        """query_batch sets city from place_by_idx when admin has no city for that point."""
        from areas_lib.query import query_batch
        conn1, conn2, conn3, conn4, conn5 = [MagicMock() for _ in range(5)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4, conn5]
        for c in (conn1, conn2, conn3, conn4, conn5):
            cur = MagicMock()
            c.cursor.return_value.__enter__ = MagicMock(return_value=cur)
            c.cursor.return_value.__exit__ = MagicMock(return_value=cur)
            cur.fetchall.return_value = []
        admin_rows = [(0, 2, "United States", {}), (0, 4, "Colorado", {}), (0, 6, "Park County", {})]
        with patch("areas_lib.query.lookup_admin.run_admin_batch", return_value=admin_rows), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_batch", return_value=[]), \
             patch("areas_lib.query.lookup_water.run_water_batch", return_value={0: []}), \
             patch("areas_lib.query.lookup_ocean.run_ocean_batch", return_value={0: None}), \
             patch("areas_lib.query.lookup_places.run_place_batch", return_value={0: "Fairplay"}):
            results = query_batch(mock_pool, [(PARK_COUNTY_LAT, PARK_COUNTY_LON)], city_radius_miles=3.0)
        assert len(results) == 1
        assert results[0][0]["city"] == "Fairplay"


# --- Fake feature data for top-5 limit tests (no real DB). ---


def _fake_protected_row(osm_id: int, name: str, **tag_overrides) -> tuple:
    """Fake DB row (osm_id, name, tags) for protected_areas."""
    tags = {
        "protection_title": "",
        "protect_class": "",
        "designation": "",
        "operator": "",
        "leisure": "",
        "landuse": "",
        "boundary": "",
    }
    tags.update(tag_overrides)
    return (osm_id, name, tags)


def _fake_lake_row(name: str, water_type: str = "water", distance_miles: float = 0.0, on_water: bool = True) -> tuple:
    """Fake DB row (name, water_type, distance_miles, on_water) for nearby_lakes."""
    return (name, water_type, distance_miles, on_water)


class TestProtectedAreasTop5:
    """Validate protected areas are limited to top 5 per point; DB is mocked, no real DB."""

    def test_build_protected_list_empty(self):
        out = lookup_protected_areas.build_protected_list([])
        assert out == []

    def test_build_protected_list_one(self):
        rows = [_fake_protected_row(1, "Park A")]
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 1
        assert out[0]["name"] == "Park A"

    def test_build_protected_list_five_at_limit(self):
        rows = [_fake_protected_row(i, f"Park {i}") for i in range(1, 6)]
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 5
        assert [x["name"] for x in out] == ["Park 1", "Park 2", "Park 3", "Park 4", "Park 5"]

    def test_build_protected_list_skips_rows_without_name(self):
        rows = [
            _fake_protected_row(1, "Park A"),
            (999, None, {}),
            (998, "", {"name": "From Tags"}),
        ]
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 2
        assert out[0]["name"] == "Park A"
        assert out[1]["name"] == "From Tags"

    def test_build_protected_list_no_truncation_beyond_five(self):
        """build_protected_list does not truncate; limit is enforced in SQL. 6 rows -> 6 items."""
        rows = [_fake_protected_row(i, f"Park {i}") for i in range(1, 8)]
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 7

    def test_run_protected_single_execute_receives_limit_5(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = []
        lookup_protected_areas.run_protected_single(conn, 40.0, -105.0)
        cur.execute.assert_called_once()
        args = cur.execute.call_args[0]
        params = args[1]
        assert params[0] == -105.0 and params[1] == 40.0
        assert params[2] == lookup_protected_areas.PROTECTED_LIMIT_PER_POINT
        assert params[2] == 5

    def test_run_protected_single_returns_at_most_five_when_mock_returns_five(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [_fake_protected_row(i, f"Park {i}") for i in range(1, 6)]
        rows = lookup_protected_areas.run_protected_single(conn, 40.0, -105.0)
        assert len(rows) == 5

    def test_run_protected_single_empty(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = []
        rows = lookup_protected_areas.run_protected_single(conn, 40.0, -105.0)
        assert len(rows) == 0

    def test_run_protected_batch_execute_receives_limit_5(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = []
        lookup_protected_areas.run_protected_batch(conn, [0], [-105.0], [40.0])
        cur.execute.assert_called_once()
        args = cur.execute.call_args[0]
        params = args[1]
        assert params[-1] == lookup_protected_areas.PROTECTED_LIMIT_PER_POINT
        assert params[-1] == 5

    def test_run_protected_batch_five_per_point(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [
            (0, i, f"Park P0 {i}", {}) for i in range(1, 6)
        ] + [
            (1, i, f"Park P1 {i}", {}) for i in range(1, 6)
        ]
        rows = lookup_protected_areas.run_protected_batch(conn, [0, 1], [-105.0, -106.0], [40.0, 41.0])
        assert len(rows) == 10
        by_idx = {}
        for r in rows:
            by_idx.setdefault(r[0], []).append(r)
        assert len(by_idx[0]) == 5
        assert len(by_idx[1]) == 5

    def test_query_single_protected_at_most_five(self, mock_pool):
        """End-to-end: query_single returns at most 5 protected areas when run_protected_single returns 5."""
        from areas_lib.query import query_single
        conn1, conn2, conn3, conn4, conn5 = [MagicMock() for _ in range(5)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4, conn5]
        cur2 = MagicMock()
        conn2.cursor.return_value.__enter__ = MagicMock(return_value=cur2)
        conn2.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur2.fetchall.return_value = [_fake_protected_row(i, f"Park {i}") for i in range(1, 6)]
        cur1 = MagicMock()
        conn1.cursor.return_value.__enter__ = MagicMock(return_value=cur1)
        conn1.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur1.fetchall.return_value = []
        cur3 = MagicMock()
        conn3.cursor.return_value.__enter__ = MagicMock(return_value=cur3)
        conn3.cursor.return_value.__exit__ = MagicMock(return_value=None)
        conn3.cursor.return_value.__enter__ = MagicMock(return_value=cur3)
        conn3.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur3.fetchall.side_effect = [[], []]
        with patch("areas_lib.query.lookup_admin.run_admin_single", return_value=[]), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_single", return_value=cur2.fetchall.return_value), \
             patch("areas_lib.query.lookup_water.run_water_single", return_value=[]), \
             patch("areas_lib.query.lookup_ocean.run_ocean_single", return_value=None), \
             patch("areas_lib.query.lookup_places.run_place_single", return_value=None):
            admin, protected, lakes, ocean = query_single(mock_pool, 40.0, -105.0)
        assert len(protected) == 5
        assert protected[0]["name"] == "Park 1"

    def test_run_protected_single_when_mock_returns_six_no_python_truncation(self):
        """If DB returned 6 rows (e.g. bug), we'd get 6; limit is enforced in SQL only."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        six_rows = [_fake_protected_row(i, f"Park {i}") for i in range(1, 7)]
        cur.fetchall.return_value = six_rows
        rows = lookup_protected_areas.run_protected_single(conn, 40.0, -105.0)
        assert len(rows) == 6
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 6

    def test_run_protected_batch_single_point_five_results(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [(0, i, f"Park {i}", {}) for i in range(1, 6)]
        rows = lookup_protected_areas.run_protected_batch(conn, [0], [-105.0], [40.0])
        assert len(rows) == 5
        out = lookup_protected_areas.build_protected_list([r[1:] for r in rows])
        assert len(out) == 5

    def test_run_protected_batch_mixed_zero_and_five_per_point(self):
        """Point 0 has 0 results, point 1 has 5; grouping must still give 5 for point 1."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [(1, i, f"Park P1 {i}", {}) for i in range(1, 6)]
        rows = lookup_protected_areas.run_protected_batch(conn, [0, 1], [-105.0, -106.0], [40.0, 41.0])
        assert len(rows) == 5
        by_idx = {}
        for r in rows:
            by_idx.setdefault(r[0], []).append(r)
        assert 0 not in by_idx
        assert len(by_idx[1]) == 5

    def test_build_protected_list_skips_short_rows(self):
        """Rows with len < 3 are skipped (no crash, don't count toward 5)."""
        rows = [
            (1,),
            (1, "Park A"),  # len 2, skipped
            _fake_protected_row(2, "Park B"),
        ]
        out = lookup_protected_areas.build_protected_list(rows)
        assert len(out) == 1
        assert out[0]["name"] == "Park B"


class TestNearbyLakesTop5:
    """Validate nearby_lakes are limited to top 5 per point (on-water + near-shore); DB mocked."""

    def test_build_nearby_lakes_empty(self):
        out = lookup_water.build_nearby_lakes([])
        assert out == []

    def test_build_nearby_lakes_one(self):
        rows = [_fake_lake_row("Lake A")]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 1
        assert out[0]["name"] == "Lake A"
        assert out[0]["on_water"] is True

    def test_build_nearby_lakes_five_at_limit(self):
        rows = [_fake_lake_row(f"Lake {i}", on_water=(i <= 2)) for i in range(1, 6)]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 5

    def test_build_nearby_lakes_skips_empty_name(self):
        rows = [
            _fake_lake_row("Lake A"),
            ("", "water", 0.0, True),
            _fake_lake_row("Lake B"),
        ]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 2
        assert [x["name"] for x in out] == ["Lake A", "Lake B"]

    def test_build_nearby_lakes_no_truncation_beyond_five(self):
        rows = [_fake_lake_row(f"Lake {i}") for i in range(1, 8)]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 7

    def test_run_water_single_execute_receives_limit_5(self):
        """Water single uses one round-trip (UNION ALL); params include lon, lat, both limits, radius."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = []
        lookup_water.run_water_single(conn, 40.0, -105.0, 1.0)
        cur.execute.assert_called_once()
        params = cur.execute.call_args[0][1]
        assert params[0] == -105.0 and params[1] == 40.0
        assert params[2] == lookup_water.NEARBY_LAKES_LIMIT
        assert params[3] == pytest.approx(1609.34, rel=1e-2)
        assert params[4] == lookup_water.NEARBY_LAKES_LIMIT

    def test_run_water_single_empty(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.side_effect = [[], []]
        rows = lookup_water.run_water_single(conn, 40.0, -105.0, 1.0)
        assert len(rows) == 0

    def test_run_water_single_five_on_water_plus_five_near(self):
        """One round-trip returns on-water first, then near-shore (combined list)."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        on_water = [_fake_lake_row(f"On {i}", on_water=True) for i in range(1, 6)]
        near = [_fake_lake_row(f"Near {i}", on_water=False, distance_miles=float(i)) for i in range(1, 6)]
        cur.fetchall.return_value = on_water + near
        rows = lookup_water.run_water_single(conn, 40.0, -105.0, 1.0)
        assert len(rows) == 10
        built = lookup_water.build_nearby_lakes(rows)
        assert len(built) == 10

    def test_run_water_batch_execute_receives_limit_5(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = []
        lookup_water.run_water_batch(conn, [0], [-105.0], [40.0], 1.0)
        cur.execute.assert_called_once()
        params = cur.execute.call_args[0][1]
        assert params[-1] == lookup_water.NEARBY_LAKES_LIMIT
        assert params[-1] == 5

    def test_run_water_batch_five_per_point(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [
            (0, f"Lake P0 {i}", "water", 0.0 if i <= 2 else float(i), i <= 2)
            for i in range(1, 6)
        ] + [
            (1, f"Lake P1 {i}", "reservoir", 0.0 if i <= 1 else float(i), i <= 1)
            for i in range(1, 6)
        ]
        by_idx = lookup_water.run_water_batch(conn, [0, 1], [-105.0, -106.0], [40.0, 41.0], 1.0)
        assert len(by_idx) == 2
        assert len(by_idx[0]) == 5
        assert len(by_idx[1]) == 5
        built0 = lookup_water.build_nearby_lakes(by_idx[0])
        built1 = lookup_water.build_nearby_lakes(by_idx[1])
        assert len(built0) == 5
        assert len(built1) == 5

    def test_query_single_nearby_lakes_at_most_five_plus_five(self, mock_pool):
        """query_single returns at most 5 on-water + 5 near-shore (current contract)."""
        from areas_lib.query import query_single
        mock_pool.getconn.side_effect = [MagicMock(), MagicMock(), MagicMock(), MagicMock(), MagicMock()]
        lakes = [_fake_lake_row(f"Lake {i}") for i in range(1, 6)]
        with patch("areas_lib.query.lookup_admin.run_admin_single", return_value=[]), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_single", return_value=[]), \
             patch("areas_lib.query.lookup_water.run_water_single", return_value=lakes), \
             patch("areas_lib.query.lookup_ocean.run_ocean_single", return_value=None), \
             patch("areas_lib.query.lookup_places.run_place_single", return_value=None):
            admin, protected, water, ocean = query_single(mock_pool, 40.0, -105.0)
        assert len(water) == 5
        assert water[0]["name"] == "Lake 1"

    def test_run_water_single_only_on_water_five(self):
        """Five on-water, zero near-shore: total 5 (one combined fetchall)."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        on_water = [_fake_lake_row(f"On {i}", on_water=True) for i in range(1, 6)]
        cur.fetchall.return_value = on_water
        rows = lookup_water.run_water_single(conn, 40.0, -105.0, 1.0)
        assert len(rows) == 5
        built = lookup_water.build_nearby_lakes(rows)
        assert len(built) == 5
        assert all(b["on_water"] for b in built)

    def test_run_water_single_only_near_shore_five(self):
        """Zero on-water, five near-shore: total 5 (one combined fetchall)."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        near = [_fake_lake_row(f"Near {i}", on_water=False, distance_miles=float(i)) for i in range(1, 6)]
        cur.fetchall.return_value = near
        rows = lookup_water.run_water_single(conn, 40.0, -105.0, 1.0)
        assert len(rows) == 5
        built = lookup_water.build_nearby_lakes(rows)
        assert len(built) == 5
        assert not any(b["on_water"] for b in built)

    def test_run_water_batch_single_point_five_results(self):
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [(0, f"Lake {i}", "water", 0.0, True) for i in range(1, 6)]
        by_idx = lookup_water.run_water_batch(conn, [0], [-105.0], [40.0], 1.0)
        assert list(by_idx.keys()) == [0]
        assert len(by_idx[0]) == 5

    def test_run_water_batch_mixed_zero_and_five_per_point(self):
        """Point 0 has 0 lakes, point 1 has 5."""
        conn = MagicMock()
        cur = MagicMock()
        conn.cursor.return_value.__enter__ = MagicMock(return_value=cur)
        conn.cursor.return_value.__exit__ = MagicMock(return_value=None)
        cur.fetchall.return_value = [(1, f"Lake P1 {i}", "water", 0.0, True) for i in range(1, 6)]
        by_idx = lookup_water.run_water_batch(conn, [0, 1], [-105.0, -106.0], [40.0, 41.0], 1.0)
        assert 0 not in by_idx
        assert len(by_idx[1]) == 5

    def test_build_nearby_lakes_skips_short_rows(self):
        """Rows with len < 4 are skipped."""
        rows = [
            ("A",),
            ("A", "water"),
            ("A", "water", 0.0),
            _fake_lake_row("Lake B"),
        ]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 1
        assert out[0]["name"] == "Lake B"

    def test_build_nearby_lakes_none_optional_fields(self):
        """None distance_miles -> 0.0; None water_type -> 'water'."""
        rows = [("Pond", None, None, True)]
        out = lookup_water.build_nearby_lakes(rows)
        assert len(out) == 1
        assert out[0]["water_type"] == "water"
        assert out[0]["distance_miles"] == 0.0

    def test_query_batch_protected_and_lakes_five_per_point(self, mock_pool):
        """query_batch: each result has at most 5 protected and at most 5 lakes (mocked)."""
        from areas_lib.query import query_batch
        conn1, conn2, conn3, conn4, conn5 = [MagicMock() for _ in range(5)]
        mock_pool.getconn.side_effect = [conn1, conn2, conn3, conn4, conn5]
        for c in (conn1, conn2, conn3, conn4, conn5):
            cur = MagicMock()
            c.cursor.return_value.__enter__ = MagicMock(return_value=cur)
            c.cursor.return_value.__exit__ = MagicMock(return_value=None)
            cur.fetchall.return_value = []
        protected_rows = [(0, i, f"Park P0 {i}", {}) for i in range(1, 6)] + [
            (1, i, f"Park P1 {i}", {}) for i in range(1, 6)
        ]
        water_by_idx = {
            0: [_fake_lake_row(f"Lake P0 {i}") for i in range(1, 6)],
            1: [_fake_lake_row(f"Lake P1 {i}") for i in range(1, 6)],
        }
        ocean_by_idx = {0: None, 1: None}
        place_by_idx = {0: None, 1: None}
        with patch("areas_lib.query.lookup_admin.run_admin_batch", return_value=[]), \
             patch("areas_lib.query.lookup_protected_areas.run_protected_batch", return_value=protected_rows), \
             patch("areas_lib.query.lookup_water.run_water_batch", return_value=water_by_idx), \
             patch("areas_lib.query.lookup_ocean.run_ocean_batch", return_value=ocean_by_idx), \
             patch("areas_lib.query.lookup_places.run_place_batch", return_value=place_by_idx):
            results = query_batch(mock_pool, [(40.0, -105.0), (41.0, -106.0)])
        assert len(results) == 2
        for i, (admin, protected, lakes, ocean) in enumerate(results):
            assert len(protected) == 5, f"point {i} protected"
            assert len(lakes) == 5, f"point {i} lakes"
