"""Geometry split, GPX route times, KML created, KMZ namespace prep."""

import defusedxml.minidom as minidom
from django.test import SimpleTestCase

from geo_lib.importing.readers.kml_prep import apply_kml_times, remove_kml_namespace_prefixes
from geo_lib.processing.geo import split_complex_geometries
from geo_lib.togeojson import gpx, kml


def _feature(geometry: dict, name: str = 'n') -> dict:
    return {
        'type': 'Feature',
        'geometry': geometry,
        'properties': {'name': name},
    }


class TestSplitComplexGeometries(SimpleTestCase):
    def test_geometry_collection_emits_all_children(self):
        feature = _feature({
            'type': 'GeometryCollection',
            'geometries': [
                {'type': 'Point', 'coordinates': [0.0, 0.0]},
                {'type': 'LineString', 'coordinates': [[0.0, 0.0], [1.0, 1.0]]},
                {'type': 'Polygon', 'coordinates': [[[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 0.0]]]},
            ],
        })
        parts = split_complex_geometries(feature)
        self.assertEqual([p['geometry']['type'] for p in parts], ['Point', 'LineString', 'Polygon'])

    def test_multipoint_splits_without_assert(self):
        feature = _feature({
            'type': 'MultiPoint',
            'coordinates': [[0.0, 0.0], [10.0, 20.0]],
        })
        parts = split_complex_geometries(feature)
        self.assertEqual(len(parts), 2)
        self.assertEqual([p['geometry']['type'] for p in parts], ['Point', 'Point'])

    def test_multipolygon_splits_without_assert(self):
        ring = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 0.0]]
        feature = _feature({
            'type': 'MultiPolygon',
            'coordinates': [[ring], [ring]],
        })
        parts = split_complex_geometries(feature)
        self.assertEqual(len(parts), 2)
        self.assertEqual([p['geometry']['type'] for p in parts], ['Polygon', 'Polygon'])

    def test_empty_geometry_collection_splits_to_zero(self):
        feature = _feature({'type': 'GeometryCollection', 'geometries': []})
        self.assertEqual(split_complex_geometries(feature), [])

    def test_null_geometry_skipped(self):
        self.assertEqual(split_complex_geometries({'type': 'Feature', 'geometry': None, 'properties': {}}), [])


class TestGpxRouteTimes(SimpleTestCase):
    def test_route_emits_coordinate_times(self):
        xml = """<?xml version="1.0"?>
        <gpx version="1.1" creator="test">
          <rte>
            <name>Route</name>
            <rtept lon="0.0" lat="0.0"><time>2020-01-01T00:00:00Z</time></rtept>
            <rtept lon="1.0" lat="1.0"><time>2020-01-01T00:01:00Z</time></rtept>
          </rte>
        </gpx>
        """
        collection = gpx(minidom.parseString(xml))
        self.assertEqual(len(collection['features']), 1)
        times = collection['features'][0]['properties']['coordinateProperties']['times']
        self.assertEqual(len(times), 2)


class TestKmlCreated(SimpleTestCase):
    def test_timestamp_maps_to_created(self):
        xml = """<?xml version="1.0"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <Placemark>
              <name>Stamp</name>
              <TimeStamp><when>2020-06-01T12:00:00Z</when></TimeStamp>
              <Point><coordinates>0.0,0.0,0</coordinates></Point>
            </Placemark>
          </Document>
        </kml>
        """
        collection = kml(minidom.parseString(xml))
        apply_kml_times(collection['features'][0])
        self.assertEqual(collection['features'][0]['properties']['created'], '2020-06-01T12:00:00Z')

    def test_timespan_maps_to_created(self):
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [0.0, 0.0]},
            'properties': {'timespan': {'begin': '2021-01-01T00:00:00Z', 'end': '2021-01-02T00:00:00Z'}},
        }
        apply_kml_times(feature)
        self.assertEqual(feature['properties']['created'], '2021-01-01T00:00:00Z')


class TestKmzNamespacePrep(SimpleTestCase):
    def test_prefixed_kml_matches_unprefixed_after_prep(self):
        prefixed = (
            '<kml:kml xmlns:kml="http://www.opengis.net/kml/2.2">'
            '<kml:Placemark><kml:name>X</kml:name></kml:Placemark></kml:kml>'
        )
        plain = '<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><name>X</name></Placemark></kml>'
        self.assertEqual(
            remove_kml_namespace_prefixes(prefixed).replace(' xmlns="http://www.opengis.net/kml/2.2"', ''),
            remove_kml_namespace_prefixes(plain).replace(' xmlns="http://www.opengis.net/kml/2.2"', ''),
        )
