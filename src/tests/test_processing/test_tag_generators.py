"""
Comprehensive tests for all tag generator modules.

Tests each tag generator individually to ensure they produce the correct tags
for various feature types and scenarios.
"""
import pytest
from datetime import datetime
from unittest.mock import Mock, patch

from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator
from geo_lib.processing.tagging.modules.import_date import ImportDateTagGenerator
from geo_lib.processing.tagging.modules.feature_date import FeatureDateTagGenerator
from geo_lib.processing.tagging.modules.track_detection import TrackDetectionTagGenerator
from geo_lib.processing.tagging.modules.driving_detection import DrivingDetectionTagGenerator
from geo_lib.processing.tagging.modules.source_file import SourceFileTagGenerator
from geo_lib.processing.tagging.modules.elevation import ElevationTagGenerator
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from geo_lib.processing.logging import ImportLog


class TestGeometryTypeTagGenerator:
    """Test the geometry type tag generator."""
    
    def test_point_type_tag(self):
        """Test that point features get type:point tag."""
        generator = GeometryTypeTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert tags == ['type:point']
    
    def test_linestring_type_tag(self):
        """Test that linestring features get type:linestring tag."""
        generator = GeometryTypeTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line'}
        )
        
        tags = generator.process(feature)
        
        assert tags == ['type:linestring']
    
    def test_polygon_type_tag(self):
        """Test that polygon features get type:polygon tag."""
        generator = GeometryTypeTagGenerator()
        feature = PolygonFeature(
            type='Feature',
            geometry={
                'type': 'Polygon',
                'coordinates': [[
                    [-122.4194, 37.7749],
                    [-122.4195, 37.7750],
                    [-122.4196, 37.7749],
                    [-122.4194, 37.7749]
                ]]
            },
            properties={'name': 'Test Polygon'}
        )
        
        tags = generator.process(feature)
        
        assert tags == ['type:polygon']


class TestImportDateTagGenerator:
    """Test the import date tag generator."""
    
    @patch('geo_lib.processing.tagging.modules.import_date.datetime')
    def test_import_date_tags(self, mock_datetime):
        """Test that import date tags are generated correctly."""
        # Mock the current date
        mock_now = datetime(2025, 3, 15, 10, 30, 0)
        mock_datetime.now.return_value = mock_now
        
        generator = ImportDateTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert 'import-year:2025' in tags
        assert 'import-month:March' in tags
    
    @patch('geo_lib.processing.tagging.modules.import_date.datetime')
    def test_import_date_tags_different_month(self, mock_datetime):
        """Test import date tags for different month."""
        mock_now = datetime(2024, 12, 1, 10, 30, 0)
        mock_datetime.now.return_value = mock_now
        
        generator = ImportDateTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert 'import-year:2024' in tags
        assert 'import-month:December' in tags


