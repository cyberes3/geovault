"""
Tests for CalTopo icon URL detection and processing.
"""
import pytest
from geo_lib.processing.icon_manager import (
    _is_caltopo_point_icon,
    _extract_color_from_caltopo_url,
    _fix_nested_caltopo_url,
)


class TestCalTopoIconDetection:
    """Test CalTopo icon URL detection functions."""

    def test_point_icon_with_color(self):
        """Test detection of point icon with color parameter."""
        url = "http://caltopo.com/icon.png?cfg=point%2CFF0000%231.0"
        assert _is_caltopo_point_icon(url) is True
        assert _extract_color_from_caltopo_url(url) == "#FF0000"

    def test_point_icon_without_color(self):
        """Test detection of point icon WITHOUT color parameter (the bug fix)."""
        url = "http://caltopo.com/icon.png?cfg=point%231.0"
        assert _is_caltopo_point_icon(url) is True
        # No color in URL, so extraction should return None
        # (The processing logic will default to black #000000 for CalTopo points)
        assert _extract_color_from_caltopo_url(url) is None

    def test_simple_point_icon(self):
        """Test detection of simple point icon without scale or color."""
        url = "http://caltopo.com/icon.png?cfg=point"
        assert _is_caltopo_point_icon(url) is True
        # No color in URL, so extraction should return None
        # (The processing logic will default to black #000000 for CalTopo points)
        assert _extract_color_from_caltopo_url(url) is None

    def test_c_point_icon(self):
        """Test detection of c:point variant."""
        url = "http://caltopo.com/icon.png?cfg=c%3Apoint"
        assert _is_caltopo_point_icon(url) is True
        # No color in URL, so extraction should return None
        # (The processing logic will default to black #000000 for CalTopo points)
        assert _extract_color_from_caltopo_url(url) is None

    def test_c_point_icon_with_color(self):
        """Test detection of c:point with color."""
        url = "http://caltopo.com/icon.png?cfg=c%3Apoint%2C00FF00%231.0"
        assert _is_caltopo_point_icon(url) is True
        assert _extract_color_from_caltopo_url(url) == "#00FF00"

    def test_non_point_icon_with_color(self):
        """Test that non-point icons are not detected as point icons."""
        url = "http://caltopo.com/icon.png?cfg=campfire%2CFF0000%231.0"
        assert _is_caltopo_point_icon(url) is False
        assert _extract_color_from_caltopo_url(url) == "#FF0000"

    def test_non_point_icon_target(self):
        """Test that target icons are not detected as point icons."""
        url = "http://caltopo.com/icon.png?cfg=c%3Atarget2%2CFF0000%231.0"
        assert _is_caltopo_point_icon(url) is False
        assert _extract_color_from_caltopo_url(url) == "#FF0000"

    def test_https_caltopo_url(self):
        """Test that HTTPS CalTopo URLs are handled correctly."""
        url = "https://caltopo.com/icon.png?cfg=point%2C0000FF%231.0"
        assert _is_caltopo_point_icon(url) is True
        assert _extract_color_from_caltopo_url(url) == "#0000FF"

    def test_non_caltopo_url(self):
        """Test that non-CalTopo URLs are rejected."""
        url = "http://example.com/icon.png?cfg=point"
        assert _is_caltopo_point_icon(url) is False
        assert _extract_color_from_caltopo_url(url) is None

    def test_caltopo_url_without_icon_png(self):
        """Test that CalTopo URLs without /icon.png path are rejected."""
        url = "http://caltopo.com/other.png?cfg=point"
        assert _is_caltopo_point_icon(url) is False

    def test_caltopo_url_without_cfg_param(self):
        """Test that CalTopo URLs without cfg parameter are rejected."""
        url = "http://caltopo.com/icon.png"
        assert _is_caltopo_point_icon(url) is False
        assert _extract_color_from_caltopo_url(url) is None

    def test_color_extraction_lowercase_hex(self):
        """Test that lowercase hex colors are normalized to uppercase."""
        url = "http://caltopo.com/icon.png?cfg=point%2Cabcdef%231.0"
        assert _extract_color_from_caltopo_url(url) == "#ABCDEF"

    def test_color_extraction_mixed_case(self):
        """Test that mixed case hex colors are normalized to uppercase."""
        url = "http://caltopo.com/icon.png?cfg=campfire%2CaBc123%231.0"
        assert _extract_color_from_caltopo_url(url) == "#ABC123"

    def test_nested_caltopo_url_point_with_color(self):
        """Test fixing nested CalTopo URL for point with color."""
        nested_url = "http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dpoint%252CFF0000%25231.0%231.0"
        fixed_url = _fix_nested_caltopo_url(nested_url)
        
        # The fixed URL should be the inner URL
        assert "cfg=point" in fixed_url or "cfg=point%2C" in fixed_url
        assert _is_caltopo_point_icon(fixed_url) is True
        assert _extract_color_from_caltopo_url(fixed_url) == "#FF0000"

    def test_nested_caltopo_url_non_point(self):
        """Test fixing nested CalTopo URL for non-point icon."""
        nested_url = "http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dc%253Atarget2%252CFF0000%25231.0%231.0"
        fixed_url = _fix_nested_caltopo_url(nested_url)
        
        # The fixed URL should be the inner URL
        assert _is_caltopo_point_icon(fixed_url) is False
        assert _extract_color_from_caltopo_url(fixed_url) == "#FF0000"

    def test_non_nested_url_unchanged(self):
        """Test that non-nested URLs are returned unchanged."""
        url = "http://caltopo.com/icon.png?cfg=point%2CFF0000%231.0"
        fixed_url = _fix_nested_caltopo_url(url)
        assert fixed_url == url

    def test_null_cfg_parameter(self):
        """Test handling of null cfg parameter (sometimes seen in exports)."""
        url = "http://caltopo.com/icon.png?cfg=null%231.0"
        # null is not a point icon
        assert _is_caltopo_point_icon(url) is False
        # null has no color
        assert _extract_color_from_caltopo_url(url) is None

    def test_empty_cfg_parameter(self):
        """Test handling of empty cfg parameter."""
        url = "http://caltopo.com/icon.png?cfg="
        assert _is_caltopo_point_icon(url) is False
        assert _extract_color_from_caltopo_url(url) is None


