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
cp -r rules /srv/overpass/databases/
chown -R overpass:overpass /srv/overpass/databases/rules
```

## Start Areas Dispatcher

You need a second dispatcher process specifically for areas.

```shell
sudo cp overpass-areas-*.* /etc/systemd/system/
sudo systemctl daemon-reload
```

```bash
systemctl enable overpass-areas-dispatcher.service
systemctl start overpass-areas-dispatcher.service
systemctl status overpass-areas-dispatcher.service
```

## Initial Area Generation

Before setting up the automatic service, you must run the initial full area generation. This creates the base area
data and can take 24 hours.

Make sure you copied the rules!

```shell
cd /srv/overpass
sudo -u overpass /usr/bin/rules_loop.sh databases
```

You can monitor progress by checking for area files being created:

```bash
watch -n 30 'ls -lh /srv/overpass/databases/area_*.bin 2>/dev/null | tail -5'
```

When the file `/srv/overpass/databases/area_version` is created, the generation
process has completed and you can `CTRL+C` the `rules_loop.sh` terminal.

## Set Up Automatic Area Updates

After initial generation completes, enable the automatic incremental update service:

```bash
systemctl enable overpass-areas-generator.timer
systemctl start overpass-areas-generator.timer
systemctl status overpass-areas-generator.timer
```

## Verify Areas Are Working

Once area generation completes, test with a simple query:

```bash
curl -k --data-urlencode "data=[out:json][timeout:10];(relation[\"boundary\"=\"protected_area\"](27.81,-82.68,27.83,-82.66);way[\"leisure\"=\"park\"](27.81,-82.68,27.83,-82.66););out tags;" "https://127.0.0.1/api/interpreter"
```

You may have to restart the `overpass-areas-dispatcher` and `overpass-dispatcher` services for them to load the new database.

## Sample combined reverse-geocoding query

The reverse geocoding system uses a single combined Overpass query per coordinate (admin boundaries, protected areas, lakes, cities). The union must be assigned to a set and output in a separate statement. Example for latitude 40.34, longitude -105.68 (use `-k` if your server uses a self-signed certificate):

```bash
time curl -k --data-urlencode 'data=[out:json][timeout:15];
(
  relation["boundary"="administrative"]["admin_level"~"2|4|6|8"](40.29,-105.73,40.39,-105.63);
  relation["boundary"="protected_area"](40.29,-105.73,40.39,-105.63);
  relation["leisure"="nature_reserve"](40.29,-105.73,40.39,-105.63);
  relation["boundary"="national_park"](40.29,-105.73,40.39,-105.63);
  relation["leisure"="park"](40.29,-105.73,40.39,-105.63);
  relation["landuse"="recreation_ground"](40.29,-105.73,40.39,-105.63);
  way["boundary"="protected_area"](40.29,-105.73,40.39,-105.63);
  way["leisure"="park"](40.29,-105.73,40.39,-105.63);
  way["landuse"="recreation_ground"](40.29,-105.73,40.39,-105.63);
  way["natural"="water"]["name"](around:1609,40.34,-105.68);
  relation["natural"="water"]["name"](around:1609,40.34,-105.68);
  way["water"="lake"]["name"](around:1609,40.34,-105.68);
  relation["water"="lake"]["name"](around:1609,40.34,-105.68);
  node["place"~"town|city|village"](around:8047,40.34,-105.68);
)->.all;
.all out geom center;
' "https://127.0.0.1/api/interpreter"
```

## Monitoring the Areas Updater

```shell
journalctl -b -u overpass-areas-generator.service

tail /srv/overpass/databases/rules_loop.log

# List area files with timestamps
ls -lht /srv/overpass/databases/area_*.bin 2>/dev/null | head -10

cat /srv/overpass/databases/area_version

# Check modification time
ls -lh /srv/overpass/databases/area_version
```