class TestFeatureDateTagGenerator:
    """Test the feature date tag generator."""
    
    def test_feature_with_created_date(self):
        """Test that feature date tags are generated from created property."""
        generator = FeatureDateTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={
                'name': 'Test Point',
                'created': '2023-06-15T10:30:00Z'
            }
        )
        
        tags = generator.process(feature)
        
        assert 'feature-year:2023' in tags
        assert 'feature-month:June' in tags
    
    def test_feature_without_created_date(self):
        """Test that no tags are generated without created date."""
        generator = FeatureDateTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_feature_with_invalid_date(self):
        """Test that invalid dates don't crash the generator."""
        generator = FeatureDateTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={
                'name': 'Test Point',
                'created': 'invalid-date'
            }
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestTrackDetectionTagGenerator:
    """Test the track detection tag generator."""
    
    def test_linestring_with_many_points_is_track(self):
        """Test that linestrings with many points are detected as tracks."""
        generator = TrackDetectionTagGenerator()
        # Create a linestring with 15 points (threshold is 10)
        coordinates = [[-122.4194 + i * 0.001, 37.7749 + i * 0.001] for i in range(15)]
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={'name': 'Test Track'}
        )
        
        tags = generator.process(feature)
        
        assert 'is-track:yes' in tags
    
    def test_linestring_with_few_points_not_track(self):
        """Test that linestrings with few points are not detected as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_point_not_processed(self):
        """Test that points are not processed for track detection."""
        generator = TrackDetectionTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestDrivingDetectionTagGenerator:
    """Test the driving detection tag generator."""
    
    def test_track_with_driving_keyword(self):
        """Test that tracks with 'driving' in name are tagged."""
        generator = DrivingDetectionTagGenerator()
        coordinates = [[-122.4194 + i * 0.001, 37.7749 + i * 0.001] for i in range(15)]
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={'name': 'Morning Driving Route'}
        )
        
        tags = generator.process(feature)
        
        assert 'driving:yes' in tags
    
    def test_track_with_drive_keyword(self):
        """Test that tracks with 'drive' in name are tagged."""
        generator = DrivingDetectionTagGenerator()
        coordinates = [[-122.4194 + i * 0.001, 37.7749 + i * 0.001] for i in range(15)]
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={'name': 'Scenic Drive'}
        )
        
        tags = generator.process(feature)
        
        assert 'driving:yes' in tags
    
    def test_track_without_driving_keyword(self):
        """Test that tracks without driving keywords are not tagged."""
        generator = DrivingDetectionTagGenerator()
        coordinates = [[-122.4194 + i * 0.001, 37.7749 + i * 0.001] for i in range(15)]
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={'name': 'Hiking Trail'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_short_line_not_processed(self):
        """Test that short lines are not processed for driving detection."""
        generator = DrivingDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Driving'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestSourceFileTagGenerator:
    """Test the source file tag generator."""
    
    def test_source_file_tag_with_filename(self):
        """Test that source-file tag is generated with filename."""
        generator = SourceFileTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature, filename='my_map.kml')
        
        assert 'source-file:my_map.kml' in tags
    
    def test_source_file_tag_without_filename(self):
        """Test that no tag is generated without filename."""
        generator = SourceFileTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_source_file_tag_with_empty_filename(self):
        """Test that no tag is generated with empty filename."""
        generator = SourceFileTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature, filename='')
        
        assert tags == []


class TestElevationTagGenerator:
    """Test the elevation tag generator."""
    
    def test_high_elevation_point(self):
        """Test that high elevation points get elevation:high tag."""
        generator = ElevationTagGenerator()
        # 3000 meters = ~9843 feet (threshold is 8000 feet)
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 3000.0]},
            properties={'name': 'Mountain Peak'}
        )
        
        tags = generator.process(feature)
        
        assert 'elevation:high' in tags
    
    def test_low_elevation_point(self):
        """Test that low elevation points get elevation:low tag."""
        generator = ElevationTagGenerator()
        # 10 meters = ~33 feet (threshold is 100 feet)
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 10.0]},
            properties={'name': 'Sea Level Point'}
        )
        
        tags = generator.process(feature)
        
        assert 'elevation:low' in tags
    
    def test_zero_elevation_ignored(self):
        """Test that 0.0 elevation is treated as missing data and ignored."""
        generator = ElevationTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            properties={'name': 'Unknown Elevation'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_medium_elevation_no_tags(self):
        """Test that medium elevation gets no tags."""
        generator = ElevationTagGenerator()
        # 500 meters = ~1640 feet (between 100 and 8000 feet)
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 500.0]},
            properties={'name': 'Medium Elevation'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_point_without_elevation(self):
        """Test that points without elevation get no tags."""
        generator = ElevationTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'No Elevation'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_high_elevation_linestring(self):
        """Test that linestrings with high elevation get elevation:high tag."""
        generator = ElevationTagGenerator()
        # Line with max elevation at 3000 meters
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [
                    [-122.4194, 37.7749, 2000.0],
                    [-122.4195, 37.7750, 3000.0],
                    [-122.4196, 37.7751, 2500.0]
                ]
            },
            properties={'name': 'Mountain Trail'}
        )
        
        tags = generator.process(feature)
        
        assert 'elevation:high' in tags
    
    def test_linestring_with_zero_values_ignored(self):
        """Test that linestrings with 0.0 values skip those points."""
        generator = ElevationTagGenerator()
        # Line with some 0.0 values (should be ignored) and one low value
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [
                    [-122.4194, 37.7749, 0.0],
                    [-122.4195, 37.7750, 10.0],
                    [-122.4196, 37.7751, 0.0]
                ]
            },
            properties={'name': 'Mixed Elevation'}
        )
        
        tags = generator.process(feature)
        
        # Should only see elevation:low from the 10.0 meter point
        assert 'elevation:low' in tags
        assert 'elevation:high' not in tags
    
    def test_polygon_not_processed(self):
        """Test that polygons are not processed for elevation tags."""
        generator = ElevationTagGenerator()
        feature = PolygonFeature(
            type='Feature',
            geometry={
                'type': 'Polygon',
                'coordinates': [[
                    [-122.4194, 37.7749, 3000.0],
                    [-122.4195, 37.7750, 3000.0],
                    [-122.4196, 37.7749, 3000.0],
                    [-122.4194, 37.7749, 3000.0]
                ]]
            },
            properties={'name': 'Test Polygon'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestGeocodingTagGenerator:
    """Test the geocoding tag generator."""
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_disabled(self, mock_setting):
        """Test that no tags are generated when geocoding is disabled."""
        mock_setting.return_value = False
        
        generator = GeocodingTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    @patch('geo_lib.processing.tagging.modules.geocoding.reverse_geocode_feature')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_for_point(self, mock_setting, mock_geocode):
        """Test that geocoding tags are generated for points."""
        mock_setting.return_value = True
        mock_geocode.return_value = {
            'city': 'San Francisco',
            'state': 'California',
            'country': 'United States'
        }
        
        generator = GeocodingTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert 'geo-city:San Francisco' in tags
        assert 'geo-state:California' in tags
        assert 'geo-country:United States' in tags
    
    @patch('geo_lib.processing.tagging.modules.geocoding.reverse_geocode_feature')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_for_linestring(self, mock_setting, mock_geocode):
        """Test that geocoding tags are generated for linestrings."""
        mock_setting.return_value = True
        mock_geocode.return_value = {
            'state': 'California',
            'country': 'United States'
        }
        
        generator = GeocodingTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert 'geo-state:California' in tags
        assert 'geo-country:United States' in tags
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_polygon_not_processed(self, mock_setting):
        """Test that polygons are not processed for geocoding."""
        mock_setting.return_value = True
        
        generator = GeocodingTagGenerator()
        feature = PolygonFeature(
            type='Feature',
            geometry={
                'type': 'Polygon',
                'coordinates': [[
                    [-122.4194, 37.7749],
                    [-122.4195, 37.7750],
                    [-122.4196, 37.7749],
                    [-122.4194, 37.7749]
                ]]
            },
            properties={'name': 'Test Polygon'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert tags == []
    
    @patch('geo_lib.processing.tagging.modules.geocoding.reverse_geocode_feature')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_with_none_result(self, mock_setting, mock_geocode):
        """Test that no tags are generated when geocoding returns None."""
        mock_setting.return_value = True
        mock_geocode.return_value = None
        
        generator = GeocodingTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert tags == []

