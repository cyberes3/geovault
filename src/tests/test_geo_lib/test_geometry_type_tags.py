"""
Tests for geometry type tag generator.
Tests that geometry types are mapped to simplified user-friendly names.
"""
import pytest
from django.test import TestCase

from geo_lib.types.feature import (
    PointFeature,
    LineStringFeature,
    MultiLineStringFeature,
    PolygonFeature
)
from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator


@pytest.mark.django_db
class TestGeometryTypeTagGenerator(TestCase):
    """Test geometry type tag generation."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.generator = GeometryTypeTagGenerator()
    
    def test_point_type(self):
        """Test that Point geometry generates type:point tag."""
        feature = PointFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [0, 0]},
            'properties': {'geojson_hash': 'test123'}
        })
        
        tags = self.generator.process(feature)
        
        self.assertEqual(len(tags), 1)
        self.assertEqual(tags[0], 'type:point')
    
    def test_linestring_type(self):
        """Test that LineString geometry generates type:line tag (not type:linestring)."""
        feature = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
            'properties': {'geojson_hash': 'test123'}
        })
        
        tags = self.generator.process(feature)
        
        self.assertEqual(len(tags), 1)
        self.assertEqual(tags[0], 'type:line')
    
    def test_multilinestring_type(self):
        """Test that MultiLineString geometry generates type:line tag (simplified from type:multilinestring)."""
        feature = MultiLineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'MultiLineString', 'coordinates': [[[0, 0], [1, 1]]]},
            'properties': {'geojson_hash': 'test123'}
        })
        
        tags = self.generator.process(feature)
        
        self.assertEqual(len(tags), 1)
        self.assertEqual(tags[0], 'type:line')
        # Verify it's NOT using the technical name
        self.assertNotEqual(tags[0], 'type:multilinestring')
    
    def test_polygon_type(self):
        """Test that Polygon geometry generates type:polygon tag."""
        feature = PolygonFeature(**{
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]
            },
            'properties': {'geojson_hash': 'test123'}
        })
        
        tags = self.generator.process(feature)
        
        self.assertEqual(len(tags), 1)
        self.assertEqual(tags[0], 'type:polygon')
    
    def test_all_line_types_get_same_tag(self):
        """Test that both LineString and MultiLineString get the same simplified tag."""
        linestring = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
            'properties': {'geojson_hash': 'test1'}
        })
        
        multilinestring = MultiLineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'MultiLineString', 'coordinates': [[[0, 0], [1, 1]]]},
            'properties': {'geojson_hash': 'test2'}
        })
        
        linestring_tags = self.generator.process(linestring)
        multilinestring_tags = self.generator.process(multilinestring)
        
        # Both should generate the same tag
        self.assertEqual(linestring_tags, multilinestring_tags)
        self.assertEqual(linestring_tags[0], 'type:line')
    
    def test_simplified_names_not_technical(self):
        """Test that we're using simplified names, not technical GeoJSON terms."""
        test_cases = [
            (LineStringFeature, {
                'type': 'Feature',
                'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
                'properties': {'geojson_hash': 'test1'}
            }, 'type:line', ['type:linestring']),
            (MultiLineStringFeature, {
                'type': 'Feature',
                'geometry': {'type': 'MultiLineString', 'coordinates': [[[0, 0], [1, 1]]]},
                'properties': {'geojson_hash': 'test2'}
            }, 'type:line', ['type:multilinestring']),
        ]
        
        for feature_class, feature_data, expected_tag, forbidden_tags in test_cases:
            feature = feature_class(**feature_data)
            tags = self.generator.process(feature)
            
            # Should have the expected simplified tag
            self.assertEqual(tags[0], expected_tag)
            
            # Should NOT have any of the technical tags
            for forbidden_tag in forbidden_tags:
                self.assertNotIn(forbidden_tag, tags)

