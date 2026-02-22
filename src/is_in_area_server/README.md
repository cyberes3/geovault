# is_in Area Server

Standalone Flask server that answers point-in-area queries using PostGIS: which administrative boundaries and protected
areas (parks, reserves, etc.) contain a given coordinate. Data is imported from OSM PBF files via osm2pgsql with a
custom flex config. This server is supplemental to the main Overpass-based reverse geocoding (admin + protected areas
only).

## Requirements

- Python 3.10+
- PostgreSQL with PostGIS
- **osm2pgsql** (1.8+ with flex output). Install via package manager or build from
  the [osm2pgsql](https://github.com/osm2pgsql-dev/osm2pgsql) repo.
- For incremental updates: **osm2pgsql-replication** (Python script in the osm2pgsql repo) and its dependencies (e.g.
  pyosmium).

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

**GET /query?lat=40.34&lon=-105.68** — single-point query. Returns
  `{ "admin_hierarchy": { "country", "state", "county", "city" }, "protected_areas": [ ... ] }`.

**POST /query** — batch. Body: `{"points": [[lat, lon], ...]}`. Returns
  `{"results": [{ "admin_hierarchy", "protected_areas" }, ...]}` in the same order. More efficient than N single-point
  calls (two DB round-trips total).

**GET /health** — health check (DB and table existence).

**GET /stats** — database stats: feature counts, geographic extent (bbox) per layer, admin level breakdown, oldest/newest feature timestamps (requires import with `-x`).