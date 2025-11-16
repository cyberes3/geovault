```sql
GRANT ALL ON SCHEMA public TO geobackend;
GRANT ALL ON SCHEMA public TO public;
```

```shell
sudo apt install postgresql-common libpq-dev libxml2-dev libxslt-dev python3.12 python3.12-dev python3.12-venv
```

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

## Nominatim

*Nominatim* is used for reverse geocoding to find cities and towns. It runs locally and requires a 10+ GB database.

To install, use Docker: <https://github.com/mediagis/nominatim-docker>

```shell
docker run -d \
  -e PBF_URL=https://download.geofabrik.de/north-america/us-latest.osm.pbf \
  -p 8080:8080 \
  --name nominatim \
  mediagis/nominatim:5.2
```

### Overpass

The *Overpass API* is used for reverse geocoding to find other features. It also requires a 10+ GB database.

<https://github.com/wiktorn/Overpass-API>