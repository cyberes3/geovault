# Overpass API Server Installation

Official guide: <https://wiki.openstreetmap.org/wiki/Overpass_API/Installation>

The Overpass API server is a beast and public instances tend to be slow and tend to time out. Setting up your own server
is a complex and involved process but since it's yours you can avoid these issues.

If you serve a lot of users or are uploading large files you should consider running your own server.

System minimum requirements:

- 6 CPU cores
- 16 GB memory

## Paths

`/srv/overpass/downloads`: where the planet databases are downloaded to

`/srv/overpass/databases`: Overpass databases

`/srv/overpass/replicates`: database update diffs

`/srv/overpass/bin`

## Server Install

```shell
sudo apt-get update
sudo apt-get install g++ make expat libexpat1-dev zlib1g-dev bzip2 osmctools
```

```shell
mkdir -p /srv/overpass
cd /srv/overpass
wget https://dev.overpass-api.de/releases/osm-3s_latest.tar.gz
tar -zxvf osm-3s_latest.tar.gz
cd osm-3s_v*
```

```shell
mkdir -p /srv/overpass/tmp
TMPDIR=/srv/overpass/tmp ./configure CXXFLAGS="-march=native -mtune=native -O2" --prefix=$EXEC_DIR
TMPDIR=/srv/overpass/tmp make -j$(nproc) install
rm -rf /srv/overpass/tmp
```

## Data Loading

### Download

```shell
mkdir -p /srv/overpass/downloads
aria2c --continue=true --max-connection-per-server=16 --split=16 --dir=/srv/overpass/downloads --out=north-america-latest.osm.pbf https://download.geofabrik.de/north-america-latest.osm.pbf
aria2c --continue=true --max-connection-per-server=16 --split=16 --dir=/srv/overpass/downloads --out=europe-latest.osm.pbf https://download.geofabrik.de/europe-latest.osm.pbf
```

### Load

This may take multiple days to complete!

```shell
mkdir -p /srv/overpass/databases
osmconvert /srv/overpass/downloads/north-america-latest.osm.pbf --out-osm | /usr/bin/update_database --db-dir=/srv/overpass/databases/ --meta
osmconvert /srv/overpass/downloads/europe-latest.osm.pbf --out-osm | /usr/bin/update_database --db-dir=/srv/overpass/databases/ --meta
```

## Systemd Service Setup

### Prevent Shared Memory Cleanup

Overpass API uses shared memory and Unix domain sockets, just like other DBMSes. By default,
systemd's `logind` will delete shared memory when a user logs out, which will crash Overpass. We need to disable this.

Edit `/etc/systemd/logind.conf` and uncomment/modify:

```ini
RemoveIPC = no
```

Then reboot the host.

### Create Overpass User

Create a system user:

```shell
sudo useradd -r -s /bin/bash -d /srv/overpass -m overpass
sudo chown -R overpass:overpass /srv/overpass
```

### Install Systemd Service Files

Copy the service files to systemd:

```shell
sudo cp *.service /etc/systemd/system/
sudo systemctl daemon-reload
```

Reload systemd and enable services:

```shell
sudo systemctl enable overpass-dispatcher.service
sudo systemctl enable overpass-fetch.service
sudo systemctl enable overpass-apply.service
```

### Start Services

Start services in order (`dispatcher` must start first):

```shell
sudo systemctl start overpass-dispatcher.service
sudo systemctl start overpass-fetch.service
sudo systemctl start overpass-apply.service
```

### Check Status

```shell
sudo systemctl status overpass-dispatcher.service
sudo systemctl status overpass-fetch.service
sudo systemctl status overpass-apply.service
```

## Nginx Setup

Quick and dirty nginx installation:

```shell
sudo apt update && sudo apt install -y nginx fcgiwrap

sudo openssl req -x509 -nodes -days 99999 -newkey rsa:4096 \
  -subj "/C=PE/ST=Lima/L=Lima/O=Acme Inc. /OU=IT Department/CN=acme.com" \
  -keyout /etc/ssl/private/nginx-selfsigned.key -out /etc/ssl/certs/nginx-selfsigned.crt
sudo openssl dhparam -out /etc/ssl/certs/dhparam.pem 2048

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
```

Copy and enable the Overpass API nginx config:

```shell
sudo cp overpass-nginx.conf /etc/nginx/sites-enabled/default
```

Edit the config and restart nginx.

### Test the API

Test that the API is working:

```shell
curl -k "https://127.0.0.1/api/interpreter?data=%3Cprint%20mode=%22body%22/%3E"
```

You should see an XML response with the database metadata. The `-k` flag is needed for self-signed certificates.

Example queries:

Simple test - check if data exists (returns count only):

```
curl -k --data-urlencode "data=[out:json];node(40.7,-74.0,40.8,-73.9);out count;" "https://127.0.0.1/api/interpreter"
```

Find a few restaurants in New York City:

```
curl -k --data-urlencode "data=[out:json];node[\"amenity\"=\"restaurant\"](40.75,-74.0,40.76,-73.99);out;" "https://127.0.0.1/api/interpreter"
```

Find a few restaurants in London:

```
curl -k --data-urlencode "data=[out:json];node[\"amenity\"=\"restaurant\"](51.507,-0.128,51.508,-0.127);out;" "https://127.0.0.1/api/interpreter"
```

## Area Data Setup

If you need to use `is_in()` queries (required for reverse geocoding protected areas, administrative boundaries, etc.),
you need to enable and generate area data. This is a separate step from the initial data load.

See [Areas Setup.md](Areas%20Setup.md) for detailed instructions.