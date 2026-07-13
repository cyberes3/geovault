"""
Unit tests for `KmlOptions.skip_null_geometry`.

GeoVault's own processors never pass this option (`KMLProcessor`/`KMZProcessor`
always call `kml(dom)` with the default options), so it's untested by the
golden-master corpus comparison, which only exercises what production
actually calls. It's still part of the public API surface (ported for the
"provide kml()/gpx() for manual use some day" requirement), so it's covered
here directly against small, real no-geometry fixtures pulled from the
symlinked corpus rather than upstream's dedicated `expectSkipNullGeometry`
fixtures (which GeoVault doesn't happen to have locally).
"""

import defusedxml.minidom as minidom

from geo_lib.togeojson import kml
from geo_lib.togeojson.kml.shared import KmlOptions


def _parse(path):
    return minidom.parseString(path.read_text(encoding="utf-8"))


class TestSkipNullGeometry:
    def test_default_keeps_null_geometry_feature(self, geovault_tests_dir):
        doc = _parse(geovault_tests_dir / "files from the togeojson repo" / "null_geometry.kml")
        result = kml(doc)
        assert len(result["features"]) == 1
        assert result["features"][0]["geometry"] is None
        assert result["features"][0]["properties"]["name"] == "Simple placemark"

    def test_skip_null_geometry_true_drops_the_feature(self, geovault_tests_dir):
        doc = _parse(geovault_tests_dir / "files from the togeojson repo" / "null_geometry.kml")
        result = kml(doc, KmlOptions(skip_null_geometry=True))
        assert result["features"] == []

    def test_skip_null_geometry_true_drops_placemark_nested_in_folder(self, geovault_tests_dir):
        doc = _parse(geovault_tests_dir / "files from the togeojson repo" / "nogeomplacemark.kml")
        result = kml(doc, KmlOptions(skip_null_geometry=True))
        assert result["features"] == []

    def test_skip_null_geometry_true_keeps_features_that_do_have_geometry(self, geovault_tests_dir):
        doc = _parse(geovault_tests_dir / "files from the togeojson repo" / "point.kml")
        default_result = kml(doc)

        doc = _parse(geovault_tests_dir / "files from the togeojson repo" / "point.kml")
        skip_result = kml(doc, KmlOptions(skip_null_geometry=True))

        assert len(skip_result["features"]) == len(default_result["features"])
        assert all(f["geometry"] is not None for f in skip_result["features"])
        assert skip_result == default_result

    def test_skip_null_geometry_is_false_by_default(self):
        assert KmlOptions().skip_null_geometry is False
