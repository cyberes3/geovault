# Areas Server

The Areas Server is a standalone Flask service used in the reverse geocoding process. It uses PostGIS and OSM data
loaded via osm2pgsql. It is a separate service from the main GeoVault stack.

Your Postgres server and `.osm.pbf` files need to be stored on fast SSDs. The host should have at least 4 CPUs and 4GB
RAM.

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
   ALTER ROLE is_in_areas SET search_path TO is_in, public;
   ```

Exit with `\q`.

## Installation

```shell
sudo apt install osm2pgsql
```

Then, set up the Python server:

```shell
cd src/areas_server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

## Import OSM data

Download the `.osm.pbf` data from <https://download.geofabrik.de/>. Then, load the data:

```bash
./scripts/import_pbf.sh --database "postgresql://..." /srv/downloads/north-america-latest.osm.pbf
./scripts/import_pbf.sh --append --database "postgresql://..." /srv/downloads/europe-latest.osm.pbf
```

(Run the first `.osm.pbf` import then add the `--append` for subsequent ones.)

Optional arguments for faster imports:

| Argument        | Effect                                                                |
|-----------------|-----------------------------------------------------------------------|
| `--cache MB`    | Node cache size in MB. Rule of thumb: ~50% of free RAM. Default: 800. |
| `--processes N` | Parallel threads. Default: `nproc` (if available).                    |

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

Later runs are typically done via the systemd timer (daily).

## Systemd

Create an environment file with the database URL under `/etc/secrets` (so the password is not in the unit file):

```shell
sudo mkdir -p /etc/secrets
sudo chmod 600 /etc/secrets
echo 'AREAS_SERVER_DATABASE=postgresql://is_in_areas:your_password_here@localhost/is_in_areas' | sudo tee /etc/secrets/areas_server.env
```

Copy the service and timer files:

```shell
sudo cp installation/areas_server.service /etc/systemd/system/
sudo cp installation/areas_server_update.service installation/areas_server_update.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now areas_server areas_server_update.timer
sudo systemctl status areas_server areas_server_update.timer
```