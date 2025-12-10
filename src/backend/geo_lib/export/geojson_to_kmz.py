"""
Simple GeoJSON → KMZ conversion utilities.

This module intentionally supports a limited, practical subset of KML:
- Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon, GeometryCollection
- Basic name / description properties
- Simple point and line / polygon styles (stroke color/width, fill color/opacity)
- Point icons (linked via IconStyle href; optionally embedded into the KMZ archive)

The goal is to provide a small, dependency‑free helper that can be used by other
parts of the backend without dragging in the full import pipeline.
"""

from __future__ import annotations

import os
import zipfile
from dataclasses import dataclass
from io import BytesIO
from typing import Any, Dict, Iterable, List, Optional, Tuple, Union
from xml.etree import ElementTree as ET

ColorLike = Optional[str]


@dataclass
class KMZOptions:
    """
    Options controlling GeoJSON → KMZ conversion.

    - document_name: Title used for the root <Document> element.
    - embed_local_icons: If True, local icon files are copied into the KMZ and
      hrefs are rewritten to point at them (files/<basename>).
    - icon_base_path: Optional base directory used to resolve relative icon paths.
      If not provided, relative icon paths are resolved relative to CWD.
    """

    document_name: str = "GeoJSON Export"
    embed_local_icons: bool = False
    icon_base_path: Optional[str] = None


