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
sudo apt install python3.13 python3.13-dev python3.13-venv python3-gdal git
```

If your system doesn't provide Python 3.13, add this repo:

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
cd geovault/src
```

From here on, run the remaining shell commands in this guide from the repository `src/` directory (`geovault/src` inside the clone).

```shell
python3.13 -m venv backend/venv
backend/venv/bin/pip install -r backend/requirements.txt
```

If you get errors such as this when creating the venv:

```
Error processing line 1 of /usr/lib/python3/dist-packages/distutils-precedence.pth:
  Traceback (most recent call last):
    File "<frozen site>", line 213, in addpackage
    File "<string>", line 1, in <module>
  ModuleNotFoundError: No module named '_distutils_hack'
```

Ignore it and then run:
```shell
curl -sS https://bootstrap.pypa.io/get-pip.py | backend/venv/bin/python3.13
backend/venv/bin/python3.13 -m pip install --upgrade pip setuptools wheel
```

## NodeJS

NodeJS is required for the frontend as well as the internal GeoJSON converter. One-line installer:

```shell
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt-get install -y nodejs
```

Install the GeoJSON parser:

```shell
npm install --prefix backend/geo_lib/processing/togeojson
```

Build the frontend:

```shell
./build-frontend.sh
```

The `build-frontend.sh` script changes into each frontend directory on its own.

Generate fonts for the vector map styles:

```shell
./backend/generate-map-fonts.sh
```

## MaxMind

MaxMind's GeoIP2 database is used to determine user location based on their IP address. This allows the map to
automatically center on the their location instead of using a hardcoded default or requesting location permissions. Not
required, but see [MaxMind.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/MaxMind.md) for instructions to set up.

## Configuration

Copy the default config file:

```shell
cp backend/config.example.yaml backend/config.yaml
```

Then fill in your values in the config. Important values:

- `site.domain`
- `security.secret_key`
- `security.additional_allowed_hosts` (if using an intermediate reverse proxy)
- Database password
- Email settings

## Database

1. Generate a secure password via `pwgen 64 1`
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
backend/venv/bin/python backend/manage.py migrate --noinput
backend/venv/bin/python backend/manage.py ensure_oauth2_app
```

If you get the error `django.db.utils.ProgrammingError: permission denied to create extension "postgis"` then you forgot
to create the PostGIS extension in the database.

Finally, set the correct permissions:

```shell
sudo chown -R geovault:nogroup /srv/geovault
sudo chmod 700 /srv/geovault
```

## Nginx

Example Nginx config file is located at `geovault nginx.conf`.

## Systemd

```shell
cp ../installation/geovault.service /etc/systemd/system/geovault.service
cp ../installation/geovault-celery.service /etc/systemd/system/geovault-celery.service
cp ../installation/geovault-celery-beat.service /etc/systemd/system/geovault-celery-beat.service
systemctl daemon-reload
systemctl enable --now geovault-celery geovault-celery-beat geovault
systemctl status --no-pager geovault-celery
systemctl status --no-pager geovault-celery-beat
systemctl status --no-pager geovault
```

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

## Hauk Tracker Compatibility

To support [Hauk](https://github.com/bilde2910/Hauk) clients (such as for iOS since there is no Tracker app for iOS),
set up `hauk nginx.conf` on your nginx server to pass traffic to the Tracker backend.

## Done!

Everything should be running now and the server will be accessible on `0.0.0.0:8000`. Go ahead and register on the site,
the first user will be automatically set as the admin and given the appropriate permissions.

## Android App

See README in `android`
