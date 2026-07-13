"""
Port of the document-level conversion logic in togeojson's `lib/kml.ts`
(`buildSchema`/`kmlGen`/`kml`), wrapped in a `KmlConverter` class that owns
the per-document `StyleMap` and `Schema` state instead of threading them
through closures.

Deliberately not ported: `kmlWithFolders`/`Folder`/`Root` (GeoVault has no
use for the folder-tree representation, only the flat feature list that
`kml()` produces) and TCX support (GeoVault never accepts TCX uploads).
"""

from __future__ import annotations

from typing import Generator

from geo_lib.togeojson.kml.ground_overlay import get_ground_overlay
from geo_lib.togeojson.kml.networklink import get_network_link
from geo_lib.togeojson.kml.placemark import get_placemark
from geo_lib.togeojson.kml.shared import KmlOptions, Schema, type_converters
from geo_lib.togeojson.kml.style_map import StyleMap
from geo_lib.togeojson.shared import find_all


def build_schema(document) -> Schema:
    schema: Schema = {}
    for field_el in find_all(document, "SimpleField"):
        name = field_el.getAttribute("name") or ""
        type_name = field_el.getAttribute("type") or ""
        schema[name] = type_converters.get(type_name, type_converters["string"])
    return schema


class KmlConverter:
    """Converts a single parsed KML `Document` into a GeoJSON
    `FeatureCollection`. Builds the document's style map and `SimpleField`
    schema once at construction time, since both are referenced by every
    feature in the document."""

    def __init__(self, document, options: KmlOptions | None = None) -> None:
        self.document = document
        self.options = options or KmlOptions()
        self.style_map = StyleMap.build(document)
        self.schema = build_schema(document)

    def iter_features(self) -> Generator[dict, None, None]:
        for placemark in find_all(self.document, "Placemark"):
            feature = get_placemark(placemark, self.style_map, self.schema, self.options)
            if feature:
                yield feature

        for ground_overlay in find_all(self.document, "GroundOverlay"):
            feature = get_ground_overlay(ground_overlay, self.style_map, self.schema, self.options)
            if feature:
                yield feature

        for network_link in find_all(self.document, "NetworkLink"):
            feature = get_network_link(network_link, self.style_map, self.schema, self.options)
            if feature:
                yield feature

    def convert(self) -> dict:
        return {"type": "FeatureCollection", "features": list(self.iter_features())}
