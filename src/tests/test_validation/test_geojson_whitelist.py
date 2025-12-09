"""
Tests for GeoJSON whitelist validation and normalization.
"""
import pytest
from geo_lib.validation import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError


class TestGeoJSONWhitelist:
    """Test GeoJSON feature validation and normalization."""

    def test_valid_point_feature(self):
        """Test validation of a valid Point feature."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test Point',
                'description': 'A test point',
                'tags': ['test']
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['type'] == 'Feature'
        assert result['geometry']['type'] == 'Point'
        assert result['properties']['name'] == 'Test Point'
        assert result['properties']['tags'] == ['test']

    def test_whitelist_filtering(self):
        """Test that non-whitelisted keys are removed."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749],
                'extra_field': 'should be removed'
            },
            'properties': {
                'name': 'Test',
                'allowed_field': 'should stay',
                'non_whitelisted': 'should be removed',
                'tags': ['test']
            },
            'extra_top_level': 'should be removed'
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert 'extra_top_level' not in result
        assert 'extra_field' not in result['geometry']
        assert 'non_whitelisted' not in result['properties']
        assert 'name' in result['properties']
        assert 'tags' in result['properties']

    def test_name_normalization(self):
        """Test that empty names are allowed."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': '',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''

    def test_name_none_normalization(self):
        """Test that None names are converted to empty string."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': None,
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''

    def test_description_dict_parsing(self):
        """Test parsing of description from dictionary format (KML HTML)."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'description': {'@type': 'html', 'value': '<p>HTML content</p>'},
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['description'] == '<p>HTML content</p>'

    def test_tags_normalization(self):
        """Test tags normalization."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': ['tag1', 'tag2', 123, None, 'tag3']
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['tags'] == ['tag1', 'tag2', 'tag3']

    def test_tags_none_normalization(self):
        """Test that None tags become empty list."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': None
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['tags'] == []

    def test_system_tags_preservation(self):
        """Test that system_tags are preserved."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'system_tags': ['type:point', 'import-year:2023'],
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert 'system_tags' in result['properties']
        assert 'type:point' in result['properties']['system_tags']

    def test_system_tags_explicit_preservation(self):
        """Test explicit system_tags preservation."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(
            feature,
            preserve_system_tags=['custom:tag']
        )
        assert result['properties']['system_tags'] == ['custom:tag']

    def test_line_style_normalization(self):
        """Test line style normalization (stroke-width set to 2, fill removed)."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
            },
            'properties': {
                'name': 'Test Line',
                'stroke': '#ff0000',
                'stroke-width': 5,
                'fill': '#00ff00',
                'fill-opacity': 0.5,
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['stroke-width'] == 2
        assert 'fill' not in result['properties']
        assert 'fill-opacity' not in result['properties']

    def test_polygon_style_normalization(self):
        """Test polygon style normalization."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7749],
                                [-122.4094, 37.7849], [-122.4194, 37.7849],
                                [-122.4194, 37.7749]]]
            },
            'properties': {
                'name': 'Test Polygon',
                'stroke': '#ff0000',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['stroke-width'] == 2
        assert result['properties']['fill'] == '#ff0000'
        assert result['properties']['fill-opacity'] == 0.1

    def test_invalid_geometry_type(self):
        """Test that invalid geometry types raise errors."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'InvalidType',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': []
            }
        }
        with pytest.raises(GeometryValidationError):
            validate_and_normalize_geojson_feature(feature)

    def test_missing_geometry(self):
        """Test that missing geometry raises error."""
        feature = {
            'type': 'Feature',
            'properties': {
                'name': 'Test',
                'tags': []
            }
        }
        with pytest.raises(GeometryValidationError):
            validate_and_normalize_geojson_feature(feature)

    def test_invalid_feature_type(self):
        """Test that non-Feature types raise errors."""
        feature = {
            'type': 'FeatureCollection',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': []
            }
        }
        with pytest.raises(GeometryValidationError):
            validate_and_normalize_geojson_feature(feature)

    def test_color_validation(self):
        """Test that invalid colors are set to default red."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'marker-color': 'invalid-color',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['marker-color'] == '#ff0000'

    def test_valid_color_normalization(self):
        """Test that valid colors are normalized to uppercase."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'marker-color': '#abc123',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['marker-color'] == '#abc123'

    def test_geometry_collection_polygon_detection(self):
        """Test that GeometryCollection with polygons gets polygon styling."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'GeometryCollection',
                'geometries': [
                    {
                        'type': 'Polygon',
                        'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7749],
                                        [-122.4094, 37.7849], [-122.4194, 37.7849],
                                        [-122.4194, 37.7749]]]
                    }
                ]
            },
            'properties': {
                'name': 'Test',
                'stroke': '#ff0000',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['fill'] == '#ff0000'
        assert result['properties']['fill-opacity'] == 0.1

    def test_multipoint_styling(self):
        """Test MultiPoint styling."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'MultiPoint',
                'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
            },
            'properties': {
                'name': 'Test',
                'marker-color': '#00ff00',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['marker-color'] == '#00ff00'

    def test_multilinestring_styling(self):
        """Test MultiLineString styling."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'MultiLineString',
                'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7849]]]
            },
            'properties': {
                'name': 'Test',
                'stroke': '#0000ff',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['stroke-width'] == 2
        assert 'fill' not in result['properties']

    def test_multipolygon_styling(self):
        """Test MultiPolygon styling."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'MultiPolygon',
                'coordinates': [[[[-122.4194, 37.7749], [-122.4094, 37.7749],
                                [-122.4094, 37.7849], [-122.4194, 37.7849],
                                [-122.4194, 37.7749]]]]
            },
            'properties': {
                'name': 'Test',
                'stroke': '#ffff00',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['fill'] == '#ffff00'
        assert result['properties']['fill-opacity'] == 0.1

    def test_geojson_hash_stripped_by_default(self):
        """Test that geojson_hash is stripped by default."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'geojson_hash': 'abc123def456',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert 'geojson_hash' not in result['properties']

    def test_geojson_hash_preservation(self):
        """Test that geojson_hash can be preserved when requested."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'geojson_hash': 'abc123def456',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(
            feature,
            preserve_geojson_hash=True
        )
        assert result['properties']['geojson_hash'] == 'abc123def456'

    def test_geojson_hash_preservation_with_none(self):
        """Test that geojson_hash preservation does nothing if hash is not present."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': 'Test',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(
            feature,
            preserve_geojson_hash=True
        )
        assert 'geojson_hash' not in result['properties']

    def test_empty_name_point(self):
        """Test that points can have empty names."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': '',
                'marker-color': '#00ff00',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''
        assert result['properties']['marker-color'] == '#00ff00'

    def test_empty_name_linestring(self):
        """Test that lines can have empty names."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'LineString',
                'coordinates': [[-122.4194, 37.7749], [-122.4094, 37.7849]]
            },
            'properties': {
                'name': '',
                'stroke': '#ff0000',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''
        assert result['properties']['stroke'] == '#ff0000'
        assert result['properties']['stroke-width'] == 2

    def test_empty_name_polygon(self):
        """Test that polygons can have empty names."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7749],
                                [-122.4094, 37.7849], [-122.4194, 37.7849],
                                [-122.4194, 37.7749]]]
            },
            'properties': {
                'name': '',
                'stroke': '#0000ff',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''
        assert result['properties']['stroke'] == '#0000ff'
        assert result['properties']['fill'] == '#0000ff'
        assert result['properties']['fill-opacity'] == 0.1

    def test_whitespace_only_name(self):
        """Test that whitespace-only names are preserved (not converted to empty)."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'name': '   ',
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        # Whitespace is preserved as-is (converted to string)
        assert result['properties']['name'] == '   '

    def test_missing_name_property(self):
        """Test that features without name property get empty string default."""
        feature = {
            'type': 'Feature',
            'geometry': {
                'type': 'Point',
                'coordinates': [-122.4194, 37.7749]
            },
            'properties': {
                'tags': []
            }
        }
        result = validate_and_normalize_geojson_feature(feature)
        assert result['properties']['name'] == ''

