# Areas Server

A standalone Flask server that answers point-in-area queries for the OSM dataset. Backed by PostGIS with data
imported from OSM PBF files via osm2pgsql.

This server is a replacement for the Overpass API server. Overpass is just too large, heavy, and complicated for our use
case. Specifically, the `is_in()` query consumes dozens of GBs of memory for a single query. This standalone server also
allows us to load alternative data sources beyond OSM.

## Data Served

Data comes from OSM and from Natural Earth.

| Layer               | Description                               | How it is calculated                                                                                                                                                                                                                                                                  |
|---------------------|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **admin_hierarchy** | Country, state, county, city at the point | Administrative boundaries (country, state, county, city). Country name normalized (e.g. "United States" → "United States of America"). When admin has no city, the closest OSM place node (place=city, town, village) within `city-radius-miles` (default 3) is used as the city tag. |
| **protected_areas** | Parks, nature reserves, etc. at the point | Up to 5 protected areas (national park, nature reserve, recreation area) that contain the point.                                                                                                                                                                                      |
| **nearby_lakes**    | Named water bodies on water or near shore | Up to 5 water bodies: on water or with shoreline within `lake-radius-miles`. On-water first, then nearest by shoreline distance.                                                                                                                                                      |
| **ocean**           | Ocean name when on or near ocean          | Point inside ocean, or within `ocean-radius-miles` of shoreline.                                                                                                                                                                                                                      |

## Routes

### `GET /query`

Single-point lookup at `lat`, `lon`.

- **Query:** `lat` (required), `lon` (required), `lake-radius-miles` (optional, default `1` — lake shoreline search
  radius in miles), `ocean-radius-miles` (optional, default `1` — ocean shoreline search radius in miles),
  `city-radius-miles` (optional, default `3` — search radius in miles for nearest place node when admin has no city; use
  `0` to disable).
- **Response:** One object with `admin_hierarchy`, `protected_areas` (up to 5), `nearby_lakes` (up to 5: on water or
  shore within `lake-radius-miles`), and `ocean` (name when on or within `ocean-radius-miles` of ocean; null if no ocean
  data or no match).

**Example:** `GET /query?lat=40.34&lon=-105.68`

### `POST /query`

Batch lookup for multiple points.

- **Body:** `{"points": [[lat, lon], ...]}`. Optional keys: `lake-radius-miles` (default `1`), `ocean-radius-miles` (
  default `1`), `city-radius-miles` (default `3`; use `0` to disable nearest-place city lookup).
- **Response:** `{"results": [ ... ]}` — one object per point in the same order, each with `admin_hierarchy`,
  `protected_areas` (up to 5), `nearby_lakes` (up to 5), and `ocean`. More efficient than many GETs (up to five DB
  round-trips in parallel).

### `GET /health`

Health check. Returns `200` with `{"status": "ok"}` when the database is reachable and tables `admin_areas`,
`protected_areas`, and `water_bodies` exist; otherwise an error body.

### `GET /stats`

Database statistics: row count, geographic extent (bbox), and oldest/newest `created` per table; for `admin_areas`, a
breakdown by `admin_level`.

## Installation and Setup

See `installation/Areas Server.md`. Requires Redis.

Don't expose the Areas server to the internet as it isn't designed for that.

## Run the server

```bash
export AREAS_SERVER_DATABASE="postgresql://user:pass@localhost/dbname"
flask --app app run --host 0.0.0.0 --port 5001
```

## Server Environment Variables

| Variable                            | Description                                                                                                                                                                     |
|-------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AREAS_SERVER_DATABASE`             | PostgreSQL connection string (required).                                                                                                                                        |
| `AREAS_SERVER_MAX_BATCH_SIZE`       | Max points per batch request (default: 500).                                                                                                                                    |
| `AREAS_SERVER_CACHE_TTL`            | Response cache TTL in seconds for single-point GET (default: 86400 = 1 day). 0 = cache off.                                                                                     |
| `AREAS_SERVER_CACHE_COORD_DECIMALS` | Decimal places for cache key (default: 4).                                                                                                                                      |
| `AREAS_SERVER_REDIS_URL`            | Redis URL for response cache, shared across Gunicorn workers (default: `redis://127.0.0.1:6379/3`). Use a separate DB from the core server (e.g. core uses 1, 2; areas uses 3). |
| `AREAS_SERVER_POOL_MAX_SIZE`        | PostgreSQL connection pool max size (default: 10).                                                                                                                              |