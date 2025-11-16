## Install

```shell
sudo apt install postgresql-common libpq-dev libxml2-dev libxslt-dev python3.12 python3.12-dev python3.12-venv
```

PostGIS setup. Run this on your database before starting the geoserver for the first time.

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

-- Grant necessary privileges to the geoserver user
GRANT ALL PRIVILEGES ON DATABASE geoserver TO geoserver;
GRANT ALL PRIVILEGES ON SCHEMA public TO geoserver;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO geoserver;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO geoserver;

-- Grant privileges on PostGIS tables
GRANT ALL PRIVILEGES ON TABLE geometry_columns TO geoserver;
GRANT ALL PRIVILEGES ON TABLE geography_columns TO geoserver;
GRANT ALL PRIVILEGES ON TABLE spatial_ref_sys TO geoserver;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO geoserver;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO geoserver;
```

## Reverse Geocoding

Reverse geocoding is performed to generate tags for features that help to mark where they are spacially. This requires
that you host
2 very heavy services.

This feature is disabled by default via the <TODO> flag.

[Nominatim](https://github.com/mediagis/nominatim-docker) is used to find cities and towns.

[Overpass API](https://github.com/wiktorn/Overpass-API) is used to find all other features.

Docker Compose files are provided to help you get the services running with minimal pain.

Running these two services requires a minimum of 25GB RAM, 6 CPU cores, 500GB, and SSDs.

This could literally take days to set up, so be patient.

### Setup

1. Install Docker
2. `mkdir -p /srv/docker-data/nominatim/db /srv/docker-data/nominatim/flatnode /srv/docker-data/overpass`
3. `docker-compose -f nominatim.yml up`
4. `./download-overpass-data.sh`
5. `docker-compose -f overpass.yml up`

`CTRL+C` the containers when they have finished building their databases and then do:

```shell
docker-compose -f nominatim.yml up -d
docker-compose -f overpass.yml up -d
```