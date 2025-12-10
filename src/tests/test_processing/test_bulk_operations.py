"""
Tests for bulk operations validation and application.
"""
import pytest
from geo_lib.processing.import_operations.validation import validate_bulk_operations_payload
from geo_lib.processing.import_operations.styling import apply_bulk_operations


class TestBulkOperations:
    """Test bulk operations validation and application."""

    def test_validate_bulk_operations_valid(self):
        """Test validating valid bulk operations."""
        bulk_ops = {
            'tags': ['test-tag'],
            'pointColor': '#ff0000',
            'pointIcon': 'assets/icons/test.png',
            'lineColor': '#00ff00',
            'polyColor': '#0000ff'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True
        assert error is None

    def test_validate_bulk_operations_invalid_key(self):
        """Test validating bulk operations with invalid key."""
        bulk_ops = {
            'invalidKey': 'value'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is False
        assert error is not None

    def test_validate_bulk_operations_tags_not_list(self):
        """Test validating bulk operations with tags not as list."""
        bulk_ops = {
            'tags': 'not-a-list'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is False
        assert error is not None

    def test_validate_bulk_operations_tags_not_strings(self):
        """Test validating bulk operations with tags not as strings."""
        bulk_ops = {
            'tags': [123, 'valid']
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is False

    def test_validate_bulk_operations_invalid_color(self):
        """Test validating bulk operations with invalid color."""
        bulk_ops = {
            'pointColor': 'invalid-color'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is False
        assert error is not None

    def test_validate_bulk_operations_null_color(self):
        """Test validating bulk operations with null color."""
        bulk_ops = {
            'pointColor': None
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True

    def test_validate_bulk_operations_invalid_icon_url(self):
        """Test validating bulk operations with invalid icon URL."""
        bulk_ops = {
            'pointIcon': 'http://example.com/icon.png'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is False
        assert error is not None

    def test_validate_bulk_operations_valid_icon_url(self):
        """Test validating bulk operations with valid icon URL."""
        bulk_ops = {
            'pointIcon': 'assets/icons/test.png'
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True

    def test_validate_bulk_operations_null_icon(self):
        """Test validating bulk operations with null icon."""
        bulk_ops = {
            'pointIcon': None
        }
        is_valid, error = validate_bulk_operations_payload(bulk_ops)
        assert is_valid is True

    def test_validate_bulk_operations_not_dict(self):
        """Test validating bulk operations that is not a dict."""
        is_valid, error = validate_bulk_operations_payload('not-a-dict')
        assert is_valid is False
        assert error is not None

    def test_apply_bulk_operations_tags(self):
        """Test applying bulk operations with tags."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test', 'tags': ['existing']}
        }]
        bulk_ops = {
            'tags': ['new-tag']
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert 'new-tag' in result[0]['properties']['tags']
        assert 'existing' in result[0]['properties']['tags']

    def test_apply_bulk_operations_point_color(self):
        """Test applying bulk operations with point color."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test'}
        }]
        bulk_ops = {
            'pointColor': '#ff0000'
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert result[0]['properties']['marker-color'] == '#ff0000'

    def test_apply_bulk_operations_line_color(self):
        """Test applying bulk operations with line color."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'LineString', 'coordinates': [[-122.4194, 37.7749]]},
            'properties': {'name': 'Test'}
        }]
        bulk_ops = {
            'lineColor': '#00ff00'
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert result[0]['properties']['stroke'] == '#00ff00'

    def test_apply_bulk_operations_polygon_color(self):
        """Test applying bulk operations with polygon color."""
        features = [{
            'type': 'Feature',
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[-122.4194, 37.7749], [-122.4094, 37.7749],
                                [-122.4094, 37.7849], [-122.4194, 37.7849],
                                [-122.4194, 37.7749]]]
            },
            'properties': {'name': 'Test'}
        }]
        bulk_ops = {
            'polyColor': '#0000ff'
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert result[0]['properties']['stroke'] == '#0000ff'
        assert result[0]['properties']['fill'] == '#0000ff'

    def test_apply_bulk_operations_point_icon(self):
        """Test applying bulk operations with point icon."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test'}
        }]
        bulk_ops = {
            'pointIcon': 'assets/icons/test.png'
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert result[0]['properties']['icon'] == 'assets/icons/test.png'

    def test_apply_bulk_operations_empty(self):
        """Test applying empty bulk operations."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test'}
        }]
        result = apply_bulk_operations(features, {})
        assert len(result) == 1
        assert result[0] == features[0]

    def test_apply_bulk_operations_duplicate_feature(self):
        """Test that duplicate features are skipped."""
        features = [{
            'type': 'Feature',
            'geometry': {'type': 'Point', 'coordinates': [-122.4194, 37.7749]},
            'properties': {'name': 'Test', 'isDuplicate': True}
        }]
        bulk_ops = {
            'tags': ['new-tag']
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 1
        assert 'new-tag' not in result[0]['properties'].get('tags', [])

    def test_apply_bulk_operations_multiple_features(self):
        """Test applying bulk operations to multiple features."""
        features = [
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
        bulk_ops = {
            'tags': ['shared-tag']
        }
        result = apply_bulk_operations(features, bulk_ops)
        assert len(result) == 2
        assert 'shared-tag' in result[0]['properties']['tags']
        assert 'shared-tag' in result[1]['properties']['tags']

