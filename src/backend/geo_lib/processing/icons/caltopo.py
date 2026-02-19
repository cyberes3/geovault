import re
import traceback
from typing import Optional
from urllib.parse import urlparse, parse_qs, unquote

from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger()


def _is_allowed_caltopo_netloc(netloc: str) -> bool:
    """Return True if netloc is caltopo.com or a subdomain (e.g. api.caltopo.com). Rejects e.g. caltopo.com.evil.com."""
    if not netloc:
        return False
    n = netloc.lower()
    return n == 'caltopo.com' or n.endswith('.caltopo.com')


def _fix_nested_caltopo_url(url: str) -> str:
    """
    Fix nested CalTopo URLs that occur when CalTopo reimports files.

    When CalTopo reimports, it creates nested URLs where the original URL
    is URL-encoded inside the cfg parameter:
    - Original: https://caltopo.com/icon.png?cfg=point%2CFF0000
    - Nested: http://caltopo.com/icon.png?cfg=http%3A%2F%2Fcaltopo.com%2Ficon.png%3Fcfg%3Dpoint%252CFF0000

    This function detects and extracts the inner URL.

    Args:
        url: Icon URL (potentially nested)

    Returns:
        Fixed URL with inner URL extracted, or original URL if not nested
    """
    try:
        parsed = urlparse(url)

        if not _is_allowed_caltopo_netloc(parsed.netloc):
            return url

        if parsed.path.lower() != '/icon.png':
            return url

        query_params = parse_qs(parsed.query)

        if 'cfg' not in query_params:
            return url

        cfg_value = query_params['cfg'][0]
        cfg_decoded = unquote(cfg_value)

        # Find a candidate inner CalTopo URL by regex (no substring gate)
        inner_url_match = re.search(r'(https?://caltopo\.com/icon\.png\?cfg=[^#]+(?:#1\.0)?)', cfg_decoded, re.IGNORECASE)
        if inner_url_match:
            inner_url = inner_url_match.group(1)
            inner_parsed = urlparse(inner_url)
            if (
                inner_parsed.scheme in ('http', 'https')
                and _is_allowed_caltopo_netloc(inner_parsed.netloc)
                and inner_parsed.path.lower() == '/icon.png'
            ):
                return inner_url

        # Not nested or validation failed, return original
        return url
    except:
        _logger.debug(f"Failed to fix nested CalTopo URL {url}: {traceback.format_exc()}")
        return url


def _is_caltopo_url(url: str) -> bool:
    parsed = urlparse(url)
    return _is_allowed_caltopo_netloc(parsed.netloc)


def _is_caltopo_point_icon(url: str) -> bool:
    """
    Check if a CalTopo URL is the default point icon.

    CalTopo point icons have format: http://caltopo.com/icon.png?cfg=point
    or http://caltopo.com/icon.png?cfg=c%3Apoint (URL encoded)

    Args:
        url: Icon URL from CalTopo

    Returns:
        True if this is a point icon, False otherwise
    """
    parsed = urlparse(url)

    # Check if it's a CalTopo URL
    if not _is_caltopo_url(url):
        return False

    # Check if path is /icon.png
    if parsed.path.lower() != '/icon.png':
        return False

    # Parse query parameters
    query_params = parse_qs(parsed.query)

    # Get cfg parameter
    if 'cfg' not in query_params:
        return False

    cfg_value = query_params['cfg'][0]
    # URL decode
    cfg_decoded = unquote(cfg_value)

    # Check if it starts with "point" (could be "point" or "c:point" or similar)
    # The point icon typically has cfg=point or cfg=c:point
    return cfg_decoded.startswith('point') or cfg_decoded.startswith('c:point')


def _extract_color_from_caltopo_url(url: str) -> Optional[str]:
    """
    Extract color from CalTopo icon URL.

    CalTopo URLs have format: http://caltopo.com/icon.png?cfg=point%2CFF0000%231.0
    After decoding: cfg=point,FF0000#1.0
    The color is the hex value (FF0000) which should be converted to #FF0000

    Args:
        url: Icon URL from CalTopo

    Returns:
        Hex color string (e.g., '#FF0000') or None if not a CalTopo URL or color can't be extracted
    """
    parsed = urlparse(url)

    # Check if it's a CalTopo URL
    if not _is_allowed_caltopo_netloc(parsed.netloc):
        return None

    # Parse query parameters
    query_params = parse_qs(parsed.query)

    # Get cfg parameter
    if 'cfg' not in query_params:
        return None

    cfg_value = query_params['cfg'][0]
    # URL decode
    cfg_decoded = unquote(cfg_value)

    # Format is typically: point,COLOR#SCALE or similar
    # Look for hex color pattern (6 hex digits) preceded by a comma
    color_match = re.search(r',([0-9A-Fa-f]{6})', cfg_decoded)
    if color_match:
        hex_color = color_match.group(1).upper()
        return f'#{hex_color}'

    return None
