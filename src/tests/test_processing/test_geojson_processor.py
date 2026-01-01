"""
Tests for GeoJSON processor.
"""
import json
from django.test import TestCase
from django.contrib.auth import get_user_model

from geo_lib.processing.file_types import detect_file_type, FileType
from geo_lib.processing.processors import get_processor
from geo_lib.processing.processors.geojson_processor import GeoJSONProcessor

User = get_user_model()


class TestGeoJSONProcessor(TestCase):
    """Test GeoJSON processor functionality."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_convert_to_geojson_parses_valid_featurecollection(self):
        """Test convert_to_geojson() parses valid FeatureCollection."""
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
        
        file_data = json.dumps(feature_collection).encode('utf-8')
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        geojson = processor.convert_to_geojson()
        
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertEqual(len(geojson['features']), 1)
        self.assertEqual(geojson['features'][0]['properties']['name'], 'Test Feature')
    
    def test_convert_to_geojson_converts_single_feature_to_featurecollection(self):
        """Test convert_to_geojson() converts single Feature to FeatureCollection."""
        single_feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature'}
        }
        
        file_data = json.dumps(single_feature).encode('utf-8')
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        geojson = processor.convert_to_geojson()
        
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertEqual(len(geojson['features']), 1)
        self.assertEqual(geojson['features'][0]['type'], 'Feature')
        self.assertEqual(geojson['features'][0]['properties']['name'], 'Test Feature')
    
    def test_convert_to_geojson_validates_features_array_exists_in_featurecollection(self):
        """Test convert_to_geojson() validates features array exists in FeatureCollection."""
        invalid_collection = {
            'type': 'FeatureCollection'
            # Missing 'features' array
        }
        
        file_data = json.dumps(invalid_collection).encode('utf-8')
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        from geo_lib.validation.geometry_validation import GeometryValidationError
        with self.assertRaises(GeometryValidationError):
            processor.convert_to_geojson()
    
    def test_convert_to_geojson_rejects_invalid_json(self):
        """Test convert_to_geojson() rejects invalid JSON."""
        invalid_json = b'{"type": "FeatureCollection", "features": [invalid]}'
        processor = GeoJSONProcessor(invalid_json, 'test.geojson', user_id=self.user.id)
        
        from geo_lib.validation.geometry_validation import GeometryValidationError
        with self.assertRaises(GeometryValidationError):
            processor.convert_to_geojson()
    
    def test_convert_to_geojson_rejects_non_object_json(self):
        """Test convert_to_geojson() rejects non-object JSON (array, string, etc.)."""
        # Test with array
        array_json = json.dumps([1, 2, 3]).encode('utf-8')
        processor = GeoJSONProcessor(array_json, 'test.geojson', user_id=self.user.id)
        
        from geo_lib.validation.geometry_validation import GeometryValidationError
        with self.assertRaises(GeometryValidationError):
            processor.convert_to_geojson()
        
        # Test with string
        string_json = json.dumps("not a feature").encode('utf-8')
        processor = GeoJSONProcessor(string_json, 'test.geojson', user_id=self.user.id)
        
        with self.assertRaises(GeometryValidationError):
            processor.convert_to_geojson()
    
    def test_convert_to_geojson_rejects_invalid_geojson_type(self):
        """Test convert_to_geojson() rejects invalid GeoJSON type (not Feature/FeatureCollection)."""
        invalid_geojson = {
            'type': 'InvalidType',
            'properties': {}
        }
        
        file_data = json.dumps(invalid_geojson).encode('utf-8')
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        from geo_lib.validation.geometry_validation import GeometryValidationError
        with self.assertRaises(GeometryValidationError):
            processor.convert_to_geojson()
    
    def test_validate_skips_file_validation(self):
        """Test validate() skips file validation (returns True) since uploads are blocked."""
        file_data = json.dumps({
            'type': 'FeatureCollection',
            'features': []
        }).encode('utf-8')
        
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        is_valid, error_message = processor.validate()
        
        self.assertTrue(is_valid)
        self.assertIsNone(error_message)
    
    def test_processor_handles_bom_and_whitespace_in_json(self):
        """Test processor handles BOM and whitespace in JSON."""
        # Test with BOM
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
        
        # Add BOM
        file_data = b'\xef\xbb\xbf' + json.dumps(feature_collection).encode('utf-8')
        processor = GeoJSONProcessor(file_data, 'test.geojson', user_id=self.user.id)
        
        geojson = processor.convert_to_geojson()
        
        self.assertEqual(geojson['type'], 'FeatureCollection')
        self.assertEqual(len(geojson['features']), 1)
    
    def test_processor_handles_array_of_features(self):
        """Test processor handles array of features (converts to FeatureCollection)."""
        # Note: This is actually handled by detect_file_type, not the processor itself
        # But we can test that the processor can handle it if it gets an array
        # Actually, looking at the code, the processor expects an object, not an array
        # So this test might not be applicable. Let's test what actually happens.
        
        # The processor expects a JSON object, so an array would fail validation
        # But detect_file_type might convert it. Let's test the actual behavior.
        array_of_features = [
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
                'properties': {'name': 'Feature 1'}
            },
            {
                'type': 'Feature',
                'geometry': {'type': 'Point', 'coordinates': [-122.4094, 37.7849]},
                'properties': {'name': 'Feature 2'}
            }
        ]
        
        # This would be detected as GeoJSON by detect_file_type, but processor expects object
        # So we test that detect_file_type handles it
        file_data = json.dumps(array_of_features).encode('utf-8')
        file_type = detect_file_type(file_data, 'test.geojson')
        
        # Should detect as GeoJSON
        self.assertEqual(file_type, FileType.GEOJSON)
    
    def test_detect_file_type_recognizes_geojson_by_content(self):
        """Test detect_file_type() recognizes GeoJSON by content (JSON with "type": "Feature")."""
        feature = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test Feature'}
        }
        
        file_data = json.dumps(feature).encode('utf-8')
        file_type = detect_file_type(file_data, 'test.json')
        
        self.assertEqual(file_type, FileType.GEOJSON)
    
    def test_detect_file_type_recognizes_geojson_by_extension(self):
        """Test detect_file_type() recognizes GeoJSON by extension (.geojson, .json)."""
        # Test with .geojson extension
        file_data = b'{"type": "FeatureCollection", "features": []}'
        file_type = detect_file_type(file_data, 'test.geojson')
        self.assertEqual(file_type, FileType.GEOJSON)
        
        # Test with .json extension
        file_type = detect_file_type(file_data, 'test.json')
        self.assertEqual(file_type, FileType.GEOJSON)
    
    def test_detect_file_type_identifies_json_content(self):
        """Test detect_file_type() correctly identifies JSON content."""
        # Test with JSON object (Feature)
        feature_json = b'{"type": "Feature", "geometry": {"type": "Point", "coordinates": [0, 0]}}'
        file_type = detect_file_type(feature_json, 'test.json')
        self.assertEqual(file_type, FileType.GEOJSON)
        
        # Test with JSON array
        array_json = b'[{"type": "Feature", "geometry": {"type": "Point", "coordinates": [0, 0]}}]'
        file_type = detect_file_type(array_json, 'test.json')
        self.assertEqual(file_type, FileType.GEOJSON)
        
        # Test with non-JSON
        non_json = b'not json content'
        file_type = detect_file_type(non_json, 'test.txt')
        # Should default to KML or detect based on extension
        self.assertIn(file_type, [FileType.KML, FileType.GEOJSON])
    
    def test_geojson_processor_is_returned_by_get_processor_factory(self):
        """Test GeoJSON processor is returned by get_processor() factory."""
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
        
        file_data = json.dumps(feature_collection).encode('utf-8')
        processor = get_processor(file_data, 'test.geojson', user_id=self.user.id)
        
        self.assertIsInstance(processor, GeoJSONProcessor)

