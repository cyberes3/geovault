# Installation

Not too complicated but this guide should get you up and running as fast as possible. If you prefer to use Docker, see [Docker.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Docker.md).

We're going to install to `/srv/geovault` and run it as the `geovault` system user.



## PostGIS

Follow these instructions: <https://trac.osgeo.org/postgis/wiki/UsersWikiPostGIS3UbuntuPGSQLApt>

```shell
sudo apt install ca-certificates gnupg curl
curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/apt.postgresql.org.gpg >/dev/null
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list
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
sudo apt install python3.12 python3.12-dev python3-venv python3-gdal
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



## Clone Repository

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



## NodeJS

NodeJS is required for the frontend GeoJSON converter. One-line installer:

```shell
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt-get install -y nodejs
```


Install the dependancies:

```shell
cd src/backend/geo_lib/processing/togeojson/ && npm install
```

```shell
cd src/frontend && npm install && npm run build
```



## MaxMind

MaxMind's GeoIP2 database is used to determine user location based on their IP address. This allows the map to automatically center on the their location instead of using a hardcoded default. Not required, but see [MaxMind.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/MaxMind.md) for instructions to set up.



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

You need to create a new user and database. Quick guide:

1. Generate a secure password via `pwgen 32 1`
2. `sudo -u postgres psql`
3. `CREATE DATABASE geovault;`
4. `CREATE USER geovault WITH PASSWORD 'your_password_here';`
5. `GRANT ALL PRIVILEGES ON DATABASE geovault TO geovault;`
6. `\c geovault;`
7. `GRANT ALL ON SCHEMA public TO geovault;`
8. `GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO geovault;`
9. `GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO geovault;`



Then, manually create the PostGIS extension:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
GRANT ALL PRIVILEGES ON TABLE geometry_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE geography_columns TO geovault;
GRANT ALL PRIVILEGES ON TABLE spatial_ref_sys TO geovault;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO geovault;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO geovault;
```



To exit the SQL console, enter `\q`.



## Django

Once the database is ready, create the tables:

```shell
./venv/bin/python manage.py migrate --noinput
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

Quick Nginx install script:

```shell
sudo apt update && sudo apt install -y nginx

sudo openssl req -x509 -nodes -days 99999 -newkey rsa:4096 \
  -subj "/C=PE/ST=Lima/L=Lima/O=Acme Inc. /OU=IT Department/CN=acme.com" \
  -keyout /etc/ssl/private/nginx-selfsigned.key -out /etc/ssl/certs/nginx-selfsigned.crt
sudo openssl dhparam -out /etc/ssl/certs/dhparam.pem 2048

echo """server {
    listen 443 ssl http2 default_server;
    server_name _;
    ssl_certificate /etc/ssl/certs/nginx-selfsigned.crt;
    ssl_certificate_key /etc/ssl/private/nginx-selfsigned.key;
    client_max_body_size 10G;
    location / {
      proxy_set_header Host $host;
      proxy_set_header Connection $http_connection;
      proxy_set_header X-Scheme $scheme;
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
      proxy_pass http://127.0.0.1:8000;
    }
    include /etc/nginx/snippets/ssl-params.conf;
}""" >/etc/nginx/sites-enabled/default

echo """ssl_protocols TLSv1 TLSv1.1 TLSv1.2;
ssl_prefer_server_ciphers on;
ssl_ciphers \"EECDH+AESGCM:EDH+AESGCM:AES256+EECDH:AES256+EDH\";
ssl_ecdh_curve secp384r1;
ssl_session_cache shared:SSL:10m;
ssl_session_tickets off;
ssl_stapling on;
ssl_stapling_verify on;
resolver 1.1.1.1 1.0.0.1 valid=300s;
resolver_timeout 5s;
add_header Strict-Transport-Security \"max-age=63072000; includeSubdomains\";
add_header X-Frame-Options DENY;
add_header X-Content-Type-Options nosniff;
ssl_dhparam /etc/ssl/certs/dhparam.pem;""" >/etc/nginx/snippets/ssl-params.conf

sudo systemctl enable --now nginx && sudo service nginx restart && sudo service nginx status
```



## Systemd

```shell
cp installation/geovault.service /etc/systemd/system/geovault.service
systemctl daemon-reload
systemctl enable --now geovault
systemctl status geovault
```



## Reverse Geocoding

If you want advanced tagging of your features you will have to set up two very heavy services. [Reverse Geocoding.md](https://git.evulid.cc/cyberes/geovault/src/branch/master/installation/Reverse Geocoding.md) will walk you through it.



## Done!

Everything should be running now and the server will be accessible on `0.0.0.0:8000`. Go ahead and register on the site, the first user will be automatically set as the admin and given the appropriate permissions.

## Android App

See README in `src/android`