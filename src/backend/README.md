## Install

```shell
sudo apt-get install python3-gdal
```

./venv/bin/python manage.py collectstatic --noinput

PostGIS setup. Run this on your database before starting the geovault for the first time.

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

-- Grant necessary privileges to the geovault user
GRANT ALL PRIVILEGES ON DATABASE geovault TO geovault;
GRANT ALL PRIVILEGES ON SCHEMA public TO geovault;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO geovault;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO geovault;

-- Grant privileges on PostGIS tables
GRANT ALL PRIVILEGES ON TABLE geometry_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE geography_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE spatial_ref_sys TO geovault;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO geovault;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO geovault;
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

This could literally take days to import the data up, so be patient.

### Setup

1. Install Docker
2. `mkdir -p /srv/docker-data/nominatim/db /srv/docker-data/nominatim/flatnode /srv/docker-data/overpass`
3. `docker compose -f nominatim.yml up`
4. `./download-overpass-data.sh`
5. `docker compose -f overpass.yml up`
6. Wait 2 days and come back

This will run the containers in the foreground so you can monitor their progress. `CTRL+C` the containers when they have
finished building their databases and then start them normally:

```shell
docker compose -f nominatim.yml up -d
docker compose -f overpass.yml up -d
```
