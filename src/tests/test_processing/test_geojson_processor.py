"""
Tests for GeoJSON FormatReader.
"""
import json
from types import SimpleNamespace

from django.test import TestCase

from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.readers.geojson import GeoJsonReader
from geo_lib.importing.readers.registry import read_upload
from geo_lib.importing.session import RawFile
from geo_lib.processing.file_types import FileType, detect_file_type


def _session(data, filename):
    if isinstance(data, str):
        data = data.encode('utf-8')
    return SimpleNamespace(raw_file=RawFile.from_bytes(data), filename=filename)


def _read(data, filename):
    return GeoJsonReader().read(_session(data, filename))


class TestGeoJsonReader(TestCase):
    def test_convert_parses_valid_featurecollection(self):
        feature_collection = {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                    'properties': {'name': 'Test Feature'}
                }
            ]
        }
        geojson = _read(json.dumps(feature_collection), 'test.geojson')
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertEqual(len(geojson['features']), 1)
        self.assertEqual(geojson['features'][0]['properties']['name'], 'Test Feature')

    def test_convert_wraps_single_feature(self):
        single_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature'}
        }
        geojson = _read(json.dumps(single_feature), 'test.geojson')
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertEqual(len(geojson['features']), 1)
        self.assertEqual(geojson['features'][0]['properties']['name'], 'Test Feature')

    def test_convert_requires_features_array(self):
        with self.assertRaises(ImportStageError):
            _read(json.dumps({'type': 'FeatureCollection'}), 'test.geojson')

    def test_convert_rejects_invalid_json(self):
        with self.assertRaises(ImportStageError):
            _read(b'{"type": "FeatureCollection", "features": [invalid]}', 'test.geojson')

    def test_convert_rejects_non_object_json(self):
        with self.assertRaises(ImportStageError):
            _read(json.dumps([1, 2, 3]), 'test.geojson')
        with self.assertRaises(ImportStageError):
            _read(json.dumps("not a feature"), 'test.geojson')

    def test_convert_rejects_invalid_geojson_type(self):
        with self.assertRaises(ImportStageError):
            _read(json.dumps({'type': 'InvalidType', 'properties': {}}), 'test.geojson')

    def test_detect_file_type_recognizes_geojson_by_content(self):
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature'}
        }
        file_type = detect_file_type(json.dumps(feature).encode('utf-8'), 'test.json')
        self.assertEqual(file_type, FileType.GEOJSON)

    def test_detect_file_type_recognizes_geojson_by_extension(self):
        file_data = b'{"type": "FeatureCollection", "features": []}'
        self.assertEqual(detect_file_type(file_data, 'test.geojson'), FileType.GEOJSON)
        self.assertEqual(detect_file_type(file_data, 'test.json'), FileType.GEOJSON)

    def test_registry_selects_geojson_reader(self):
        feature_collection = {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                    'properties': {'name': 'Test Feature'}
                }
            ]
        }
        geojson, file_type = read_upload(_session(json.dumps(feature_collection), 'test.geojson'))
        self.assertEqual(file_type, FileType.GEOJSON)
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertTrue(GeoJsonReader().supports('test.geojson', FileType.GEOJSON))
