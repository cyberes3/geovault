import unittest
import sys
import os
from unittest.mock import MagicMock

# Mock django
sys.modules['django'] = MagicMock()
sys.modules['django.conf'] = MagicMock()
settings = MagicMock()
settings.ICON_PROCESSING_ENABLED = True
settings.ICON_FETCH_TIMEOUT = 5
sys.modules['django.conf'].settings = settings

# Add src to path
sys.path.append(os.path.join(os.path.dirname(__file__), '../backend'))

# Mock geo_lib.logging.console since we don't want to initialize logger
sys.modules['geo_lib.logging.console'] = MagicMock()
sys.modules['geo_lib.processing.logging'] = MagicMock()

from geo_lib.processing.icon_manager import _fix_nested_caltopo_url, _extract_color_from_caltopo_url

class TestCalTopoIcons(unittest.TestCase):
    def test_nested_url_point_2(self):
        """Test parsing of the nested URL from Point #2 (Blue)"""
        # The URL from Test Items.kml for Point #2
        url = "http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dpoint%252C0000FF%25231.0%231.0"
        
        # 1. Test fixing the nested URL
        fixed_url = _fix_nested_caltopo_url(url)
        print(f"Original: {url}")
        print(f"Fixed   : {fixed_url}")
        
        # 2. Test extracting color from the fixed URL
        color = _extract_color_from_caltopo_url(fixed_url)
        print(f"Color   : {color}")
        
        # Should be Blue (#0000FF)
        self.assertEqual(color, "#0000FF", f"Expected #0000FF but got {color}")

    def test_nested_url_point_1(self):
        """Test parsing of the nested URL from Point #1 (Red)"""
        url = "http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dpoint%252CFF0000%25231.0%231.0"
        
        fixed_url = _fix_nested_caltopo_url(url)
        color = _extract_color_from_caltopo_url(fixed_url)
        
        self.assertEqual(color, "#FF0000")

    def test_nested_url_custom_point(self):
        """Test parsing of a custom target point"""
        url = "http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dc%253Atarget2%252CFF0000%25231.0%231.0"
        
        fixed_url = _fix_nested_caltopo_url(url)
        color = _extract_color_from_caltopo_url(fixed_url)
        
        self.assertEqual(color, "#FF0000")

    def test_already_fixed_url(self):
        """Test parsing of an already clean URL"""
        url = "http://caltopo.com/icon.png?cfg=point,0000FF#1.0"
        
        fixed_url = _fix_nested_caltopo_url(url)
        # Should be unchanged or equivalent
        self.assertIn("point,0000FF", fixed_url)
        
        color = _extract_color_from_caltopo_url(fixed_url)
        self.assertEqual(color, "#0000FF")

    def test_false_positive_prevention(self):
        """Test that the regex doesn't match hex-like strings in the type name"""
        # 'badc0d' is a valid 6-digit hex string, but here it's part of the type/config name
        # The color is 0000FF
        url = "http://caltopo.com/icon.png?cfg=badc0d,0000FF#1.0"
        
        color = _extract_color_from_caltopo_url(url)
        # Old regex would match 'badc0d' -> #BADC0D
        # New regex should match '0000FF' -> #0000FF
        self.assertEqual(color, "#0000FF", f"Should pick actual color #0000FF, not type name. Got {color}")

if __name__ == '__main__':
    unittest.main()

