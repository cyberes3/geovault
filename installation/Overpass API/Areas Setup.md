# Overpass API Area Data Setup

This guide explains how to enable and generate area data in your Overpass API instance, which is required for `is_in()`
queries used by the reverse geocoding system.

## Prerequisites

- Overpass API is installed and running
- Base OSM data is loaded (nodes, ways, relations)
- Dispatcher service is running

## Copy Rules Directory

The rules directory contains scripts that define which OSM objects become areas.

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

Enable and start the areas dispatcher:

```bash
sudo systemctl enable overpass-areas-dispatcher.service
sudo systemctl start overpass-areas-dispatcher.service
sudo systemctl status overpass-areas-dispatcher.service
```

## Initial Area Generation

**Before setting up the automatic service, you must run the initial full area generation.** This creates the base area
data and can take 4-12+ hours depending on your data size.

```bash
sudo -u overpass /usr/bin/rules_loop.sh /srv/overpass/databases
```

This command will run until completion. You can monitor progress by checking for area files:

```bash
# Watch for area files being created (in another terminal)
watch -n 30 'ls -lh /srv/overpass/databases/area_*.bin 2>/dev/null | tail -5'

# Check area version file (created when generation completes)
cat /srv/overpass/databases/area_version
```

## Set Up Automatic Area Updates

After initial generation completes, enable the automatic incremental update service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable overpass-areas-generator.timer
sudo systemctl start overpass-areas-generator.timer
sudo systemctl status overpass-areas-generator.timer
```

The timer service uses `rules_delta_loop.sh` which only updates changed areas, making subsequent runs much faster (
typically minutes instead of hours).

## Verify Areas Are Working

Once area generation completes, test with a simple query:

```bash
curl -k --data-urlencode "data=[out:json];is_in(27.819,-82.675)->.a;area.a[\"boundary\"=\"protected_area\"];out tags;" "https://172.0.2.121/api/interpreter"
```
