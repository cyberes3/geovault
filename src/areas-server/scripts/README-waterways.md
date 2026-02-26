# Waterways import and filtering by length (including tributaries)

## Three ways to get rivers/canals into PostGIS

### 0a. Full planet: filter first, then DB (70GB OK on disk; DB stays small)

If you have (or will download) the full planet PBF (~70GB), filter it to rivers+canals **on disk first**; only that
smaller file is used for processing, and only the grouped result is loaded into PostGIS:

```bash
./scripts/planet-waterways-to-db.sh "postgresql://user:pass@host/db" /srv/downloads/planet-latest.osm.pbf
```

- Writes `<planet-basename>-waterways.osm.pbf` next to the planet (or pass a third argument for the path). The
  waterways-only file is typically a few GB.
- If that file already exists and is newer than the planet, the filter step is skipped.
- Then runs the grouped-waterways pipeline and imports only `waterways.world_major_waterways`. The 70GB never goes into the
  database.

### 0b. Build from a single PBF (no downloads)

`build-major-waterways.sh` builds grouped waterways and imports to PostGIS from **one** PBF you provide (e.g. North America + Europe combined, or any region/planet). It does not download anything.

```bash
./scripts/build-major-waterways.sh "postgresql://user:pass@host/db" /srv/downloads/north-america_western-europe_combined.osm.pbf
```

- No GeoJSON or KML output — loads directly into `waterways.world_major_waterways`. Option: `--min-upstream-km N` (default 50).

### 1. Raw segments (single region or merged PBF): `waterways.waterways`

Import individual OSM ways (each segment) with the flex config:

```bash
./scripts/import-waterways-pbf.sh "postgresql://user:pass@host/db" /srv/downloads/north-america_western-europe_combined.osm.pbf
```

- **Table:** `waterways.waterways`
- **Columns:** `osm_id`, `waterway` (river/canal), `name`, `tags`, `geom`, `created`
- **waterways.lua** is only used for this path; the major-waterways script does not need it.

## Filtering by length including tributaries

Use `max_upstream_m` to filter by total length (main + tributaries). Use `length_m` for main-stem only.

**Rivers with at least 500 km total (main + tributaries):**

```sql
SELECT tag_group_value         AS name,
       length_m / 1000.0       AS main_stem_km,
       max_upstream_m / 1000.0 AS total_with_tributaries_km
FROM waterways.world_major_waterways
WHERE max_upstream_m >= 500000
  AND tag_group_value IS NOT NULL
ORDER BY max_upstream_m DESC;
```

**Platte River (and its tributaries) by name:**

```sql
SELECT tag_group_value, length_m, max_upstream_m, geom
FROM waterways.world_major_waterways
WHERE tag_group_value ILIKE '%platte%';
```

**Top 20 by total network length:**

```sql
SELECT tag_group_value, max_upstream_m / 1000.0 AS total_km
FROM waterways.world_major_waterways
WHERE tag_group_value IS NOT NULL
ORDER BY max_upstream_m DESC
LIMIT 20;
```

The grouped table is built by `osm-lump-ways-down`, which connects OSM ways by topology and way direction, so “North
Platte”, “South Platte”, and “Platte River” end up in one network and `max_upstream_m` is the total length of that
network.

## Excluding feeder streams

**By OSM tag (streams):** The scripts above only pass **river** and **canal** into `osm-lump-ways-down` (via osmium and
the `-f waterway=river` / `-f waterway=canal` filters). So **`waterway=stream` is already excluded** — feeder streams
tagged as stream in OSM never enter the pipeline.

If your input PBF includes streams and you run `osm-lump-ways-down` yourself, you can exclude them with a tag-filter
file and `-F` (e.g. waterwaymap.org's `flowing_water_wo_streams.tagfilterfunc`, which excludes `waterway=stream` then
includes other flowing water).

**By size (small tributaries):** To drop very short branches, raise **`--min-upstream-m`** in `osm-lump-ways-down`. The
scripts use `--min-upstream-m 100` (metres). Increasing it (e.g. 1000 or 5000) keeps only segments with at least that
much upstream length, which trims small feeders.
