# Overpass API Server Installation

Official guide: <https://wiki.openstreetmap.org/wiki/Overpass_API/Installation>

The Overpass API server is a beast and public instances tend to be slow and tend to time out. Setting up your own server
is a complex and involved process but since it's yours you can avoid these issues.

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
sudo cp overpass-dispatcher.service /etc/systemd/system/
sudo cp overpass-fetch.service /etc/systemd/system/
sudo cp overpass-apply.service /etc/systemd/system/
```

Reload systemd and enable services:

```shell
sudo systemctl daemon-reload
sudo systemctl enable overpass-dispatcher.service
sudo systemctl enable overpass-fetch.service
sudo systemctl enable overpass-apply.service
```

### Start Services

Start services in order (dispatcher must start first):

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
