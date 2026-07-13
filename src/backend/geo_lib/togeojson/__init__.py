"""
Python port of `@tmcw/togeojson` v7.1.2 (https://github.com/placemark/togeojson).
See `NOTICE.md` in this package for required third-party (BSD-2-Clause)
attribution.

This is a deliberately partial, ground-up port: it only implements the KML and
GPX conversion paths that GeoVault actually exercises (Placemark, GroundOverlay,
NetworkLink, Track/Route/Waypoint). It does not implement TCX support or the
`kmlWithFolders` folder-tree API, since GeoVault does not use either.

Public API mirrors upstream's shape:

    from geo_lib.togeojson import togeojson, kml, gpx

    feature_collection = togeojson(document)  # auto-detects <kml> vs <gpx>
    feature_collection = kml(document, KmlOptions(skip_null_geometry=True))
    feature_collection = gpx(document)

`document` must be an `xml.dom.minidom.Document` (e.g. from
`defusedxml.minidom.parseString`), not a string.
"""

from geo_lib.togeojson.kml.converter import KmlConverter
from geo_lib.togeojson.kml.shared import KmlOptions
from geo_lib.togeojson.gpx.converter import GpxConverter

__all__ = ["togeojson", "kml", "gpx", "KmlOptions"]


def kml(document, options: KmlOptions | None = None) -> dict:
    """Convert a parsed KML `Document` into a GeoJSON `FeatureCollection`."""
    return KmlConverter(document, options).convert()


def gpx(document) -> dict:
    """Convert a parsed GPX `Document` into a GeoJSON `FeatureCollection`."""
    return GpxConverter(document).convert()


def togeojson(document, options: KmlOptions | None = None) -> dict:
    """
    Convert a parsed KML or GPX `Document` into a GeoJSON `FeatureCollection`,
    auto-detecting the format from the document's root element.
    """
    root = document.documentElement
    tag = root.tagName if root is not None else None
    if tag == "kml":
        return kml(document, options)
    if tag == "gpx":
        return gpx(document)
    raise ValueError(
        f"Unrecognized root element <{tag}>; expected <kml> or <gpx>"
    )
