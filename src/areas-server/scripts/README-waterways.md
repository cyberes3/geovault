# Waterways import and filtering by length (including tributaries)

## Two ways to get rivers/canals into PostGIS

### 1. Raw segments: `waterways.waterways`

Import individual OSM ways (each segment) with the flex config:

```bash
./scripts/import-waterways-pbf.sh "postgresql://user:pass@host/db" /srv/downloads/north-america_western-europe_combined.osm.pbf
```

- **Table:** `waterways.waterways`
- **Columns:** `osm_id`, `waterway` (river/canal), `name`, `tags`, `geom`, `created`
- **Use when:** You need segment-level data or don’t care about tributary length.

### 2. Grouped systems (main stem + tributaries): `waterways.grouped_waterways`

Build one feature per river system (e.g. Platte River = main stem + North Platte + South Platte) and get **length including tributaries**:

```bash
# Requires: cargo install osm-lump-ways, osmium-tool, GDAL (ogr2ogr)
./scripts/build-grouped-waterways.sh "postgresql://user:pass@host/db" /srv/downloads/north-america_western-europe_combined.osm.pbf
```

- **Table:** `waterways.grouped_waterways`
- **Important columns:**
  - `tag_group_value` — name of the river system (e.g. `"Platte River"`)
  - `length_m` — main-stem length (metres)
  - `max_upstream_m` — **total length including all tributaries** (metres)
  - `geom` — MultiLineString (WGS84)

## Filtering by length including tributaries

Use `max_upstream_m` to filter by total length (main + tributaries). Use `length_m` for main-stem only.

**Rivers with at least 500 km total (main + tributaries):**

```sql
SELECT tag_group_value AS name,
       length_m / 1000.0 AS main_stem_km,
       max_upstream_m / 1000.0 AS total_with_tributaries_km
FROM waterways.grouped_waterways
WHERE max_upstream_m >= 500000
  AND tag_group_value IS NOT NULL
ORDER BY max_upstream_m DESC;
```

**Platte River (and its tributaries) by name:**

```sql
SELECT tag_group_value, length_m, max_upstream_m, geom
FROM waterways.grouped_waterways
WHERE tag_group_value ILIKE '%platte%';
```

**Top 20 by total network length:**

```sql
SELECT tag_group_value, max_upstream_m / 1000.0 AS total_km
FROM waterways.grouped_waterways
WHERE tag_group_value IS NOT NULL
ORDER BY max_upstream_m DESC
LIMIT 20;
```

The grouped table is built by `osm-lump-ways-down`, which connects OSM ways by topology and way direction, so “North Platte”, “South Platte”, and “Platte River” end up in one network and `max_upstream_m` is the total length of that network.
