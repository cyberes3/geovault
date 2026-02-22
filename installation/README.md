# Installation

Not too complicated but this guide should get you up and running as fast as possible. If you prefer to use Docker,
see [Docker.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Docker.md).

We're going to install to `/srv/geovault` and run it as the `geovault` system user.

## PostGIS

Follow these instructions: <https://trac.osgeo.org/postgis/wiki/UsersWikiPostGIS3UbuntuPGSQLApt>

```shell
sudo apt install ca-certificates gnupg curl
curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/apt.postgresql.org.gpg >/dev/null
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
cat << EOF >> /etc/apt/preferences.d/pgdg.pref
Package: *
Pin: release o=apt.postgresql.org
Pin-Priority: 500
EOF
sudo apt update
sudo apt install postgresql-18-postgis-3
systemctl enable --now postgresql
```

## Install Required Packages

```shell
sudo apt install python3.12 python3.12-dev python3-venv python3-gdal git
```

If your system doesn't provide Python 3.12, add this repo:

```shell
sudo add-apt-repository ppa:deadsnakes/ppa
```

Redis is used for Channels/WebSockets, make sure it is installed and running:

```shell
sudo apt install redis-server
systemctl enable --now redis-server
```

## Setup

```shell
adduser --system geovault --home /srv/geovault
```

```shell
cd /srv/geovault
git clone https://git.evulid.cc/cyberes/geovault.git
```

```shell
cd geovault/src/backend
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
```

```shell
./generate-map-fonts.sh
```

## NodeJS

NodeJS is required for the frontend as well as the internal GeoJSON converter. One-line installer:

```shell
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt-get install -y nodejs
```

Install the GeoJSON parser:

```shell
cd src/backend/geo_lib/processing/togeojson/ && npm install
```

Build the frontend:

```shell
cd src/ && ./build-frontend.sh
```

## MaxMind

MaxMind's GeoIP2 database is used to determine user location based on their IP address. This allows the map to
automatically center on the their location instead of using a hardcoded default. Not required, but
see [MaxMind.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/MaxMind.md) for instructions to
set up.

## Configuration

Copy the default config file:

```shell
cp config.example.yaml config.yaml
```

Then fill in your values in the config. Important values:

- `site.domain`
- `security.secret_key`
- `security.additional_allowed_hosts` (if using an intermediate reverse proxy)
- Database password
- Email settings

## Database

1. Generate a secure password via `pwgen 32 1`
2. `sudo -u postgres psql`
3. `CREATE DATABASE geovault WITH ENCODING 'UTF8' LC_COLLATE='C.utf8' LC_CTYPE='C.utf8' TEMPLATE=template0;`
    - If you have locale issues, find the ones available on your system via: `locale -a`
4. `CREATE USER geovault WITH PASSWORD 'your_password_here';`
5. `GRANT ALL PRIVILEGES ON DATABASE geovault TO geovault;`
6. `\c geovault;`
7. `CREATE EXTENSION IF NOT EXISTS postgis;`
8. `GRANT ALL ON SCHEMA public TO geovault;`
9. `GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO geovault;`
10. `GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO geovault;`

To exit the SQL console, enter `\q`.

## Django

Once the database is ready, create the tables:

```shell
./venv/bin/python manage.py migrate --noinput
./venv/bin/python manage.py ensure_oauth2_app
```

If you get the error `django.db.utils.ProgrammingError: permission denied to create extension "postgis"` then you forgot
to create the PostGIS extension in the database.

You don't need to run `collectstatic`.

Finally, set the correct permissions:

```shell
sudo chown geovault:nogroup /srv/geovault
sudo chmod 600 /srv/geovault
```

## Nginx

Example config file is located at `geovault.conf`.

## Systemd

```shell
cp installation/geovault.service /etc/systemd/system/geovault.service
systemctl daemon-reload
systemctl enable --now geovault
systemctl status geovault
```

## Overpass Server

You can use a third party [Overpass API server](https://wiki.openstreetmap.org/wiki/Overpass_API) to perform reverse
geocoding butyou should really host your own. See
the [Overpass API](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Overpass%20API) directory
for installation instructions.

## Areas Server

A standalone server is nessesary to compute and query an "areas" database as part of the reverse geocoding process when
importing features.
See [Areas Server.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Areas%20Server.md)
for installation instructions.

## MapTiler

MapTiler API services are used in the platform for reverse geocoding, 3D height maps, and additional basemaps.

Create an account on <https://www.maptiler.com> and then generate a new API key
at <https://cloud.maptiler.com/account/keys/>.

DO NOT use the default API key that your account comes with! Instead, generate a new one and set the "Allowed HTTP
Origins" to your domain or else someone can steal your key.

## Google

We use Google's geocoding API for searching for places. Setup instructions are
in [Google APIs.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Google%20APIs.md).

To use Google for geocoding, set `geocoding_search_mode: google`

## Done!

Everything should be running now and the server will be accessible on `0.0.0.0:8000`. Go ahead and register on the site,
the first user will be automatically set as the admin and given the appropriate permissions.

## Android App

See README in `src/android`