class TestCalTopoIconProcessingIntegration:
    """Integration tests for CalTopo icon processing logic."""

    def test_point_without_color_should_use_black_default(self):
        """
        Test that a point icon without color uses black as the default.
        This is the regression test for the bug where cfg=point#1.0 was not
        recognized because it had no color parameter.
        
        CalTopo defaults to black for point icons without a color parameter.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        url = "http://caltopo.com/icon.png?cfg=point%231.0"
        
        # Verify detection works correctly
        is_point = _is_caltopo_point_icon(url)
        color = _extract_color_from_caltopo_url(url)
        
        assert is_point is True, "Should be detected as a point icon"
        assert color is None, "Should have no color in the URL"
        
        # Now test the actual processing function
        new_href, extracted_color = _process_single_icon_href(url, 'kml')
        
        assert new_href is None, "Point icons should not be fetched"
        assert extracted_color == '#000000', "Should default to black when no color is specified"

    def test_point_with_color_should_not_fetch_icon(self):
        """Test that a point icon with color is detected and color is extracted."""
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        url = "http://caltopo.com/icon.png?cfg=point%2CFF0000%231.0"
        
        is_point = _is_caltopo_point_icon(url)
        color = _extract_color_from_caltopo_url(url)
        
        assert is_point is True, "Should be detected as a point icon"
        assert color == "#FF0000", "Should extract the color"
        
        # Test the actual processing function
        new_href, extracted_color = _process_single_icon_href(url, 'kml')
        
        assert new_href is None, "Point icons should not be fetched"
        assert extracted_color == "#FF0000", "Should use the specified color"

    def test_non_point_icon_should_fetch(self):
        """Test that non-point CalTopo icons should be fetched."""
        from geo_lib.processing.icon_manager import _process_single_icon_href
        from unittest.mock import patch
        
        url = "http://caltopo.com/icon.png?cfg=campfire%2CFF0000%231.0"
        
        is_point = _is_caltopo_point_icon(url)
        color = _extract_color_from_caltopo_url(url)
        
        assert is_point is False, "Should NOT be detected as a point icon"
        assert color == "#FF0000", "Should still extract the color"
        
        # Mock the icon fetching to avoid actually downloading
        with patch('geo_lib.processing.icon_manager.process_icon_href') as mock_fetch:
            mock_fetch.return_value = 'assets/icons/local_icon.png'
            
            new_href, extracted_color = _process_single_icon_href(url, 'kml')
            
            # Verify fetch was called for non-point icons
            mock_fetch.assert_called_once()
            assert new_href == 'assets/icons/local_icon.png', "Should return fetched icon path"
            assert extracted_color == "#FF0000", "Should extract the color"

    def test_href_mapping_with_point_icon_without_color(self):
        """
        Test href_mapping with a CalTopo point icon that has no color.
        This tests the cascading fallback logic: mapped_color -> original_color -> black default.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        # Original URL has no color
        original_url = "http://caltopo.com/icon.png?cfg=point%231.0"
        # Mapped URL also has no color
        mapped_url = "http://caltopo.com/icon.png?cfg=point"
        
        href_mapping = {original_url: mapped_url}
        
        new_href, extracted_color = _process_single_icon_href(
            original_url, 'kml', href_mapping=href_mapping
        )
        
        assert new_href is None, "Point icons should not be fetched even with mapping"
        assert extracted_color == '#000000', "Should default to black when neither URL has color"

    def test_href_mapping_with_point_icon_original_has_color(self):
        """
        Test href_mapping where original has color but mapped doesn't.
        Should use original color as fallback.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        # Original URL has color
        original_url = "http://caltopo.com/icon.png?cfg=point%2CFF0000%231.0"
        # Mapped URL has no color
        mapped_url = "http://caltopo.com/icon.png?cfg=point"
        
        href_mapping = {original_url: mapped_url}
        
        new_href, extracted_color = _process_single_icon_href(
            original_url, 'kml', href_mapping=href_mapping
        )
        
        assert new_href is None, "Point icons should not be fetched"
        assert extracted_color == '#FF0000', "Should use original color when mapped has none"

    def test_href_mapping_with_point_icon_mapped_has_color(self):
        """
        Test href_mapping where mapped URL has color.
        Note: When the original URL is already a point icon, it returns immediately
        without checking the mapping. The mapping is only used for non-point icons.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        # Original URL has no color and is a point icon
        original_url = "http://caltopo.com/icon.png?cfg=point"
        # Mapped URL has color (but won't be checked since original is a point icon)
        mapped_url = "http://caltopo.com/icon.png?cfg=point%2C00FF00%231.0"
        
        href_mapping = {original_url: mapped_url}
        
        new_href, extracted_color = _process_single_icon_href(
            original_url, 'kml', href_mapping=href_mapping
        )
        
        assert new_href is None, "Point icons should not be fetched"
        # Since original is a point icon with no color, it returns early with black default
        # The mapping is not checked because the original URL already matches point icon pattern
        assert extracted_color == '#000000', "Should use black default for point icon without color"

    def test_href_mapping_with_non_point_to_point_icon(self):
        """
        Test href_mapping where a non-point icon is mapped to a point icon.
        This is the actual use case for href_mapping - when icons are remapped during processing.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        
        # Original URL is a non-point icon
        original_url = "http://caltopo.com/icon.png?cfg=campfire%2CFF0000"
        # Mapped URL is a point icon with color
        mapped_url = "http://caltopo.com/icon.png?cfg=point%2C00FF00%231.0"
        
        href_mapping = {original_url: mapped_url}
        
        new_href, extracted_color = _process_single_icon_href(
            original_url, 'kml', href_mapping=href_mapping
        )
        
        assert new_href is None, "Mapped point icons should not be fetched"
        assert extracted_color == '#00FF00', "Should use mapped point icon color"

    def test_non_point_caltopo_icon_without_color_should_fetch(self):
        """
        Test that non-point CalTopo icons WITHOUT color still attempt to fetch.
        This is an edge case - CalTopo should always include colors, but we handle it gracefully.
        """
        from geo_lib.processing.icon_manager import _process_single_icon_href
        from unittest.mock import patch
        
        # A CalTopo campfire icon without a color parameter (unusual but possible)
        url = "http://caltopo.com/icon.png?cfg=campfire%231.0"
        
        is_point = _is_caltopo_point_icon(url)
        color = _extract_color_from_caltopo_url(url)
        
        assert is_point is False, "Should NOT be detected as a point icon"
        assert color is None, "Should have no color"
        
        # Mock the icon fetching
        with patch('geo_lib.processing.icon_manager.process_icon_href') as mock_fetch:
            mock_fetch.return_value = 'assets/icons/local_icon.png'
            
            new_href, extracted_color = _process_single_icon_href(url, 'kml')
            
            # Should still attempt to fetch the icon even without color
            mock_fetch.assert_called_once()
            assert new_href == 'assets/icons/local_icon.png', "Should return fetched icon path"
            assert extracted_color is None, "Should have no extracted color"

