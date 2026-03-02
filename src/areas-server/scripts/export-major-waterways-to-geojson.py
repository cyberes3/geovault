#!/usr/bin/env python3
"""
Export waterways.major_waterways from Postgres to GeoJSON and build a static HTML map.

Streams rows so it does not load 11k+ features into memory. Simplifies line geometry
to ~1 vertex per 500 m to keep file size small. Outputs:
  - <out>.geojson  (GeoJSON FeatureCollection)
  - <out>.html     (self-contained map viewer; embeds GeoJSON for file:// use)

Usage (from src/areas-server):
  ./venv/bin/python scripts/export-major-waterways-to-geojson.py DATABASE_URL [output_basename]
  ./venv/bin/python scripts/export-major-waterways-to-geojson.py DATABASE_URL out --interval-m 500

Example:
  ./venv/bin/python scripts/export-major-waterways-to-geojson.py "$DATABASE_URL" major-waterways
  # Opens major-waterways.html in browser (GeoJSON embedded so file:// works).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from areas_lib import lookup_waterway

SCHEMA = lookup_waterway.WATERWAYS_SCHEMA
TABLE = lookup_waterway.TABLE_NAME


def _html_template(geojson_embed_json: str) -> str:
    return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Major waterways</title>
  <link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet">
  <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    #map { width: 100%; height: 100vh; }
    .maplibregl-popup-content { padding: 8px 12px; min-width: 120px; }
    .maplibregl-popup-content strong { display: block; margin-bottom: 4px; }
    .maplibregl-popup-content span { display: block; font-size: 12px; color: #555; }
  </style>
</head>
<body>
  <div id="map"></div>
  <script>
    const geojson = """ + geojson_embed_json + """;

    const map = new maplibregl.Map({
      container: 'map',
      style: {
        version: 8,
        sources: {
          osm: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          }
        },
        layers: [{ id: 'osm-tiles', type: 'raster', source: 'osm', minzoom: 0, maxzoom: 19 }]
      },
      center: [0, 20],
      zoom: 2
    });

    map.on('load', function () {
      map.addSource('waterways', { type: 'geojson', data: geojson });
      map.addLayer({
        id: 'waterways-line',
        type: 'line',
        source: 'waterways',
        paint: {
          'line-color': '#1e88e5',
          'line-width': 1.2,
          'line-opacity': 0.85
        }
      });
      map.addLayer({
        id: 'waterways-line-outline',
        type: 'line',
        source: 'waterways',
        paint: {
          'line-color': '#0d47a1',
          'line-width': 2.4,
          'line-opacity': 0.4
        }
      });
      map.on('click', 'waterways-line', function (e) {
        if (e.features.length) {
          const p = e.features[0].properties;
          const name = (p && p.name) ? p.name : '(unnamed)';
          const mToMi = 1 / 1609.344;
          const len = (p && p.length_m != null) ? (p.length_m * mToMi).toFixed(1) + ' mi' : '';
          const up = (p && p.max_upstream_m != null) ? (p.max_upstream_m * mToMi).toFixed(1) + ' mi system' : '';
          const html = '<strong>' + name + '</strong>' + (len ? '<span>' + len + '</span>' : '') + (up ? '<span>' + up + '</span>' : '');
          const geom = e.features[0].geometry;
          let c;
          if (geom.type === 'MultiLineString') {
            const line = geom.coordinates[0];
            c = line[Math.floor(line.length / 2)];
          } else {
            c = geom.coordinates[Math.floor(geom.coordinates.length / 2)];
          }
          new maplibregl.Popup({ closeButton: true }).setLngLat(c).setHTML(html).addTo(map);
        }
      });
      map.on('mouseenter', 'waterways-line', () => { map.getCanvas().style.cursor = 'pointer'; });
      map.on('mouseleave', 'waterways-line', () => { map.getCanvas().style.cursor = ''; });
    });
  </script>
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export major waterways to GeoJSON and a static HTML map (MapLibre GL)."
    )
    parser.add_argument("database", type=str, help="PostgreSQL connection string")
    parser.add_argument(
        "output_basename",
        type=str,
        nargs="?",
        default="major-waterways",
        help="Output base path without extension (default: major-waterways)",
    )
    parser.add_argument(
        "--interval-m",
        type=float,
        default=500,
        metavar="M",
        help="Simplify lines to ~1 vertex per M metres (default: 500); larger = smaller file",
    )
    args = parser.parse_args()
    conninfo = args.database.strip()
    if not conninfo:
        print("Error: database URL must be non-empty", file=sys.stderr)
        return 1

    out_base = Path(args.output_basename)
    geojson_path = out_base.with_suffix(".geojson")
    html_path = out_base.with_suffix(".html")

    with psycopg.connect(conninfo) as conn:
        if not lookup_waterway.table_exists(conn):
            print("Error: waterways.major_waterways table does not exist", file=sys.stderr)
            return 1
        interval_m = max(1.0, args.interval_m)
        # ST_Simplify(geometry, tolerance): tolerance in same units as geom (degrees for 4326)
        # ~111320 m per degree (latitude); use this to convert interval_m to degrees
        tolerance_deg = float(interval_m) / 111320.0
        with conn.cursor(name="waterways_export") as cur:
            cur.execute(
                f"""
                SELECT tag_group_value,
                       public.ST_Length(public.ST_Union(geom)::public.geography) AS length_m,
                       (array_agg(max_upstream_m ORDER BY length_m DESC NULLS LAST))[1] AS max_upstream_m,
                       ST_AsGeoJSON(
                         public.ST_Simplify(
                           public.ST_Union(geom),
                           %s
                         )
                       )::json
                FROM {SCHEMA}.{TABLE}
                GROUP BY tag_group_value
                ORDER BY (array_agg(max_upstream_m ORDER BY length_m DESC NULLS LAST))[1] DESC NULLS LAST
                """,
                (tolerance_deg,),
            )
            count = 0
            with open(geojson_path, "w", encoding="utf-8") as f:
                f.write('{"type":"FeatureCollection","features":[')
                first = True
                for row in cur:
                    name, length_m, max_upstream_m, geom_json = row
                    props = {}
                    if name:
                        props["name"] = name
                    if length_m is not None:
                        props["length_m"] = round(length_m, 1)
                        props["length_km"] = round(length_m / 1000, 1)
                    if max_upstream_m is not None:
                        props["max_upstream_m"] = round(max_upstream_m, 1)
                        props["max_upstream_km"] = round(max_upstream_m / 1000, 1)
                    feature = {"type": "Feature", "properties": props, "geometry": geom_json}
                    if not first:
                        f.write(",")
                    f.write(json.dumps(feature, separators=(",", ":")))
                    first = False
                    count += 1
                    if count % 2000 == 0:
                        print(f"  ... {count} features", file=sys.stderr)
                f.write("]}")

    print(f"Wrote: {geojson_path} ({count} features)")

    geojson_str = geojson_path.read_text(encoding="utf-8")
    # Escape for embedding in <script>: avoid </script> breaking out of the tag
    embed_safe = geojson_str.replace("</", "<\\/")
    html_path.write_text(_html_template(embed_safe), encoding="utf-8")
    print(f"Wrote: {html_path} (open in browser to view map)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
