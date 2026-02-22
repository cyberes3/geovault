# Areas Server (is_in)

The Areas Server is a standalone Flask service that answers “which admin and protected areas contain this point?” using
PostGIS and OSM data loaded via osm2pgsql. It is separate service from the main GeoVault stack.

## Requirements

- Python 3.10+
- PostgreSQL with PostGIS (same as main GeoVault; see [installation/README.md](README.md) for PostGIS install)
- **osm2pgsql** (1.8+ with flex output)
- For incremental updates: **osm2pgsql-replication** (from the osm2pgsql repo) and pyosmium

## Database

Use a dedicated database (or an existing one with a dedicated schema). The server uses the schema `is_in` and does not
touch the main GeoVault schema.

1. Generate a secure password, e.g. `pwgen 32 1`
2. `sudo -u postgres psql`
3. `CREATE DATABASE is_in_areas WITH ENCODING 'UTF8' LC_COLLATE='C.utf8' LC_CTYPE='C.utf8' TEMPLATE=template0;`
4. `CREATE USER is_in_areas WITH PASSWORD 'your_password_here';`
5. `GRANT ALL PRIVILEGES ON DATABASE is_in_areas TO is_in_areas;`
6. `\c is_in_areas`
7. ```sql
   CREATE EXTENSION IF NOT EXISTS postgis;
   CREATE SCHEMA IF NOT EXISTS is_in;
   GRANT ALL ON SCHEMA is_in TO is_in_areas;
   GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA is_in TO is_in_areas;
   GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA is_in TO is_in_areas;
   ALTER DEFAULT PRIVILEGES IN SCHEMA is_in GRANT ALL ON TABLES TO is_in_areas;
   ```

Exit with `\q`.

## Install and run

From the repo root:

```shell
cd src/is_in_area_server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
export IS_IN_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./venv/bin/flask --app app run --host 0.0.0.0 --port 5000
```

Or run with gunicorn: `./venv/bin/gunicorn -w 4 -b 0.0.0.0:5000 app:app`.

## Import OSM data

Before the server can answer queries, load area data from an OSM PBF (e.g. planet or regional extract):

```shell
export IS_IN_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./scripts/import_pbf.sh /path/to/planet-latest.osm.pbf
```

After the first import, to enable incremental updates:

```shell
./scripts/update.sh init
```

Then run `./scripts/update.sh` periodically (e.g. via cron).
