# Waterways import and filtering by length (including tributaries)

## Major waterways: `waterways.world_major_waterways`

### Build from PBF

`build-major-waterways.sh` builds grouped major rivers and canals from a PBF and loads into PostGIS. GeoJSON is stored in `--local-dir` (default `/srv/downloads`). Use `--load` to skip building and load from existing GeoJSON.

```bash
./scripts/build-major-waterways.sh "postgresql://user:pass@host/db" /srv/downloads/north-america_western-europe_combined.osm.pbf
./scripts/build-major-waterways.sh "postgresql://user:pass@host/db" --load --local-dir /srv/downloads
```

- **Options:** `--local-dir DIR`, `--load` (use existing GeoJSON), `--min-upstream-km N` (default 50)
- **Table:** `waterways.world_major_waterways`

### Full planet (filter first)

If you have the full planet PBF, filter to waterways on disk first so the 70GB never hits the DB:

```bash
./scripts/planet-waterways-to-db.sh "postgresql://user:pass@host/db" /srv/downloads/planet-latest.osm.pbf
```

(Then use `build-major-waterways.sh --load` if that script writes the same table.)

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
scripts use `--min-upstream-m 1000` (metres). Increasing it (e.g. 1000 or 5000) keeps only segments with at least that
much upstream length, which trims small feeders.
