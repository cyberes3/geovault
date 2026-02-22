# Areas Server

A standalone Flask server that answers point-in-area queries for the OSM dataset. Backed by PostGIS with data
imported from OSM PBF files via osm2pgsql.

This server is a replacement for the Overpass API server. Overpass is just too large, heavy, and complicated for our use
case. Specifically, the `is_in()` query consumes dozens of GBs of memory for a single query.

## Data served

All data comes from OSM, imported into the `is_in` schema via osm2pgsql (flex config in `flex_config/areas.lua`).
Each response contains three parts:

| Layer               | Description                               | How it is calculated                                                                                                                         |
|---------------------|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **admin_hierarchy** | Country, state, county, city at the point | All administrative boundaries (country, state, county, city).Country name is normalized (e.g. "United States" → "United States of America"). |
| **protected_areas** | Parks, nature reserves, etc. at the point | Up to 5 protected areas (national park, nature reserve, recreation area) that contain the point.                                             |
| **nearby_lakes**    | Named water bodies on water or near shore | Up to 5 water bodies: on water or with shoreline within `lake-radius-miles`. On-water first, then nearest by shoreline distance.             |

## Routes

### `GET /query`

Single-point lookup at `lat`, `lon`.

- **Query:** `lat` (required), `lon` (required), `lake-radius-miles` (optional, default `1` — shoreline search radius in
  miles).
- **Response:** One object with `admin_hierarchy`, `protected_areas` (up to 5), and `nearby_lakes` (up to 5: on water or
  shore within `lake-radius-miles`).

**Example:** `GET /query?lat=40.34&lon=-105.68`

### `POST /query`

Batch lookup for multiple points.

- **Body:** `{"points": [[lat, lon], ...]}`. Optional key: `lake-radius-miles` (default `1`).
- **Response:** `{"results": [ ... ]}` — one object per point in the same order, each with `admin_hierarchy`,
  `protected_areas` (up to 5), and `nearby_lakes` (up to 5). More efficient than many GETs (three DB round-trips in
  parallel).

### `GET /health`

Health check. Returns `200` with `{"status": "ok"}` when the database is reachable and tables `admin_areas`,
`protected_areas`, and `water_bodies` exist; otherwise an error body.

### `GET /stats`

Database statistics: row count, geographic extent (bbox), and oldest/newest `created` per table; for `admin_areas`, a
breakdown by `admin_level`.

## Installation and Setup

See `installation/Areas Server.md`

## Run the server

```bash
export AREAS_SERVER_DATABASE="postgresql://user:pass@localhost/dbname"
flask --app app run --host 0.0.0.0 --port 5001
```

## Server Environment Variables

| Variable                            | Description                                                                  |
|-------------------------------------|------------------------------------------------------------------------------|
| `AREAS_SERVER_DATABASE`             | PostgreSQL connection string (required).                                     |
| `AREAS_SERVER_MAX_BATCH_SIZE`       | Max points per batch request (default: 500).                                 |
| `AREAS_SERVER_CACHE_TTL`            | Response cache TTL in seconds for single-point GET (default: 86400 = 1 day). |
| `AREAS_SERVER_CACHE_COORD_DECIMALS` | Decimal places for cache key (default: 4).                                   |