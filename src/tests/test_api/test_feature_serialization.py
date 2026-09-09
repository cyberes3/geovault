from django.test import SimpleTestCase

from api.services.feature_serialization import geojson_feature_from_parts


class TestPublicSafeSerialization(SimpleTestCase):
    def test_public_safe_uses_feature_ref_and_omits_store_ids(self):
        geojson = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
            'properties': {
                'name': 'Shared Point',
                'database_id': 99,
                'geojson_hash': 'abc123',
                'tags': ['trail'],
            },
        }
        feature = geojson_feature_from_parts(7, geojson, 'abc123', public_safe=True)
        self.assertIsNotNone(feature)
        properties = feature['properties']
        self.assertEqual(properties['feature_ref'], 7)
        self.assertNotIn('database_id', properties)
        self.assertNotIn('geojson_hash', properties)
        self.assertNotIn('geojson_hash', feature)
        self.assertNotIn('tags', properties)

    def test_owner_serialization_keeps_database_id_and_hash(self):
        geojson = {
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
            'properties': {'name': 'Owned Point'},
        }
        feature = geojson_feature_from_parts(7, geojson, 'abc123')
        self.assertEqual(feature['properties']['database_id'], 7)
        self.assertEqual(feature['geojson_hash'], 'abc123')
        self.assertNotIn('feature_ref', feature['properties'])
