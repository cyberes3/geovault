"""
Regression tests for a real bug found by manually stress-testing the port on
enormous/pathological input: a KML file only tens of KB in size, with ~1000
levels of nested `<MultiGeometry>`, crashed the converter with an unhandled
`RecursionError` -- not because of this port's own `get_geometry()` (which
was itself recursive and got fixed), but because CPython's stdlib
`xml.dom.minidom` implements `Element.getElementsByTagName()` /
`getElementsByTagNameNS()` (and `Element.normalize()`) as recursive tree
walks, and nearly every DOM traversal in this package went through those
methods via `find_all()`/`find_all_ns()`/`node_val()`. The identical failure
class also affected GPX's `get_extensions()` (recursing into nested
`gpxtpx:TrackPointExtension`) and this module's own `_text_content()`.

All of these were rewritten to use an explicit iterative stack instead of
recursion (see `geo_lib/togeojson/shared.py`'s module docstring and
`kml/geometry.py`'s `get_geometry()` docstring for the full rationale).
These tests pin that fix down: deliberately go *well* past Python's default
`sys.getrecursionlimit()` (1000) to prove the fix is a real structural one,
not just a higher ceiling.
"""

import sys

import defusedxml.minidom as minidom

from geo_lib.togeojson import kml, gpx

_DEPTH = 10_000
assert _DEPTH > 5 * sys.getrecursionlimit(), "test depth should comfortably exceed Python's recursion limit"


def _nested_multigeometry_kml(depth: int) -> str:
    return (
        '<?xml version="1.0"?>\n'
        '<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><name>deep</name>\n'
        + ("<MultiGeometry>" * depth)
        + "<Point><coordinates>-122.5,37.5,0</coordinates></Point>"
        + ("</MultiGeometry>" * depth)
        + "\n</Placemark></kml>\n"
    )


def _nested_track_point_extension_gpx(depth: int) -> str:
    return (
        '<?xml version="1.0"?>\n'
        '<gpx xmlns="http://www.topografix.com/GPX/1/1">\n'
        '<trk><trkseg><trkpt lat="47.0" lon="-122.0"><extensions>\n'
        + ('<gpxtpx:TrackPointExtension xmlns:gpxtpx="x">' * depth)
        + "<gpxtpx:hr>150</gpxtpx:hr>"
        + ("</gpxtpx:TrackPointExtension>" * depth)
        + "\n</extensions></trkpt>"
        + '<trkpt lat="47.001" lon="-122.001"></trkpt>'
        + "</trkseg></trk>\n</gpx>\n"
    )


class TestDeepKmlGeometryNesting:
    def test_ten_thousand_levels_of_nested_multigeometry_does_not_crash(self):
        doc = minidom.parseString(_nested_multigeometry_kml(_DEPTH))
        result = kml(doc)

        assert len(result["features"]) == 1
        feature = result["features"][0]
        assert feature["geometry"] == {
            "type": "Point",
            "coordinates": [-122.5, 37.5, 0],
        }

    def test_shallow_nesting_still_works_normally(self):
        doc = minidom.parseString(_nested_multigeometry_kml(3))
        result = kml(doc)
        assert result["features"][0]["geometry"]["type"] == "Point"


class TestDeepGpxExtensionNesting:
    def test_ten_thousand_levels_of_nested_track_point_extension_does_not_crash(self):
        doc = minidom.parseString(_nested_track_point_extension_gpx(_DEPTH))
        result = gpx(doc)

        assert len(result["features"]) == 1
        feature = result["features"][0]
        assert feature["properties"]["coordinateProperties"]["heart"] == [150.0, None]

    def test_shallow_nesting_still_works_normally(self):
        doc = minidom.parseString(_nested_track_point_extension_gpx(2))
        result = gpx(doc)
        assert result["features"][0]["properties"]["coordinateProperties"]["heart"] == [150.0, None]


class TestDeepTextContentNesting:
    """`_text_content()` (backing `node_val()`) is exercised by every
    text-bearing property lookup, independent of geometry/extensions."""

    def test_deeply_nested_plain_elements_inside_a_name_does_not_crash(self):
        xml = (
            '<?xml version="1.0"?>\n'
            '<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><name>'
            + ("<a>" * _DEPTH)
            + "deep text"
            + ("</a>" * _DEPTH)
            + "</name><Point><coordinates>0,0,0</coordinates></Point>"
            + "</Placemark></kml>\n"
        )
        doc = minidom.parseString(xml)
        result = kml(doc)
        assert result["features"][0]["properties"]["name"] == "deep text"
