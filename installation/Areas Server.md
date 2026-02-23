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

To load the OSM data:

```bash
./scripts/import_pbf.sh --database "postgresql://..." /srv/downloads/north-america-latest.osm.pbf
./scripts/import_pbf.sh --database "postgresql://..." /srv/downloads/europe-latest.osm.pbf --append
```

Run the first `.osm.pbf` import then add the `--append` for subsequent ones.

Optional arguments for faster imports:

| Argument        | Effect                                             |
|-----------------|----------------------------------------------------|
| `--cache MB`    | Node cache size in MB. Default: 800.               |
| `--processes N` | Parallel threads. Default: `nproc` (if available). |

After the initial import, run the post-processing script to create geography indexes on the tables and refresh
statistics:

```bash
export AREAS_SERVER_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./scripts/post_analyze.sh
```

Remove small lakes so they do not clutter up the nearby-lakes results:

```bash
./venv/bin/python scripts/delete_small_lakes.py --database "postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
```

Download and import the ocean dataset:

```bash
./venv/bin/python scripts/import_ocean_polygons.py --local-path /srv/downloads --database "postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
```

Download and import the ski resort dataset:

```bash
./venv/bin/python scripts/import_ski_resorts.py --local-path /srv/downloads --database "postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
```

If your computer crashes, try adding the `--no-parallel` arg to disable parallelization.

## Running the Server

```shell
export AREAS_SERVER_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./venv/bin/flask --app app run --host 0.0.0.0 --port 5001
```

## Incremental Updates

`osm2pgsql` supports easily importing update diffs from the OSM server.

First, initalize the diffs:

```shell
./scripts/update.sh --database "postgresql://is_in_areas:your_password_here@localhost/is_in_areas" init
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