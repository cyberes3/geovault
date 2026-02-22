# is_in Area Server

A standalone Flask server that answers point-in-area queries for the OSM dataset. Backed by PostGIS with data is
imported from OSM PBF files via osm2pgsql.

This server is a replacement for the Overpass API server. Overpass is just too large, heavy, and complicated for our use
case. Specifically, the `is_in()` query consumes dozens of GBs of memory for a single query.

## Environment

| Variable                           | Description                                                                 |
|------------------------------------|-----------------------------------------------------------------------------|
| `IS_IN_DATABASE` or `DATABASE_URL` | PostgreSQL connection string (required).                                    |
| `IS_IN_SCHEMA`                     | Schema for tables (default: `is_in`). Use lowercase.                        |
| `IS_IN_MAX_BATCH_SIZE`             | Max points per batch request (default: 500).                                |
| `IS_IN_CACHE_TTL`                  | Response cache TTL in seconds for single-point GET (default: 0 = disabled). |
| `IS_IN_CACHE_COORD_DECIMALS`       | Decimal places for cache key (default: 4).                                  |

## Installation and Setup

See `installation/Areas Server.md`

## Run the server

```bash
pip install -r requirements.txt
export IS_IN_DATABASE="postgresql://user:pass@localhost/dbname"
flask --app app run --host 0.0.0.0 --port 5001
```

## Routes

**GET /query?lat=40.34&lon=-105.68** — single-point query. Optional query args: `lake_radius_miles` (default 1),
`nearby_lakes_limit` (default 10). Returns
`{ "admin_hierarchy": { "country", "state", "county", "city" }, "protected_areas": [ ... ], "nearby_lakes": [ { "name", "distance_miles", "water_type", "on_water" }, ... ] }`.

**POST /query** — batch. Body: `{"points": [[lat, lon], ...]}`. Optional query args or body keys: `lake_radius_miles`,
`nearby_lakes_limit` (same defaults). Returns
`{"results": [{ "admin_hierarchy", "protected_areas", "nearby_lakes" }, ...]}` in the same order. More efficient than N
single-point calls (three DB round-trips total).

**GET /health** — health check (DB and table existence).

**GET /stats** — database stats: feature counts, geographic extent (bbox) per layer, admin level breakdown,
oldest/newest feature timestamps (including `water_bodies`).