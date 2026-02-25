# Areas Server

The Areas Server is a standalone Flask service used in the reverse geocoding process. It uses PostGIS and OSM data
loaded via osm2pgsql. It is a separate service from the main GeoVault stack.

Your Postgres server and `.osm.pbf` files need to be stored on fast SSDs (Samsung 990 Pros work very well and the OSM
import only takes a few hours). The host should have at least 6 CPUs and 16GB RAM. The python server is pretty
lightweight as Postgres does the heavy lifting.

## Database Setup

Use a dedicated database seperate from the main GeoVault one.

1. Generate a secure password, e.g. `pwgen 32 1`
2. `sudo -u postgres psql`
3. `CREATE DATABASE is_in_areas WITH ENCODING 'UTF8' LC_COLLATE='C.utf8' LC_CTYPE='C.utf8' TEMPLATE=template0;`
4. `CREATE USER is_in_areas WITH PASSWORD 'your_password_here';`
6. `\c is_in_areas`
7. ```sql
   GRANT ALL PRIVILEGES ON DATABASE is_in_areas TO is_in_areas;
   CREATE EXTENSION IF NOT EXISTS postgis;
   CREATE SCHEMA IF NOT EXISTS is_in;
   GRANT ALL ON SCHEMA is_in TO is_in_areas;
   GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA is_in TO is_in_areas;
   GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA is_in TO is_in_areas;
   ALTER DEFAULT PRIVILEGES IN SCHEMA is_in GRANT ALL ON TABLES TO is_in_areas;
   ALTER ROLE is_in_areas SET search_path TO is_in, public;
   ```

Exit with `\q`.

### Postgre Tuning

You will likely need to tune your Postgres server.

| Parameter                    | Suggested                         | Purpose                                                |
|------------------------------|-----------------------------------|--------------------------------------------------------|
| **random_page_cost**         | `1.1`                             | Planner prefers index scans (default 4.0 assumes HDD). |
| **effective_cache_size**     | ~75% of RAM, e.g. `12GB` for 16GB | Planner favors index use.                              |
| **effective_io_concurrency** | `200` (SSD/NVMe)                  | Lets bitmap heap scans issue more concurrent I/O.      |
| **shared_buffers**           | e.g. `4GB` for 16GB RAM           | If not already set.                                    |
| **work_mem**                 | Session only (see below)          | More memory for sorts/hash before spilling to disk.    |
| **statement_timeout**        | `0` (no timeout)                  | Disable timeout for long-running imports.              |

The areas server sets **work_mem** per connection (default 128MB) via `AREAS_SERVER_WORK_MEM`; raise it if
`EXPLAIN` shows heavy sorts or hash joins.

Example (in `postgresql.conf`):

```
random_page_cost = 1.1
effective_cache_size = 12GB
effective_io_concurrency = 200
```

For full parameter reference see [PostgreSQL Tuning Guide](https://postgresqlco.nf/tuning-guide).

## Installation

```shell
sudo apt install osm2pgsql
```

Then, set up the Python server:

```shell
cd src/areas-server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

## Import OSM data

Download the `.osm.pbf` data from <https://download.geofabrik.de/>. You are expected to download data to
`/srv/downloads`.

Scripts use a connection string in this format:

```
"postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
```

To load the OSM data:

```bash
./scripts/import_pbf.sh --database "postgresql://..." /srv/downloads/north-america-latest.osm.pbf
./scripts/import_pbf.sh --database "postgresql://..." /srv/downloads/europe-latest.osm.pbf --append
```

Run the first `.osm.pbf` import then add the `--append` for subsequent ones. If an `import_pbf.sh` run is canceled, you
have to start the entire run over and start at the first file. It is recommended to snapshot your VM or whatever between
PBF imports.

Remove small lakes so they do not clutter up the nearby-lakes results:

```bash
./venv/bin/python scripts/delete_small_lakes.py --database "postgresql://..."
```

Download and import the ocean dataset:

```bash
./venv/bin/python scripts/import_ocean_polygons.py --local-path /srv/downloads --database "postgresql://..."
```

Download and import the ski resort dataset (OpenSkiMap; script uses `ski_areas.geojson` in the download directory,
refreshes if older than 1 day):

```bash
./venv/bin/python scripts/import_ski_areas.py --local-path /srv/downloads --database "postgresql://..."
```

The standalone Python import scripts drop their table and re-import fresh data on every run.

After the imports, run the post-processing script to create geography indexes on the tables and refresh
statistics:

```bash
./scripts/post_analyze.sh "postgresql://..."
```

If you do not run `post_analyze.sh` the queries will be incredibly slow.

## Running the Server

```shell
export AREAS_SERVER_DATABASE="postgresql://..."
./venv/bin/flask --app app run --host 0.0.0.0 --port 5001
```

## Incremental Updates

`osm2pgsql` supports easily importing update diffs from the OSM server.

First, initalize the diffs:

```shell
./scripts/update.sh --database "postgresql://..." init
```

To run the update:

```shell
./scripts/update.sh --database "postgresql://..." update
```

It will output something like this:

```
2026-02-22 10:02:14 [INFO]: Initialised updates for service 'https://download.geofabrik.de/north-america-updates'.
2026-02-22 10:02:14 [INFO]: Starting at sequence 4683 (2026-01-30T21:21:29Z).
```

Later runs are typically done via the daily systemd timer.

## Systemd

Create an environment file with the database URL under `/etc/secrets` (so the password is not in the unit file):

```shell
sudo mkdir -p /etc/secrets
sudo chmod 600 /etc/secrets
echo 'AREAS_SERVER_DATABASE=postgresql://is_in_areas:your_password_here@localhost/is_in_areas' | sudo tee /etc/secrets/areas_server.env
```

Copy the service and timer files:

```shell
sudo cp installation/areas-server.service /etc/systemd/system/
sudo cp installation/areas-server-update.service installation/areas-server-update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now areas-server areas-server-update.timer
sudo systemctl status areas-server areas-server-update.timer
```
