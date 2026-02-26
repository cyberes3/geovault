#!/usr/bin/env python3
"""
Download OpenSkiMap ski_areas.geojson and convert it to KML.
Always downloads the GeoJSON (no cache). Applies a 500 ft buffer to each geometry. Writes a single KML file with one Placemark per feature.

Usage (from src/areas-server):
  python scripts/ski-areas-geojson-to-kml.py --output ski_areas.kml
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import Request, urlopen

import tqdm
from pyproj import CRS, Transformer
from shapely import make_valid
from shapely.geometry import mapping, shape
from shapely.ops import transform

SKI_AREAS_URL = "https://tiles.openskimap.org/geojson/ski_areas.geojson"
USER_AGENT = "GeoVault-SkiAreasToKml/1.0"
DOWNLOAD_TIMEOUT = 300
KML_XMLNS = "http://www.opengis.net/kml/2.2"
BUFFER_FEET = 800
BUFFER_METERS = BUFFER_FEET * 0.3048


def download_geojson(url: str) -> bytes | None:
    req = Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp:
            return resp.read()
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return None


def _buffer_geometry(geom: dict) -> dict | None:
    """Buffer geometry by BUFFER_METERS in a local UTM CRS; return GeoJSON-like dict or None if invalid."""
    try:
        shp = shape(geom)
    except Exception:
        return None
    if shp.is_empty or not shp.is_valid:
        shp = shp.buffer(0) if hasattr(shp, "buffer") else shp
        if not shp.is_valid:
            try:
                shp = make_valid(shp)
            except Exception:
                pass
        if shp.is_empty:
            return None
    try:
        centroid = shp.centroid
        lon, lat = centroid.x, centroid.y
    except Exception:
        return None
    zone = int((lon + 180) / 6) + 1
    zone = max(1, min(60, zone))  # UTM zone 1-60
    north = lat >= 0
    utm_crs = CRS.from_proj4(
        f"+proj=utm +zone={zone} +ellps=WGS84 +datum=WGS84 +units=m +north={north} +no_defs"
    )
    to_utm = Transformer.from_crs("EPSG:4326", utm_crs, always_xy=True)
    from_utm = Transformer.from_crs(utm_crs, "EPSG:4326", always_xy=True)
    try:
        shp_utm = transform(to_utm.transform, shp)
        shp_buf = shp_utm.buffer(BUFFER_METERS)
        shp_wgs = transform(from_utm.transform, shp_buf)
    except Exception:
        return None
    if shp_wgs.is_empty:
        return None
    return mapping(shp_wgs)


def _name_from_properties(prop: dict) -> str:
    name = prop.get("name")
    if name and isinstance(name, str) and name.strip():
        return name.strip()
    return "unnamed"


def _format_coord(coord: list | tuple) -> str:
    if not isinstance(coord, (list, tuple)) or len(coord) < 2:
        return ""
    lon, lat = coord[0], coord[1]
    if len(coord) >= 3:
        return f"{lon},{lat},{coord[2]}"
    return f"{lon},{lat}"


def _format_coords_list(coords: list | tuple) -> str:
    return " ".join(_format_coord(c) for c in coords if c is not None)


def _geom_to_geojson_dict(geom: dict) -> dict:
    """Normalize geometry dict so coordinates are lists (KML/GeoJSON expect consistent types). Shapely mapping() may return tuples."""
    gtype = (geom.get("type") or "").strip()
    coords = geom.get("coordinates")
    if coords is None:
        return geom
    if gtype == "Point":
        return {"type": gtype, "coordinates": list(coords) if isinstance(coords, tuple) else coords}
    if gtype == "Polygon":
        if not isinstance(coords, (list, tuple)) or not coords:
            return geom
        return {
            "type": gtype,
            "coordinates": [[list(c) if isinstance(c, tuple) else c for c in coords[0]]]
            + [[list(c) if isinstance(c, tuple) else c for c in ring] for ring in coords[1:]],
        }
    if gtype == "MultiPolygon":
        if not isinstance(coords, (list, tuple)):
            return geom
        return {
            "type": gtype,
            "coordinates": [
                [[list(c) if isinstance(c, tuple) else c for c in ring_list[0]]]
                + [[list(c) if isinstance(c, tuple) else c for c in ring] for ring in ring_list[1:]]
                for ring_list in coords
            ],
        }
    return geom


def _append_geometry(placemark: ET.Element, geom: dict) -> None:
    gtype = (geom.get("type") or "").strip().lower()
    coords = geom.get("coordinates")
    if not gtype or coords is None:
        return
    if gtype == "point":
        point_el = ET.SubElement(placemark, "Point")
        coord_el = ET.SubElement(point_el, "coordinates")
        coord_el.text = _format_coord(coords)
    elif gtype == "polygon":
        if not isinstance(coords, (list, tuple)) or not coords:
            return
        poly_el = ET.SubElement(placemark, "Polygon")
        outer = ET.SubElement(poly_el, "outerBoundaryIs")
        outer_ring = ET.SubElement(outer, "LinearRing")
        outer_coords_el = ET.SubElement(outer_ring, "coordinates")
        outer_coords_el.text = _format_coords_list(coords[0])
        for inner_ring_coords in coords[1:]:
            inner = ET.SubElement(poly_el, "innerBoundaryIs")
            inner_ring = ET.SubElement(inner, "LinearRing")
            inner_coords_el = ET.SubElement(inner_ring, "coordinates")
            inner_coords_el.text = _format_coords_list(inner_ring_coords)
    elif gtype == "multipolygon":
        if not isinstance(coords, (list, tuple)):
            return
        for ring_list in coords:
            if not isinstance(ring_list, (list, tuple)) or not ring_list:
                continue
            poly_el = ET.SubElement(placemark, "Polygon")
            outer = ET.SubElement(poly_el, "outerBoundaryIs")
            outer_ring = ET.SubElement(outer, "LinearRing")
            outer_coords_el = ET.SubElement(outer_ring, "coordinates")
            outer_coords_el.text = _format_coords_list(ring_list[0])
            for inner_ring_coords in ring_list[1:]:
                inner = ET.SubElement(poly_el, "innerBoundaryIs")
                inner_ring = ET.SubElement(inner, "LinearRing")
                inner_coords_el = ET.SubElement(inner_ring, "coordinates")
                inner_coords_el.text = _format_coords_list(inner_ring_coords)


def geojson_to_kml(fc: dict) -> bytes:
    # Use unprefixed elements and default xmlns so output is standard KML
    kml = ET.Element("kml")
    kml.set("xmlns", KML_XMLNS)
    document = ET.SubElement(kml, "Document")
    name_el = ET.SubElement(document, "name")
    name_el.text = "Ski Areas (OpenSkiMap)"

    features = fc.get("features") or []
    for feat in tqdm.tqdm(features, desc="Converting to KML", unit="feature"):
        if feat.get("type") != "Feature":
            continue
        prop = feat.get("properties") or {}
        status = prop.get("status")
        if status is not None and status != "operating":
            continue
        geom = feat.get("geometry")
        if not geom:
            continue
        gtype = (geom.get("type") or "").strip()
        if gtype not in ("Polygon", "MultiPolygon"):
            continue
        geom_out = _buffer_geometry(geom)
        if geom_out is None:
            geom_out = geom  # fallback to unbuffered so we still output polygons
        geom_out = _geom_to_geojson_dict(geom_out)
        # Only output polygons (buffer/transform can sometimes yield a Point from degenerate geometry)
        if (geom_out.get("type") or "").strip().lower() not in ("polygon", "multipolygon"):
            continue
        placemark = ET.SubElement(document, "Placemark")
        name_el = ET.SubElement(placemark, "name")
        name_el.text = _name_from_properties(prop)
        _append_geometry(placemark, geom_out)

    return ET.tostring(kml, encoding="utf-8", xml_declaration=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download OpenSkiMap ski_areas.geojson and convert to KML. Always downloads (no cache)."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("ski_areas.kml"),
        help="Output KML file path (default: ski_areas.kml)",
    )
    args = parser.parse_args()

    print("Downloading ski_areas.geojson ...", file=sys.stderr)
    geojson_bytes = download_geojson(SKI_AREAS_URL)
    if geojson_bytes is None:
        sys.exit(1)

    try:
        fc = json.loads(geojson_bytes.decode("utf-8"))
    except Exception as e:
        print(f"Failed to parse GeoJSON: {e}", file=sys.stderr)
        sys.exit(1)
    if fc.get("type") != "FeatureCollection" or "features" not in fc:
        print("GeoJSON is not a FeatureCollection with features.", file=sys.stderr)
        sys.exit(1)

    n = len(fc.get("features") or [])
    print(f"Converting {n} features to KML ...", file=sys.stderr)
    kml_bytes = geojson_to_kml(fc)

    out_path = args.output
    out_path.write_bytes(kml_bytes)
    print(f"Wrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
