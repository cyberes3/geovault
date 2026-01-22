# Overpass API Area Data Setup

This guide explains how to enable and generate area data in your Overpass API instance, which is required for `is_in()`
queries used by the reverse geocoding system.

## Prerequisites

- Base OSM data is loaded
- Dispatcher service is running

## Copy Rules Directory

Copy the rules directory to your database directory:

```bash
cd /srv/overpass/osm-3s_v*
sudo cp -r rules /srv/overpass/databases/
sudo chown -R overpass:overpass /srv/overpass/databases/rules
```

## Start Areas Dispatcher

You need a second dispatcher process specifically for areas.

```shell
sudo cp overpass-areas-*.* /etc/systemd/system/
sudo systemctl daemon-reload
```

```bash
sudo systemctl enable overpass-areas-dispatcher.service
sudo systemctl start overpass-areas-dispatcher.service
sudo systemctl status overpass-areas-dispatcher.service
```

## Initial Area Generation

Before setting up the automatic service, you must run the initial full area generation. This creates the base area
data and can take 4-12+ hours depending on your data size.

Make sure you copied the rules!

```shell
cd /srv/overpass
sudo -u overpass /usr/bin/rules_loop.sh databases
```

This command will run until completion. You can monitor progress by checking for area files:

```bash
# Watch for area files being created (in another terminal)
watch -n 30 'ls -lh /srv/overpass/databases/area_*.bin 2>/dev/null | tail -5'
```

## Set Up Automatic Area Updates

After initial generation completes, enable the automatic incremental update service:

```bash
sudo systemctl enable overpass-areas-generator.timer
sudo systemctl start overpass-areas-generator.timer
sudo systemctl status overpass-areas-generator.timer
```

## Verify Areas Are Working

Once area generation completes, test with a simple query:

```bash
curl -k --data-urlencode "data=[out:json];is_in(27.819,-82.675)->.a;area.a[\"boundary\"=\"protected_area\"];out tags;" "https://172.0.2.121/api/interpreter"
```
