"""
Minimal tests for the standalone is_in area server (Flask + PostGIS).
Routes and validation only; DB/query logic is covered by main geocoding tests.
"""
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Server runs as a script from its own dir; add it to path so "config" and "query" resolve
_server_dir = Path(__file__).resolve().parent.parent / "is_in_area_server"
if str(_server_dir) not in sys.path:
    sys.path.insert(0, str(_server_dir))


@pytest.fixture
def client():
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
    def test_health_ok(self, client, mock_pool):
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.check_health", return_value=(True, None)):
            r = client.get("/health")
        assert r.status_code == 200
        assert r.json == {"status": "ok"}

    def test_health_unhealthy(self, client, mock_pool):
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.check_health", return_value=(False, "no tables")):
            r = client.get("/health")
        assert r.status_code == 503
        assert r.json["status"] == "unhealthy"
        assert "no tables" in r.json["error"]


class TestStats:
    def test_stats_ok(self, client, mock_pool):
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.get_stats", return_value={
                 "admin_areas": {"count": 10},
                 "protected_areas": {"count": 5},
             }):
            r = client.get("/stats")
        assert r.status_code == 200
        assert r.json["admin_areas"]["count"] == 10
        assert r.json["protected_areas"]["count"] == 5


class TestQueryGet:
    def test_query_get_missing_params(self, client):
        r = client.get("/query")
        assert r.status_code == 400
        assert "required" in r.json["error"].lower()

        r = client.get("/query?lat=40")
        assert r.status_code == 400

    def test_query_get_invalid_coords(self, client):
        r = client.get("/query?lat=x&lon=-105")
        assert r.status_code == 400
        assert "number" in r.json["error"].lower()

        r = client.get("/query?lat=40&lon=200")
        assert r.status_code == 400
        assert "180" in r.json["error"]

        r = client.get("/query?lat=91&lon=-105")
        assert r.status_code == 400
        assert "90" in r.json["error"]

    def test_query_get_ok(self, client, mock_pool):
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_single", return_value=(
                 {"country": "United States of America", "state": "Colorado", "county": None, "city": None},
                 [{"name": "Rocky Mountain NP", "type": "national_park"}],
             )):
            r = client.get("/query?lat=40.34&lon=-105.68")
        assert r.status_code == 200
        assert r.json["admin_hierarchy"]["country"] == "United States of America"
        assert r.json["admin_hierarchy"]["state"] == "Colorado"
        assert len(r.json["protected_areas"]) == 1
        assert r.json["protected_areas"][0]["name"] == "Rocky Mountain NP"


class TestQueryPost:
    def test_query_post_not_json(self, client):
        r = client.post("/query", data="not json", content_type="text/plain")
        assert r.status_code == 400
        assert "application/json" in r.json["error"].lower()

    def test_query_post_no_points(self, client):
        r = client.post("/query", json={}, content_type="application/json")
        assert r.status_code == 400
        assert "points" in r.json["error"].lower()

    def test_query_post_points_not_list(self, client):
        r = client.post("/query", json={"points": "x"}, content_type="application/json")
        assert r.status_code == 400
        assert "array" in r.json["error"].lower()

    def test_query_post_invalid_point(self, client):
        r = client.post("/query", json={"points": [[40, "x"]]}, content_type="application/json")
        assert r.status_code == 400
        assert "index" in r.json["error"].lower()

    def test_query_post_ok(self, client, mock_pool):
        with patch("app.get_pool", return_value=mock_pool), \
             patch("app.query_batch", return_value=[
                 ({"country": "United States of America", "state": "Colorado", "county": None, "city": None}, []),
                 ({"country": "United States of America", "state": "California", "county": None, "city": None}, []),
             ]):
            r = client.post("/query", json={"points": [[40.34, -105.68], [37.77, -122.42]]}, content_type="application/json")
        assert r.status_code == 200
        assert "results" in r.json
        assert len(r.json["results"]) == 2
        assert r.json["results"][0]["admin_hierarchy"]["state"] == "Colorado"
        assert r.json["results"][1]["admin_hierarchy"]["state"] == "California"
