"""
Unit tests for geo_lib.togeojson.kml.ground_overlay, focused on the
deliberate behavioral fix over upstream togeojson: `GroundOverlay` elements
using `LatLonQuad` are resolved regardless of whether the tag still has its
`gx:` namespace prefix.

Upstream only ever checks for the prefixed `gx:LatLonQuad` form. That's
correct for a raw KML DOM, but GeoVault strips all namespace prefixes from
standalone .kml uploads before parsing (see kml_processor.py's
_remove_namespaces(), replicated here as `_strip_namespace_prefixes`), so a
`gx:LatLonQuad`-based ground overlay would silently resolve to a `None`
geometry in production without this fix. KMZ uploads are NOT
namespace-stripped (see KMZProcessor.convert_to_geojson(), which bypasses
KMLProcessor's preprocessing), so both forms genuinely occur in practice
depending on upload path.
"""

import re

import defusedxml.minidom as minidom

from geo_lib.togeojson.kml.converter import KmlConverter
from geo_lib.togeojson.kml.shared import KmlOptions

_NAMESPACE_PREFIX_RE = re.compile(r"(</?)(\w+):")


def _strip_namespace_prefixes(content: str) -> str:
    """Mirrors kml_processor.py's _remove_namespaces()."""
    return _NAMESPACE_PREFIX_RE.sub(r"\1", content)


PREFIXED_GROUND_OVERLAY_XML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
<GroundOverlay>
  <name>Quad Overlay</name>
  <gx:LatLonQuad>
    <coordinates>
      -122.1,37.1 -122.0,37.1 -122.0,37.0 -122.1,37.0
    </coordinates>
  </gx:LatLonQuad>
</GroundOverlay>
</kml>
"""


class TestGroundOverlayLatLonQuad:
    def test_prefixed_gx_latlonquad_resolves_directly(self):
        """The un-stripped (KMZ-like) form works the same way it always
        did upstream: togeojson checks for `gx:LatLonQuad` directly."""
        doc = minidom.parseString(PREFIXED_GROUND_OVERLAY_XML)
        result = KmlConverter(doc).convert()
        assert len(result["features"]) == 1
        feature = result["features"][0]
        assert feature["geometry"]["type"] == "Polygon"
        assert feature["geometry"]["coordinates"][0][0] == [-122.1, 37.1]

    def test_stripped_latlonquad_still_resolves(self):
        """The fix under test: after GeoVault's namespace-prefix stripping
        turns `gx:LatLonQuad` into `LatLonQuad`, the geometry must still
        resolve instead of silently becoming null."""
        stripped_content = _strip_namespace_prefixes(PREFIXED_GROUND_OVERLAY_XML)
        assert "gx:LatLonQuad" not in stripped_content
        assert "<LatLonQuad>" in stripped_content

        doc = minidom.parseString(stripped_content)
        result = KmlConverter(doc).convert()
        assert len(result["features"]) == 1
        feature = result["features"][0]
        assert feature["geometry"]["type"] == "Polygon"
        assert feature["geometry"]["coordinates"][0][0] == [-122.1, 37.1]

    def test_stripped_and_unstripped_produce_identical_geometry(self):
        doc_prefixed = minidom.parseString(PREFIXED_GROUND_OVERLAY_XML)
        doc_stripped = minidom.parseString(_strip_namespace_prefixes(PREFIXED_GROUND_OVERLAY_XML))

        result_prefixed = KmlConverter(doc_prefixed).convert()
        result_stripped = KmlConverter(doc_stripped).convert()

        assert result_prefixed["features"][0]["geometry"] == result_stripped["features"][0]["geometry"]

    def test_skip_null_geometry_drops_ground_overlay_with_no_box(self):
        xml = """<?xml version="1.0"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <GroundOverlay><name>No box at all</name></GroundOverlay>
        </kml>
        """
        doc = minidom.parseString(xml)
        result = KmlConverter(doc, KmlOptions(skip_null_geometry=True)).convert()
        assert result["features"] == []


class TestGroundOverlayLatLonBox:
    def test_lat_lon_box_without_quad(self):
        xml = """<?xml version="1.0"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <GroundOverlay>
          <name>Box Overlay</name>
          <LatLonBox>
            <north>38.0</north>
            <south>37.0</south>
            <east>-122.0</east>
            <west>-123.0</west>
          </LatLonBox>
        </GroundOverlay>
        </kml>
        """
        doc = minidom.parseString(xml)
        result = KmlConverter(doc).convert()
        feature = result["features"][0]
        assert feature["geometry"]["type"] == "Polygon"
        assert feature["bbox"] == [-123.0, 37.0, -122.0, 38.0]
        # top-left, top-right, bottom-right, bottom-left, top-left (closed)
        assert feature["geometry"]["coordinates"][0][0] == [-123.0, 38.0]

    def test_lat_lon_box_with_rotation(self):
        xml = """<?xml version="1.0"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <GroundOverlay>
          <name>Rotated Overlay</name>
          <LatLonBox>
            <north>38.0</north>
            <south>37.0</south>
            <east>-122.0</east>
            <west>-123.0</west>
            <rotation>90</rotation>
          </LatLonBox>
        </GroundOverlay>
        </kml>
        """
        doc = minidom.parseString(xml)
        result = KmlConverter(doc).convert()
        feature = result["features"][0]
        # A 90-degree rotation about the box's center maps each corner to
        # the position 90 degrees further around the same circle; verified
        # independently against _rotate_box()'s own formula.
        expected = [
            [-123.0, 37.0],
            [-123.0, 38.0],
            [-122.0, 38.0],
            [-122.0, 37.0],
            [-123.0, 37.0],
        ]
        actual = feature["geometry"]["coordinates"][0]
        for actual_point, expected_point in zip(actual, expected):
            assert round(actual_point[0], 9) == expected_point[0]
            assert round(actual_point[1], 9) == expected_point[1]
