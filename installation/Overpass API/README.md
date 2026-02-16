# Overpass API Server Installation

Official OSM guide: <https://wiki.openstreetmap.org/wiki/Overpass_API/Installation>

The Overpass API server is a beast and public instances tend to be slow and can time out.

If you serve a lot of users or are uploading large files, you should consider running your own server.

Setting up an Overpass API server is a very involved and complicated process. This guide tries to
walk you through the process.

This guide has the following minimum system requirements:

- 6 CPU cores
- 16 GB memory
- ???? GB storage space

## Paths

`/srv/overpass/downloads`: where the planet databases are downloaded to

`/srv/overpass/databases`: Overpass databases

`/srv/overpass/replicates`: database update diffs

`/srv/overpass/bin`

## Server Install

```shell
apt-get update
apt-get install g++ make expat libexpat1-dev zlib1g-dev bzip2 osmctools
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
TMPDIR=/srv/overpass/tmp ./configure CXXFLAGS="-march=native -mtune=native -O2" --prefix=/usr
TMPDIR=/srv/overpass/tmp make -j$(nproc) install
rm -rf /srv/overpass/tmp
```

## Data Loading

### Download

```shell
mkdir -p /srv/overpass/downloads
```

Download the MD5 hashes first:

```shell
curl -sL https://download.geofabrik.de/north-america-latest.osm.pbf.md5 | awk -v f='/srv/overpass/downloads/north-america-latest.osm.pbf' '{print $1"  "f}' > /srv/overpass/downloads/north-america-latest.osm.pbf.md5
curl -sL https://download.geofabrik.de/europe-latest.osm.pbf.md5 | awk -v f='/srv/overpass/downloads/europe-latest.osm.pbf' '{print $1"  "f}' > /srv/overpass/downloads/europe-latest.osm.pbf.md5
```

```shell
aria2c --continue=true --max-connection-per-server=16 --split=16 --dir=/srv/overpass/downloads --out=north-america-latest.osm.pbf https://download.geofabrik.de/north-america-latest.osm.pbf
aria2c --continue=true --max-connection-per-server=16 --split=16 --dir=/srv/overpass/downloads --out=europe-latest.osm.pbf https://download.geofabrik.de/europe-latest.osm.pbf
```

Wait a few hours. Then verify:

```shell
md5sum -c /srv/overpass/downloads/north-america-latest.osm.pbf.md5 /srv/overpass/downloads/europe-latest.osm.pbf.md5
```

Wait 10 minutes. Should see `OK` for both files. If not, you're SOL.

### Convert PBF to OSM XML Format

The `init_osm3s.sh` script requires bzip2-compressed OSM XML format:

```shell
mkdir -p /srv/overpass/converted
osmconvert /srv/overpass/downloads/north-america-latest.osm.pbf --out-osm | bzip2 > /srv/overpass/converted/north-america-latest.osm.bz2
osmconvert /srv/overpass/downloads/europe-latest.osm.pbf --out-osm | bzip2 > /srv/overpass/converted/europe-latest.osm.bz2
```

Wait 1 day.

### Initialize Database

Create directories and change to the correct root directory:
```shell
mkdir -p /srv/overpass/databases
cd /srv/overpass
```

First import this:
```shell
/usr/bin/init_osm3s.sh /srv/overpass/converted/north-america-latest.osm.bz2 /srv/overpass/databases /usr --meta --flush-size=1
```

Then import that:
```shell
/usr/bin/init_osm3s.sh /srv/overpass/converted/europe-latest.osm.bz2 /srv/overpass/databases /usr --meta --flush-size=1
```

DO NOT run both these imports at the same time! Must be run sequentially.

Then fix permissions:

```shell
chown -R overpass:overpass /srv/overpass/databases
```

Wait 3 days.

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
useradd -r -s /bin/bash -d /srv/overpass -m overpass
chown -R overpass:overpass /srv/overpass
```

### Install Systemd Service Files

Copy the service files to systemd:

```shell
cp *.service /etc/systemd/system/
systemctl daemon-reload
```

Reload systemd and enable services:

```shell
systemctl enable overpass-dispatcher.service
systemctl enable overpass-fetch.service
systemctl enable overpass-apply.service
```

### Applying Diffs

To set up automatic diff application, you need to find replicate sequence number.

Browse through the replicate directory at <https://planet.osm.org/replication/day/> hierarchy and find
the diff that has a date before the starting point of the planet dump, like a day before or something.

Verify you have the right file by checking the respective *.state.txt file. The timestamp should show a date (here
always UTC) slightly before midnight. `sequenceNumber` in this file is your replicant sequence number.

Edit `overpass-fetch.service` and `overpass-apply.service`. Replace `auto` on the `ExecStart` line in each file with
your replicant sequence number.

### Start Services

Start services in order (`dispatcher` must start first):

```shell
systemctl start overpass-dispatcher.service
systemctl start overpass-fetch.service
systemctl start overpass-apply.service
```

### Check Status

```shell
systemctl status overpass-dispatcher.service
systemctl status overpass-fetch.service
systemctl status overpass-apply.service
```

## Nginx Setup

Quick and dirty nginx installation:

```shell
apt update && apt install -y nginx fcgiwrap

openssl req -x509 -nodes -days 99999 -newkey rsa:4096 \
  -subj "/C=PE/ST=Lima/L=Lima/O=Acme Inc. /OU=IT Department/CN=acme.com" \
  -keyout /etc/ssl/private/nginx-selfsigned.key -out /etc/ssl/certs/nginx-selfsigned.crt
openssl dhparam -out /etc/ssl/certs/dhparam.pem 2048

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

Copy the Overpass API nginx config:

```shell
cp overpass-nginx.conf /etc/nginx/sites-enabled/default
```

Edit the config to match your setup and restart nginx.

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

We need to build the areas dataset to perform `is_in()` queries (required for reverse geocoding protected areas,
administrative boundaries, etc).

Areas is basically a completely seperate database. See [Areas Setup.md](Areas%20Setup.md) for instructions.
