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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == ['type:point']
    
    def test_linestring_type_tag(self):
        """Test that linestring features get type:line tag."""
        generator = GeometryTypeTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == ['type:line']
    
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
            properties={'name': 'Test Polygon', 'geojson_hash': 'test'}
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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
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
                'name': 'Test Point', 'geojson_hash': 'test',
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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
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
                'name': 'Test Point', 'geojson_hash': 'test',
                'created': 'invalid-date'
            }
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestTrackDetectionTagGenerator:
    """Test the track detection tag generator."""
    
    def test_gpx_track_with_timestamps(self):
        """Test that GPX tracks with coordinateProperties.times are detected as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [
                    [-122.4194, 37.7749, 100.0],
                    [-122.4195, 37.7750, 105.0],
                    [-122.4196, 37.7751, 110.0]
                ]
            },
            properties={
                'name': 'GPS Track',
                'geojson_hash': 'test123',
                'coordinateProperties': {
                    'times': [
                        '2024-09-02T08:00:00Z',
                        '2024-09-02T08:00:05Z',
                        '2024-09-02T08:00:10Z'
                    ]
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert 'track:yes' in tags
    
    def test_gpx_route_with_time_property(self):
        """Test that GPX routes with time property are detected as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={
                'name': 'GPS Route', 'geojson_hash': 'test',
                'time': '2024-09-02T08:00:00Z'
            }
        )
        
        tags = generator.process(feature)
        
        assert 'track:yes' in tags
    
    def test_kml_gx_track_with_timestamps(self):
        """Test that KML gx:Track elements (converted with coordinateProperties) are detected."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [
                    [-105.5, 39.1, 2800.0],
                    [-105.501, 39.102, 2850.0],
                    [-105.502, 39.104, 2900.0]
                ]
            },
            properties={
                'name': 'Mountain Hike Track', 'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': [
                        '2024-09-02T08:00:00Z',
                        '2024-09-02T08:05:00Z',
                        '2024-09-02T08:10:00Z'
                    ]
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert 'track:yes' in tags
    
    def test_plain_kml_linestring_without_timestamps(self):
        """Test that plain KML LineStrings without timestamps are NOT marked as tracks."""
        generator = TrackDetectionTagGenerator()
        # This represents a CalTopo export or manually drawn route
        coordinates = [[-106.097 + i * 0.001, 39.025 + i * 0.001, 3200.0] for i in range(100)]
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={
                'name': 'Buffalo_Peaks_20249092', 'geojson_hash': 'test',
                'description': 'CalTopo Export - planning route'
            }
        )
        
        tags = generator.process(feature)
        
        # Should NOT be marked as track - this is the key test case
        assert tags == []
        assert 'track:yes' not in tags
    
    def test_linestring_with_empty_times_array(self):
        """Test that LineStrings with empty times array are not marked as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={
                'name': 'Test Line', 'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': []
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_linestring_with_none_times(self):
        """Test that LineStrings with None times are not marked as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={
                'name': 'Test Line', 'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': None
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_linestring_without_coordinate_properties(self):
        """Test that LineStrings without coordinateProperties are not marked as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_multilinestring_with_timestamps(self):
        """Test that MultiLineStrings with timestamps are detected as tracks."""
        generator = TrackDetectionTagGenerator()
        feature = MultiLineStringFeature(
            type='Feature',
            geometry={
                'type': 'MultiLineString',
                'coordinates': [
                    [[-122.4194, 37.7749], [-122.4195, 37.7750]],
                    [[-122.4196, 37.7751], [-122.4197, 37.7752]]
                ]
            },
            properties={
                'name': 'Multi Track', 'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': [
                        '2024-09-02T08:00:00Z',
                        '2024-09-02T08:00:05Z',
                        '2024-09-02T08:00:10Z',
                        '2024-09-02T08:00:15Z'
                    ]
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert 'track:yes' in tags
    
    def test_point_not_processed(self):
        """Test that points are not processed for track detection."""
        generator = TrackDetectionTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_polygon_not_processed(self):
        """Test that polygons are not processed for track detection."""
        generator = TrackDetectionTagGenerator()
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
            properties={
                'name': 'Test Polygon', 'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': ['2024-09-02T08:00:00Z']
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert tags == []


class TestDrivingDetectionTagGenerator:
    """Test the driving detection tag generator."""
    
    def test_track_with_driving_keyword(self):
        """Test that tracks with driving speed (30 mph average) are tagged."""
        generator = DrivingDetectionTagGenerator()
        # Create coordinates spaced to simulate ~30 mph driving
        # Each point is about 400m apart, with 30 second intervals
        # 400m / 30s = 13.33 m/s = 29.8 mph
        coordinates = [[-122.4194 + i * 0.0036, 37.7749 + i * 0.0036] for i in range(15)]
        # Generate timestamps 30 seconds apart: 08:00:00, 08:00:30, 08:01:00, etc.
        timestamps = []
        for i in range(15):
            seconds = i * 30
            minutes = seconds // 60
            secs = seconds % 60
            timestamps.append(f'2024-09-02T08:{minutes:02d}:{secs:02d}Z')
        
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={
                'name': 'Morning Driving Route',
                'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': timestamps
                }
            }
        )
        
        tags = generator.process(feature)
        
        assert 'driving:yes' in tags
    
    def test_track_with_drive_keyword(self):
        """Test that tracks with driving speed (45 mph average) are tagged."""
        generator = DrivingDetectionTagGenerator()
        # Create coordinates spaced to simulate ~45 mph driving
        # Each point is about 600m apart, with 30 second intervals
        # 600m / 30s = 20 m/s = 44.7 mph
        coordinates = [[-122.4194 + i * 0.0054, 37.7749 + i * 0.0054] for i in range(15)]
        # Generate timestamps 30 seconds apart: 08:00:00, 08:00:30, 08:01:00, etc.
        timestamps = []
        for i in range(15):
            seconds = i * 30
            minutes = seconds // 60
            secs = seconds % 60
            timestamps.append(f'2024-09-02T08:{minutes:02d}:{secs:02d}Z')
        
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={
                'name': 'Scenic Drive',
                'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': timestamps
                }
            }
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
            properties={'name': 'Hiking Trail', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_short_line_not_processed(self):
        """Test that short lines (< 10 points) are not processed for driving detection."""
        generator = DrivingDetectionTagGenerator()
        # Only 5 points with timestamps
        coordinates = [[-122.4194 + i * 0.0036, 37.7749 + i * 0.0036] for i in range(5)]
        timestamps = [f'2024-09-02T08:00:{i*30:02d}Z' for i in range(5)]
        
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': coordinates
            },
            properties={
                'name': 'Driving',
                'geojson_hash': 'test',
                'coordinateProperties': {
                    'times': timestamps
                }
            }
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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature, filename='my_map.kml')
        
        assert 'source-file:my_map.kml' in tags
    
    def test_source_file_tag_without_filename(self):
        """Test that no tag is generated without filename."""
        generator = SourceFileTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_source_file_tag_with_empty_filename(self):
        """Test that no tag is generated with empty filename."""
        generator = SourceFileTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
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
            properties={'name': 'Mountain Peak', 'geojson_hash': 'test'}
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
            properties={'name': 'Sea Level Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert 'elevation:low' in tags
    
    def test_zero_elevation_ignored(self):
        """Test that 0.0 elevation is treated as missing data and ignored."""
        generator = ElevationTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749, 0.0]},
            properties={'name': 'Unknown Elevation', 'geojson_hash': 'test'}
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
            properties={'name': 'Medium Elevation', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    def test_point_without_elevation(self):
        """Test that points without elevation get no tags."""
        generator = ElevationTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'No Elevation', 'geojson_hash': 'test'}
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
            properties={'name': 'Mountain Trail', 'geojson_hash': 'test'}
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
            properties={'name': 'Mixed Elevation', 'geojson_hash': 'test'}
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
            properties={'name': 'Test Polygon', 'geojson_hash': 'test'}
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
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        
        tags = generator.process(feature)
        
        assert tags == []
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_for_point(self, mock_setting, mock_get_service):
        """Test that geocoding tags are generated for points."""
        mock_setting.return_value = True
        
        # Mock the geocoding service
        mock_service = Mock()
        mock_service.get_location_tags.return_value = [
            'geo-city:San Francisco',
            'geo-state:California',
            'geo-country:United States'
        ]
        mock_get_service.return_value = mock_service
        
        generator = GeocodingTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert 'geo-city:San Francisco' in tags
        assert 'geo-state:California' in tags
        assert 'geo-country:United States' in tags
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_for_linestring(self, mock_setting, mock_get_service):
        """Test that geocoding tags are generated for linestrings."""
        mock_setting.return_value = True
        
        # Mock the geocoding service
        mock_service = Mock()
        mock_service.get_location_tags.return_value = [
            'geo-state:California',
            'geo-country:United States'
        ]
        mock_get_service.return_value = mock_service
        
        generator = GeocodingTagGenerator()
        feature = LineStringFeature(
            type='Feature',
            geometry={
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4195, 37.7750]]
            },
            properties={'name': 'Test Line', 'geojson_hash': 'test'}
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
            properties={'name': 'Test Polygon', 'geojson_hash': 'test'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert tags == []
    
    @patch('geo_lib.processing.tagging.modules.geocoding.get_reverse_geocoding_service')
    @patch('geo_lib.processing.tagging.modules.geocoding.get_required_setting')
    def test_geocoding_with_none_result(self, mock_setting, mock_get_service):
        """Test that no tags are generated when geocoding returns empty list."""
        mock_setting.return_value = True
        
        # Mock the geocoding service to return empty list
        mock_service = Mock()
        mock_service.get_location_tags.return_value = []
        mock_get_service.return_value = mock_service
        
        generator = GeocodingTagGenerator()
        feature = PointFeature(
            type='Feature',
            geometry={'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            properties={'name': 'Test Point', 'geojson_hash': 'test'}
        )
        import_log = ImportLog()
        
        tags = generator.process(feature, import_log=import_log)
        
        assert tags == []