def geojson_to_kmz_bytes(
        geojson: Dict[str, Any],
        options: Optional[KMZOptions] = None,
) -> bytes:
    """
    Convert a GeoJSON Feature or FeatureCollection to a KMZ archive (as bytes).

    Args:
        geojson: Parsed GeoJSON dict.
        options: Optional KMZOptions for fine‑tuning output.

    Returns:
        KMZ file contents as bytes.
    """
    options = options or KMZOptions()

    # Normalize to feature list
    features: List[Dict[str, Any]] = _normalize_geojson_features(geojson)

    kml_bytes, embedded_files = _build_kml_and_collect_icons(features, options)

    # Package into KMZ (ZIP) in memory

    kmz_buffer = BytesIO()
    with zipfile.ZipFile(kmz_buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        # Main KML document
        zf.writestr("doc.kml", kml_bytes)

        # Any embedded icon files
        for arcname, src_path in embedded_files:
            try:
                zf.write(src_path, arcname)
            except FileNotFoundError:
                # If the icon disappeared between discovery and packaging, just skip it.
                continue

    return kmz_buffer.getvalue()


def geojson_to_kmz_file(
        geojson: Dict[str, Any],
        output_path: str,
        options: Optional[KMZOptions] = None,
) -> str:
    """
    Convert GeoJSON to a KMZ file on disk.

    Args:
        geojson: Parsed GeoJSON dict.
        output_path: Target KMZ path (e.g. "/tmp/export.kmz").
        options: Optional KMZOptions.

    Returns:
        The absolute path to the written KMZ file.
    """
    kmz_bytes = geojson_to_kmz_bytes(geojson, options=options)
    abs_path = os.path.abspath(output_path)
    with open(abs_path, "wb") as f:
        f.write(kmz_bytes)
    return abs_path


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _normalize_geojson_features(root: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Accept either a FeatureCollection, single Feature, or raw geometry and
    normalize into a simple feature list.
    """
    ftype = root.get("type")
    if ftype == "FeatureCollection":
        return list(root.get("features", []))
    if ftype == "Feature":
        return [root]

    # Raw geometry – wrap in a Feature with empty properties
    return [{"type": "Feature", "geometry": root, "properties": {}}]


def _build_kml_and_collect_icons(
        features: List[Dict[str, Any]],
        options: KMZOptions,
) -> Tuple[bytes, List[Tuple[str, str]]]:
    """
    Build a minimal KML document and collect any icon files that should be
    embedded in the KMZ.

    Returns:
        (kml_bytes, embedded_files)
        where embedded_files is a list of (archive_name, source_path).
    """
    ns = "http://www.opengis.net/kml/2.2"
    ET.register_namespace("", ns)

    kml = ET.Element(ET.QName(ns, "kml"))
    document = ET.SubElement(kml, ET.QName(ns, "Document"))

    name_el = ET.SubElement(document, ET.QName(ns, "name"))
    name_el.text = options.document_name

    embedded_files: List[Tuple[str, str]] = []

    # Flatten multi‑geometries / geometry collections into individual placemarks
    for feature in features:
        for geom, props in _explode_feature(feature):
            placemark = ET.SubElement(document, ET.QName(ns, "Placemark"))

            _apply_properties_to_placemark(placemark, props, ns)

            style_href, embedded = _build_style_for_feature(
                placemark,
                props,
                ns,
                options,
            )
            if embedded is not None:
                embedded_files.append(embedded)

            _append_geometry(placemark, geom, ns)

    # Serialize to bytes
    kml_bytes = ET.tostring(kml, encoding="utf-8", xml_declaration=True)
    return kml_bytes, embedded_files


def _explode_feature(feature: Dict[str, Any]) -> Iterable[Tuple[Dict[str, Any], Dict[str, Any]]]:
    """
    Yield (geometry, properties) pairs for a feature, expanding Multi* and
    GeometryCollection into multiple simple geometries.
    """
    geom = feature.get("geometry") or {}
    props = feature.get("properties") or {}
    gtype = (geom.get("type") or "").lower()

    if gtype == "geometrycollection":
        for sub in geom.get("geometries", []):
            yield sub, dict(props)
        return

    if gtype in {"multipoint", "multilinestring", "multipolygon"}:
        coords = geom.get("coordinates") or []
        base_type = gtype[len("multi"):].title()  # "Point"/"LineString"/"Polygon"
        for part in coords:
            sub_geom = {"type": base_type, "coordinates": part}
            yield sub_geom, dict(props)
        return

    yield geom, props


def _apply_properties_to_placemark(placemark: ET.Element, props: Dict[str, Any], ns: str) -> None:
    """Set basic name / description for a Placemark based on properties."""
    name = props.get("name") or "Unnamed Feature"
    desc = props.get("description") or ""

    # Add tags to description if present
    tags = props.get("tags")
    if tags and isinstance(tags, list):
        tags_str = ", ".join(str(t) for t in tags if t)
        if tags_str:
            if desc:
                desc += f"\n\nTags: {tags_str}"
            else:
                desc = f"Tags: {tags_str}"
    
    # Add system tags to description if present
    system_tags = props.get("system_tags")
    if system_tags and isinstance(system_tags, list):
        sys_tags_str = ", ".join(str(t) for t in system_tags if t)
        if sys_tags_str:
            if desc:
                desc += f"\nSystem Tags: {sys_tags_str}"
            else:
                desc = f"System Tags: {sys_tags_str}"

    name_el = ET.SubElement(placemark, ET.QName(ns, "name"))
    name_el.text = str(name)

    if desc:
        desc_el = ET.SubElement(placemark, ET.QName(ns, "description"))
        desc_el.text = str(desc)


def _build_style_for_feature(
        placemark: ET.Element,
        props: Dict[str, Any],
        ns: str,
        options: KMZOptions,
) -> Tuple[Optional[str], Optional[Tuple[str, str]]]:
    """
    Build an inline <Style> for a Placemark and return:
        (icon_href, embedded_file) where
            icon_href: final href used inside IconStyle (if any)
            embedded_file: optional (archive_name, source_path) pair for KMZ
    """
    style_el = ET.SubElement(placemark, ET.QName(ns, "Style"))

    embedded_file: Optional[Tuple[str, str]] = None

    # Point styling / icon
    icon_href, maybe_embedded = _resolve_icon_href(props, options)
    if icon_href:
        icon_style = ET.SubElement(style_el, ET.QName(ns, "IconStyle"))
        icon = ET.SubElement(icon_style, ET.QName(ns, "Icon"))
        href_el = ET.SubElement(icon, ET.QName(ns, "href"))
        href_el.text = icon_href
        if maybe_embedded is not None:
            embedded_file = maybe_embedded

    # Line / polygon styling
    stroke = props.get("stroke")
    stroke_width = props.get("stroke-width") or props.get("stroke_width")
    fill = props.get("fill")
    fill_opacity = props.get("fill-opacity") or props.get("fill_opacity")

    if stroke or stroke_width is not None:
        ls = ET.SubElement(style_el, ET.QName(ns, "LineStyle"))
        if stroke:
            color_el = ET.SubElement(ls, ET.QName(ns, "color"))
            color_el.text = _css_color_to_kml(stroke, opacity=None)
        if stroke_width is not None:
            width_el = ET.SubElement(ls, ET.QName(ns, "width"))
            width_el.text = str(stroke_width)

    if fill:
        ps = ET.SubElement(style_el, ET.QName(ns, "PolyStyle"))
        color_el = ET.SubElement(ps, ET.QName(ns, "color"))
        color_el.text = _css_color_to_kml(fill, opacity=fill_opacity)

    return icon_href, embedded_file


def _resolve_icon_href(
        props: Dict[str, Any],
        options: KMZOptions,
) -> Tuple[Optional[str], Optional[Tuple[str, str]]]:
    """
    Determine the best icon href for a feature.

    Returns:
        (href, embedded_file)
        where embedded_file is either None or (archive_name, source_path).
    """
    icon_keys = [
        "icon_href",
        "icon-href",
        "iconUrl",
        "icon_url",
        "marker_icon",
        "marker-icon",
        "icon",
    ]

    raw_icon: Optional[str] = None
    for key in icon_keys:
        if key in props and props[key]:
            raw_icon = str(props[key])
            break

    if not raw_icon:
        return None, None

    # Remote (or absolute) URL – just reference it directly
    lower = raw_icon.lower()
    if lower.startswith("http://") or lower.startswith("https://") or lower.startswith("data:"):
        return raw_icon, None

    # Local path – optionally embed in KMZ
    if not options.embed_local_icons:
        # Still reference it as‑is; caller is responsible for resolving the path.
        return raw_icon, None

    # Security: Prevent path traversal in icon paths
    # Reject paths containing .. or absolute paths
    if ".." in raw_icon or os.path.isabs(raw_icon):
        # Reject unsafe paths
        return None, None

    base = options.icon_base_path or os.getcwd()
    base_path = os.path.abspath(base)
    
    # Join and resolve the path
    src_path = os.path.join(base_path, raw_icon)
    # Normalize to resolve any remaining .. or . sequences
    src_path = os.path.normpath(src_path)
    
    # Security: Ensure the resolved path is within base directory
    if not src_path.startswith(base_path + os.sep) and src_path != base_path:
        # Path traversal detected - reject
        return None, None
    
    if not os.path.exists(src_path):
        # Fall back to just using the raw value as an href
        return raw_icon, None

    # Embed as files/<basename>
    # Security: Use only the basename to prevent path injection in archive
    arcname = os.path.join("files", os.path.basename(raw_icon))
    return arcname, (arcname, src_path)


def _append_geometry(placemark: ET.Element, geom: Dict[str, Any], ns: str) -> None:
    """Append the appropriate KML geometry element to the Placemark."""
    gtype = (geom.get("type") or "").lower()
    coords = geom.get("coordinates")

    if not gtype or coords is None:
        return

    if gtype == "point":
        point_el = ET.SubElement(placemark, ET.QName(ns, "Point"))
        coord_el = ET.SubElement(point_el, ET.QName(ns, "coordinates"))
        coord_el.text = _format_coord(coords)
    elif gtype == "linestring":
        ls_el = ET.SubElement(placemark, ET.QName(ns, "LineString"))
        coord_el = ET.SubElement(ls_el, ET.QName(ns, "coordinates"))
        coord_el.text = _format_coords_list(coords)
    elif gtype == "polygon":
        _append_polygon_geometry(placemark, coords, ns)
    else:
        # For any geometry type we don't explicitly handle, just skip for now.
        return


def _append_polygon_geometry(placemark: ET.Element, coords: Any, ns: str) -> None:
    """
    Append a Polygon geometry. Expects GeoJSON coordinate layout:
        [ exterior_ring, hole1, hole2, ... ]
    """
    if not isinstance(coords, list) or not coords:
        return

    poly_el = ET.SubElement(placemark, ET.QName(ns, "Polygon"))

    # Outer ring
    outer = ET.SubElement(poly_el, ET.QName(ns, "outerBoundaryIs"))
    outer_ring = ET.SubElement(outer, ET.QName(ns, "LinearRing"))
    outer_coords_el = ET.SubElement(outer_ring, ET.QName(ns, "coordinates"))
    outer_coords_el.text = _format_coords_list(coords[0])

    # Holes, if any
    for inner_ring_coords in coords[1:]:
        inner = ET.SubElement(poly_el, ET.QName(ns, "innerBoundaryIs"))
        inner_ring = ET.SubElement(inner, ET.QName(ns, "LinearRing"))
        inner_coords_el = ET.SubElement(inner_ring, ET.QName(ns, "coordinates"))
        inner_coords_el.text = _format_coords_list(inner_ring_coords)


def _format_coord(coord: Union[List[float], Tuple[float, ...]]) -> str:
    """Format a single [lon, lat] or [lon, lat, alt] coordinate for KML."""
    if not isinstance(coord, (list, tuple)) or len(coord) < 2:
        return ""
    lon, lat = coord[0], coord[1]
    if len(coord) >= 3:
        alt = coord[2]
        return f"{lon},{lat},{alt}"
    return f"{lon},{lat}"


def _format_coords_list(coords: Iterable[Iterable[float]]) -> str:
    """Format a list of coordinates into a KML coordinate string."""
    return " ".join(_format_coord(c) for c in coords if c is not None)


def _css_color_to_kml(color: ColorLike, opacity: Optional[Union[int, float]] = None) -> str:
    """
    Convert a simple CSS‑style #RRGGBB color and optional opacity to KML aabbggrr.

    If parsing fails, this returns a default opaque white.
    """
    if not isinstance(color, str):
        return "ffffffff"  # opaque white

    color = color.strip()
    if color.startswith("#") and len(color) == 7:
        rr = color[1:3]
        gg = color[3:5]
        bb = color[5:7]
    else:
        # Unknown format – bail out to white
        rr, gg, bb = "ff", "ff", "ff"

    # KML uses AA BB GG RR
    if opacity is None:
        aa = "ff"
    else:
        try:
            if isinstance(opacity, str):
                opacity = float(opacity)
            opacity = max(0.0, min(1.0, float(opacity)))
            aa = f"{int(opacity * 255 + 0.5):02x}"
        except (TypeError, ValueError):
            aa = "ff"

    return f"{aa}{bb}{gg}{rr}"
