# Areas Server

The Areas Server is a standalone Flask service used in the reverse geocoding process. It uses PostGIS and OSM data
loaded via osm2pgsql. It is a separate service from the main GeoVault stack.

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
   ALTER ROLE is_in_areas SET search_path TO is_in;
   ```

Exit with `\q`.

## Installation

```shell
sudo apt install osm2pgsql
```

Then, set up the Python server:

```shell
cd src/is_in_area_server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

## Import OSM data

Before the server can answer queries, load area data from an OSM PBF:

```shell
export IS_IN_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./scripts/import_pbf.sh /path/to/planet-latest.osm.pbf
```

To import multiple regions:

```bash
./scripts/import_pbf.sh north-america-latest.pbf
./scripts/import_pbf.sh --append europe-latest.pbf
```

## Running the Server

```shell
export IS_IN_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./venv/bin/flask --app app run --host 0.0.0.0 --port 5001
```

## Incremental Updates

`osm2pgsql` supports easily importing update diffs from the OSM server.

```shell
export IS_IN_DATABASE="postgresql://is_in_areas:your_password_here@localhost/is_in_areas"
./update.sh init
```

It will output something like this:

```
2026-02-22 10:02:14 [INFO]: Initialised updates for service 'https://download.geofabrik.de/north-america-updates'.
2026-02-22 10:02:14 [INFO]: Starting at sequence 4683 (2026-01-30T21:21:29Z).
```

Then run `./update.sh` to tell it to fetch the data. Later runs will be run via Systemd.

## Systemd

Create an environment file with the database URL under `/etc/secrets` (so the password is not in the unit file):

```shell
sudo mkdir -p /etc/secrets
sudo chmod 600 /etc/secrets
echo 'IS_IN_DATABASE=postgresql://is_in_areas:your_password_here@localhost/is_in_areas' | sudo tee /etc/secrets/is_in_areas.env
```

Copy the service file and adjust paths if your repo is not at `/srv/geovault/geovault`:

```shell
sudo cp installation/is_in_areas.service /etc/systemd/system/
sudo cp installation/is_in_areas_update.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now is_in_areas is_in_areas_update.timer
sudo systemctl status is_in_areas is_in_areas_update.timer
```