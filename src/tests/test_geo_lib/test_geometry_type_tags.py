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
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.processing.logging import ImportLog


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


@pytest.mark.django_db
class TestLineVsTrackTags(TestCase):
    """Test the distinction between type:line and type:track tags."""
    
    def test_regular_line_gets_type_line(self):
        """Test that regular LineStrings (no time data) get type:line tag."""
        regular_line = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
            'properties': {'geojson_hash': 'test123', 'name': 'Regular Line'}
        })
        
        # Generate all tags (skip geocoding for speed)
        import_log = ImportLog()
        tags = generate_auto_tags(regular_line, import_log=import_log, skip_reverse_geocoding=True)
        
        # Should have type:line
        self.assertIn('type:line', tags)
        # Should NOT have type:track
        self.assertNotIn('type:track', tags)
        # Should NOT have old track:yes tag
        self.assertNotIn('track:yes', tags)
    
    def test_gpx_track_gets_type_track_not_line(self):
        """Test that GPX tracks (with coordinateProperties.times) get type:track, NOT type:line."""
        gpx_track = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
            'properties': {
                'geojson_hash': 'test456',
                'name': 'GPX Track',
                'coordinateProperties': {
                    'times': ['2024-01-01T00:00:00Z', '2024-01-01T00:01:00Z']
                }
            }
        })
        
        # Generate all tags (skip geocoding for speed)
        import_log = ImportLog()
        tags = generate_auto_tags(gpx_track, import_log=import_log, skip_reverse_geocoding=True)
        
        # Should have type:track
        self.assertIn('type:track', tags)
        # Should NOT have type:line (track is more specific)
        self.assertNotIn('type:line', tags)
        # Should NOT have old track:yes tag
        self.assertNotIn('track:yes', tags)
    
    def test_gpx_route_gets_type_track(self):
        """Test that GPX routes (with time property) get type:track."""
        gpx_route = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1]]},
            'properties': {
                'geojson_hash': 'test789',
                'name': 'GPX Route',
                'time': '2024-01-01T00:00:00Z'
            }
        })
        
        # Generate all tags (skip geocoding for speed)
        import_log = ImportLog()
        tags = generate_auto_tags(gpx_route, import_log=import_log, skip_reverse_geocoding=True)
        
        # Should have type:track
        self.assertIn('type:track', tags)
        # Should NOT have type:line
        self.assertNotIn('type:line', tags)
    
    def test_multilinestring_track(self):
        """Test that MultiLineString tracks also get type:track."""
        multi_track = MultiLineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'MultiLineString', 'coordinates': [[[0, 0], [1, 1]]]},
            'properties': {
                'geojson_hash': 'test999',
                'name': 'Multi Track',
                'coordinateProperties': {
                    'times': ['2024-01-01T00:00:00Z', '2024-01-01T00:01:00Z']
                }
            }
        })
        
        # Generate all tags (skip geocoding for speed)
        import_log = ImportLog()
        tags = generate_auto_tags(multi_track, import_log=import_log, skip_reverse_geocoding=True)
        
        # Should have type:track
        self.assertIn('type:track', tags)
        # Should NOT have type:line
        self.assertNotIn('type:line', tags)
    
    def test_track_tag_overrides_line_tag(self):
        """Test that type:track replaces type:line in the tag generation pipeline."""
        # This tests the pipeline integration
        # GeometryTypeTagGenerator runs first (priority 10) and adds type:line
        # TrackDetectionTagGenerator runs later (priority 40) and adds type:track
        # Post-processing should remove type:line when type:track is present
        
        gpx_track = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[0, 0], [1, 1], [2, 2]]},
            'properties': {
                'geojson_hash': 'test_override',
                'name': 'Override Test',
                'coordinateProperties': {
                    'times': ['2024-01-01T00:00:00Z', '2024-01-01T00:01:00Z', '2024-01-01T00:02:00Z']
                }
            }
        })
        
        import_log = ImportLog()
        tags = generate_auto_tags(gpx_track, import_log=import_log, skip_reverse_geocoding=True)
        type_tags = [t for t in tags if t.startswith('type:')]
        
        # Should have exactly one type tag
        self.assertEqual(len(type_tags), 1, f"Should have exactly one type: tag, got: {type_tags}")
        # That tag should be type:track
        self.assertEqual(type_tags[0], 'type:track')
    
    def test_caltopo_route_is_line_not_track(self):
        """Test that CalTopo routes (no time data) are type:line, not type:track."""
        caltopo_route = LineStringFeature(**{
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-106.097, 39.025], [-106.096, 39.026], [-106.095, 39.027]]
            },
            'properties': {
                'geojson_hash': 'caltopo_test',
                'name': 'Buffalo_Peaks_Route',
                'description': 'Planned hiking route'
            }
        })
        
        import_log = ImportLog()
        tags = generate_auto_tags(caltopo_route, import_log=import_log, skip_reverse_geocoding=True)
        
        # Should have type:line (it's a planned route, not a GPS track)
        self.assertIn('type:line', tags)
        # Should NOT have type:track (no time data)
        self.assertNotIn('type:track', tags)

