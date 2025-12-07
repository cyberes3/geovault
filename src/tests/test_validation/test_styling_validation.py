"""
Tests for styling validation (colors, icons).
"""
import pytest
from geo_lib.validation.styling_validation import (
    is_valid_hex_color,
    normalize_hex_color,
    is_valid_icon_url,
    describe_color_format,
    describe_icon_format,
)


class TestStylingValidation:
    """Test styling validation functions."""

    def test_valid_3_digit_hex_color(self):
        """Test validation of 3-digit hex colors."""
        assert is_valid_hex_color('#abc') is True
        assert is_valid_hex_color('#ABC') is True
        assert is_valid_hex_color('#123') is True
        assert is_valid_hex_color('#fF0') is True

    def test_valid_6_digit_hex_color(self):
        """Test validation of 6-digit hex colors."""
        assert is_valid_hex_color('#abcdef') is True
        assert is_valid_hex_color('#ABCDEF') is True
        assert is_valid_hex_color('#123456') is True
        assert is_valid_hex_color('#aBcDeF') is True

    def test_invalid_hex_color_missing_hash(self):
        """Test that colors without # are invalid."""
        assert is_valid_hex_color('abc') is False
        assert is_valid_hex_color('abcdef') is False

    def test_invalid_hex_color_wrong_length(self):
        """Test that wrong-length colors are invalid."""
        assert is_valid_hex_color('#ab') is False
        assert is_valid_hex_color('#abcd') is False
        assert is_valid_hex_color('#abcde') is False
        assert is_valid_hex_color('#abcdefg') is False

    def test_invalid_hex_color_invalid_characters(self):
        """Test that colors with invalid characters are invalid."""
        assert is_valid_hex_color('#ghijkl') is False
        assert is_valid_hex_color('#xyz') is False
        assert is_valid_hex_color('#123g') is False

    def test_invalid_hex_color_empty(self):
        """Test that empty strings are invalid."""
        assert is_valid_hex_color('') is False
        assert is_valid_hex_color('#') is False

    def test_invalid_hex_color_non_string(self):
        """Test that non-string values are invalid."""
        assert is_valid_hex_color(123) is False
        assert is_valid_hex_color(None) is False
        assert is_valid_hex_color([]) is False
        assert is_valid_hex_color({}) is False

    def test_normalize_hex_color_uppercase(self):
        """Test that hex colors preserve their case (no normalization)."""
        assert normalize_hex_color('#abc') == '#abc'
        assert normalize_hex_color('#abcdef') == '#abcdef'
        assert normalize_hex_color('#aBc123') == '#aBc123'

    def test_normalize_hex_color_preserves_format(self):
        """Test that normalization preserves 3 vs 6 digit format."""
        assert normalize_hex_color('#abc') == '#abc'
        assert normalize_hex_color('#abcdef') == '#abcdef'

    def test_normalize_hex_color_strips_whitespace(self):
        """Test that normalization strips whitespace."""
        assert normalize_hex_color(' #abc ') == '#abc'
        assert normalize_hex_color('  #abcdef  ') == '#abcdef'

    def test_valid_icon_url_assets(self):
        """Test validation of assets/ icon URLs."""
        assert is_valid_icon_url('assets/icons/test.png') is True
        assert is_valid_icon_url('assets/test.png') is True
        assert is_valid_icon_url('assets/') is True

    def test_valid_icon_url_api(self):
        """Test validation of /api/icons/ icon URLs."""
        assert is_valid_icon_url('/api/icons/abc123') is True
        assert is_valid_icon_url('/api/icons/user/hash123') is True

    def test_invalid_icon_url_external_http(self):
        """Test that external HTTP URLs are rejected."""
        assert is_valid_icon_url('http://example.com/icon.png') is False
        assert is_valid_icon_url('https://example.com/icon.png') is False

    def test_invalid_icon_url_relative(self):
        """Test that relative paths without assets/ or /api/icons/ are rejected."""
        assert is_valid_icon_url('icons/test.png') is False
        assert is_valid_icon_url('/icons/test.png') is False
        assert is_valid_icon_url('../icons/test.png') is False

    def test_invalid_icon_url_empty(self):
        """Test that empty strings are invalid."""
        assert is_valid_icon_url('') is False

    def test_invalid_icon_url_non_string(self):
        """Test that non-string values are invalid."""
        assert is_valid_icon_url(123) is False
        assert is_valid_icon_url(None) is False
        assert is_valid_icon_url([]) is False
        assert is_valid_icon_url({}) is False

    def test_describe_color_format(self):
        """Test color format description."""
        description = describe_color_format('pointColor')
        assert 'pointColor' in description
        assert 'hex color' in description.lower()
        assert '#0f3' in description or '#00ff30' in description

    def test_describe_icon_format(self):
        """Test icon format description."""
        description = describe_icon_format('pointIcon')
        assert 'pointIcon' in description
        assert 'icon' in description.lower()
        assert 'assets/' in description
        assert '/api/icons/' in description


