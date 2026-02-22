# Original `is_in()` Overpass Queries (Pre–Bbox Refactor)

Before we switched to a single combined **bbox-based** Overpass query (to avoid high memory use on our Overpass server),
reverse geocoding used Overpass’s **`is_in(lat, lon)`** to find areas containing a point. That meant **two separate
Overpass requests** per coordinate: one for administrative boundaries and one for protected areas. Cities, lakes, and
ski resorts used other queries (e.g. `around`).

We stopped using `is_in()` because a single such query can use **9–12+ GB RAM** on the Overpass server; on a 20 GB
machine the process was sometimes **OOM-killed** even with `[maxsize:12Gi]`. See `installation/Overpass API/README.md`
for RAM requirements and Areas setup.

---

## 1. Administrative boundaries

**Module:** `admin_boundaries.py` (before refactor)  
**Purpose:** Country, state, county, city for a point.

**Overpass QL:**

```overpass
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"]["boundary"="administrative"];
  area.a["admin_level"="4"]["boundary"="administrative"];
  area.a["admin_level"="6"]["boundary"="administrative"];
  area.a["admin_level"="8"]["boundary"="administrative"];
);
out tags;
```

- **`is_in(lat, lon)->.a`** fills the set `.a` with **all areas** that contain the point (using the Overpass “areas”
  dataset built from relations).
- **`area.a["admin_level"="2"]...`** etc. filter that set to administrative boundaries with levels 2 (country), 4 (
  state), 6 (county), 8 (city).
- **Output:** Tags only (no geometry in this query).

**Python (original):**

```python
query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["admin_level"="2"]["boundary"="administrative"];
  area.a["admin_level"="4"]["boundary"="administrative"];
  area.a["admin_level"="6"]["boundary"="administrative"];
  area.a["admin_level"="8"]["boundary"="administrative"];
);
out tags;
"""
response, error = query_overpass(query, latitude=latitude, longitude=longitude)
# then parse elements[].tags for admin_level → country/state/county/city
```

---

## 2. Protected areas (parks, reserves, etc.)

**Module:** `protected_areas.py` (before refactor)  
**Purpose:** National parks, state parks, nature reserves, parks, recreation grounds containing the point.

**Overpass QL:**

```overpass
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
  area.a["leisure"="park"];
  area.a["landuse"="recreation_ground"];
);
out tags;
```

- Same idea: **`is_in(lat, lon)->.a`** gets all areas containing the point; the union filters to protected-area–style
  tags.
- **Output:** Tags only.

**Python (original):**

```python
query = f"""
[out:json];
is_in({latitude},{longitude})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
  area.a["leisure"="park"];
  area.a["landuse"="recreation_ground"];
);
out tags;
"""
response, error = query_overpass(query, latitude=latitude, longitude=longitude)
# then parse elements[].tags and classify via classify_protected_area()
```

---

## How the app used them

- **`get_location_tags(lat, lon)`** ran **three** Overpass-related things in parallel (via
  `ThreadPoolExecutor(max_workers=3)`):
    1. `get_admin_hierarchy(lat, lon)` → **one** `is_in()` admin query
    2. `get_protected_areas(lat, lon)` → **one** `is_in()` protected-areas query
    3. `search_nearby_lakes(lat, lon)` → lakes query (e.g. `around`)

- Cities came from admin (level 8) or a separate `find_nearby_cities()` call; ski resorts from a separate search.

So **each** reverse-geocode could trigger **two** heavy `is_in()` requests (admin + protected). The Overpass server
needs the **areas** dataset (second dispatcher + area cache) for `is_in()` to work at all.

---

## Why we moved off `is_in()`

- **Memory:** A single `is_in()` over the areas DB can use **9–12+ GB RAM** per query. With `[maxsize:8Gi]` we hit OOM;
  with `[maxsize:12Gi]` on a 20 GB server the process was still sometimes **Killed** (total RAM exhausted).
- **Recommendation in our install doc:** 32 GB RAM if you use areas and run heavy `is_in()`; 20 GB is often too low.
- **Current approach:** One **bbox-based** combined query (no `is_in()`), with point-in-polygon filtering in our code.
  No areas dispatcher required; lower and more predictable memory use.

---

## Where we should really be using `is_in()` (or equivalent)

The **bbox query is a workaround**, not the right abstraction. These are the situations where a true “point in area”
lookup is what we want:

1. **Administrative boundaries (country, state, county, city)**  
   The question is literally “which admin areas contain this point?” — one answer per level. **`is_in()`** (or a PostGIS
   `ST_Contains` over admin polygons) is the correct operation. The bbox approach works only because we then do
   point-in-polygon on the returned relations; if the bbox is too small (or the relation isn’t returned because no
   member node falls in the bbox), we can miss or mis-assign admin. **`is_in()`** would always return the containing
   areas.

2. **Protected areas (parks, reserves, etc.)**  
   Same idea: “which protected areas contain this point?” **`is_in()`** is the right query. The bbox approach is messy
   because:
    - Overpass returns a **relation** only if at least one **member** (node or way segment) lies in the bbox. For large
      parks with coarse boundaries (e.g. Yellowstone), we had to increase the bbox to 0.5° so that some boundary
      geometry fell inside; with 0.25° we got nothing.
    - Very large parks (e.g. Northeast Greenland, some Alaska parks) may still need an even larger bbox or a “mega-park
      fallback” list. **`is_in()`** would return them regardless of boundary node density.

3. **When to use `is_in()` vs bbox in practice**
    - **Use `is_in()` (Overpass)** when you have an Overpass instance with enough RAM (e.g. 32 GB, areas enabled) and
      you want correct “containing area” results without tuning bbox size or maintaining exception lists.
    - **Use “our own `is_in()`”** (e.g. **PostGIS** with osm2pgsql-loaded admin + protected-area polygons): same
      semantics as `is_in()` — `ST_Contains(geometry, point)` — with no Overpass RAM cost and no bbox size edge cases.
      That’s the long-term place where we should *really* be doing point-in-area for admin and protected areas.
    - **Use the current bbox query** only as a fallback when Overpass RAM or areas are not available, accepting that we
      may miss very large or coarsely-mapped areas without a larger bbox or a fallback list.

**Summary:** For “which areas contain this point?”, **`is_in()` (or PostGIS containment) is the right tool**. The messy
bbox query is a pragmatic workaround for Overpass memory limits; wherever we can (bigger server or our own polygon DB),
we should use true point-in-area lookups instead.

---

## Optional: `[timeout]` and `[maxsize]` for `is_in()` (server-side)

If you ever run these queries again (e.g. on a 32 GB server with areas enabled), you can add settings in the query
preamble, for example:

```overpass
[out:json][timeout:60][maxsize:12Gi];
is_in({latitude},{longitude})->.a;
...
```

(Our original code did not embed `[timeout]` or `[maxsize]` in the query string; the client used
`settings.OVERPASS_API_TIMEOUT` for HTTP timeout only.)

---

## Reference: test script

`scripts/test_overpass_official_rmnp.py` still contains a minimal **protected-areas-only** `is_in()` example for
comparison with the bbox query:

```overpass
[out:json][timeout:25];
is_in({LAT},{LON})->.a;
(
  area.a["boundary"="protected_area"];
  area.a["leisure"="nature_reserve"];
  area.a["boundary"="national_park"];
  area.a["leisure"="park"];
  area.a["landuse"="recreation_ground"];
);
out tags;
```

That script runs both the bbox combined query and this `is_in()` query against a given server for RMNP (40.34, -105.68).
