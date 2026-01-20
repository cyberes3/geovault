from django.test import SimpleTestCase
from geo_lib.websocket.modules.file_duplicate_utils import check_all_features_duplicate

class TestCheckAllFeaturesDuplicate(SimpleTestCase):
    def test_single_feature_duplicate(self):
        geofeatures = [{'properties': {'geojson_hash': 'hash1'}}]
        duplicate_features = [{'feature': {'properties': {'geojson_hash': 'hash1'}}}]
        self.assertTrue(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_single_feature_not_duplicate(self):
        geofeatures = [{'properties': {'geojson_hash': 'hash1'}}]
        duplicate_features = [{'feature': {'properties': {'geojson_hash': 'hash2'}}}]
        self.assertFalse(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_multiple_features_all_duplicate(self):
        geofeatures = [
            {'properties': {'geojson_hash': 'hash1'}},
            {'properties': {'geojson_hash': 'hash2'}}
        ]
        duplicate_features = [
            {'feature': {'properties': {'geojson_hash': 'hash1'}}},
            {'feature': {'properties': {'geojson_hash': 'hash2'}}}
        ]
        self.assertTrue(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_multiple_features_partial_duplicate(self):
        geofeatures = [
            {'properties': {'geojson_hash': 'hash1'}},
            {'properties': {'geojson_hash': 'hash3'}}
        ]
        duplicate_features = [
            {'feature': {'properties': {'geojson_hash': 'hash1'}}},
            {'feature': {'properties': {'geojson_hash': 'hash2'}}}
        ]
        self.assertFalse(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_multiple_features_no_duplicate(self):
        geofeatures = [
            {'properties': {'geojson_hash': 'hash3'}},
            {'properties': {'geojson_hash': 'hash4'}}
        ]
        duplicate_features = [
            {'feature': {'properties': {'geojson_hash': 'hash1'}}},
            {'feature': {'properties': {'geojson_hash': 'hash2'}}}
        ]
        self.assertFalse(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_empty_features(self):
        geofeatures = []
        duplicate_features = [{'feature': {'properties': {'geojson_hash': 'hash1'}}}]
        self.assertFalse(check_all_features_duplicate(geofeatures, duplicate_features))

    def test_generating_hash_if_missing(self):
        # Test that it generates hash if not present in properties
        # Note: generate_geojson_hash behavior depends on its implementation, 
        # checking minimal valid geojson structure might depend on that function.
        # Assuming generate_geojson_hash returns a consistent hash for same content.
        
        # We need to mock generate_geojson_hash or rely on its real implementation.
        # Since this is a SimpleTestCase and we are importing the real function, 
        # we are using the real generate_geojson_hash.
        
        feat1 = {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [0, 0]}}
        feat2 = {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [0, 0]}}
        
        geofeatures = [feat1]
        duplicate_features = [{'feature': feat2}]
        
        self.assertTrue(check_all_features_duplicate(geofeatures, duplicate_features))
