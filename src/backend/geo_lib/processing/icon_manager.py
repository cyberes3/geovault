"""
Icon manager for processing and storing icons from KML/KMZ files.
Handles extraction from KMZ archives, fetching from remote URLs, and storage with hash-based filenames.
"""

import hashlib
import io
import os
import re
import traceback
import zipfile
from pathlib import Path
from typing import Dict, Optional, Tuple
from urllib.error import URLError, HTTPError
from urllib.parse import urlparse, parse_qs, unquote
from urllib.request import urlopen, Request

from django.conf import settings

from geo_lib.logging.console import get_job_logger
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel

logger = get_job_logger()

# Valid image file extensions
VALID_ICON_EXTENSIONS = {'.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico'}


def _get_icon_extension(filename_or_url: str) -> Optional[str]:
    """
    Extract file extension from filename or URL.
    
    Args:
        filename_or_url: Filename or URL string
        
    Returns:
        Extension with leading dot, or None if not found
    """
    # Parse URL or filename
    parsed = urlparse(filename_or_url)
    path = parsed.path or filename_or_url

    # Extract extension
    ext = os.path.splitext(path)[1].lower()
    return ext if ext in VALID_ICON_EXTENSIONS else None


def _is_valid_icon_type(filename_or_url: str) -> bool:
    """
    Check if the file appears to be a valid image type.
    
    Args:
        filename_or_url: Filename or URL string
        
    Returns:
        True if valid image type, False otherwise
    """
    ext = _get_icon_extension(filename_or_url)
    return ext is not None


def _calculate_hash(icon_data: bytes) -> str:
    """
    Calculate SHA-256 hash of icon data.
    
    Args:
        icon_data: Icon file content as bytes
        
    Returns:
        SHA-256 hash as hexadecimal string
    """
    return hashlib.sha256(icon_data).hexdigest()


def _get_storage_path(icon_hash: str, extension: str) -> Path:
    """
    Get storage path for icon using hash-based directory structure.
    
    Args:
        icon_hash: SHA-256 hash of icon content
        extension: File extension with leading dot
        
    Returns:
        Path object for icon storage location
    """
    storage_dir = Path(settings.ICON_STORAGE_DIR)
    # Create subdirectory structure: {hash[0:2]}/{hash[2:4]}/
    subdir = storage_dir / icon_hash[0:2] / icon_hash[2:4]
    subdir.mkdir(parents=True, exist_ok=True)
    return subdir / f"{icon_hash}{extension}"


def extract_icon_from_kmz(kmz_data: bytes, icon_path: str) -> Optional[bytes]:
    """
    Extract icon from KMZ ZIP archive.
    
    Args:
        kmz_data: KMZ file content as bytes
        icon_path: Path to icon within KMZ archive (e.g., 'files/icon.png' or 'icon.png')
        
    Returns:
        Icon data as bytes, or None if extraction fails
    """
    try:
        # Normalize icon path - remove leading :/ or files/ prefix
        normalized_path = icon_path
        if normalized_path.startswith(':/'):
            normalized_path = normalized_path[2:]
        elif normalized_path.startswith('files/'):
            normalized_path = normalized_path[6:]

        # Open KMZ as ZIP archive
        with zipfile.ZipFile(io.BytesIO(kmz_data), 'r') as zip_file:
            # Build list of paths to try (original, normalized, and variations)
            paths_to_try = []
            
            # 1. Try original path first (as-is)
            if icon_path:
                paths_to_try.append(icon_path)
            
            # 2. Try normalized path (without files/ or :/)
            if normalized_path and normalized_path != icon_path:
                paths_to_try.append(normalized_path)
            
            # 3. Try with files/ prefix if not already present
            if not icon_path.startswith('files/') and not icon_path.startswith(':/'):
                paths_to_try.append(f'files/{icon_path}')
            
            # Try exact matches first
            for path in paths_to_try:
                if path in zip_file.namelist():
                    return zip_file.read(path)

            # Try case-insensitive search on all paths
            for path in paths_to_try:
                path_lower = path.lower()
                for entry_name in zip_file.namelist():
                    if entry_name.lower() == path_lower:
                        return zip_file.read(entry_name)

            logger.warning(f"Icon not found in KMZ archive: {icon_path} (tried: {', '.join(paths_to_try)})")
            return None

    except zipfile.BadZipFile:
        logger.error(f"Invalid KMZ/ZIP file format")
        return None
    except Exception as e:
        logger.error(f"Failed to extract icon from KMZ: {traceback.format_exc()}")
        return None


def fetch_remote_icon(url: str, timeout: float) -> Optional[bytes]:
    """
    Fetch icon from remote URL with timeout.
    
    Args:
        url: Remote icon URL
        timeout: Timeout in seconds
        
    Returns:
        Icon data as bytes, or None if fetch fails
    """
    try:
        # Create request with user agent
        req = Request(url, headers={'User-Agent': 'GeoVault/1.0'})

        # Fetch with timeout
        with urlopen(req, timeout=timeout) as response:
            # Check content length if available
            content_length = response.headers.get('Content-Length')
            if content_length:
                size = int(content_length)
                if size > settings.ICON_MAX_SIZE_BYTES:
                    logger.warning(f"Icon exceeds size limit: {url} ({size} bytes)")
                    return None

            # Read data with size limit
            icon_data = b''
            max_size = settings.ICON_MAX_SIZE_BYTES
            chunk_size = min(8192, max_size)

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                icon_data += chunk
                if len(icon_data) > max_size:
                    logger.warning(f"Icon exceeds size limit during download: {url}")
                    return None

            return icon_data

    except HTTPError as e:
        logger.warning(f"HTTP error fetching icon: {url} - {e.code}")
        return None
    except URLError as e:
        logger.warning(f"URL error fetching icon: {url} - {traceback.format_exc()}")
        return None
    except TimeoutError:
        logger.warning(f"Timeout fetching icon: {url}")
        return None
    except Exception as e:
        logger.error(f"Failed to fetch remote icon: {url} - {traceback.format_exc()}")
        return None


def store_icon(icon_data: bytes, original_path: str) -> Optional[str]:
    """
    Store icon using SHA-256 hash as filename.
    
    Args:
        icon_data: Icon file content as bytes
        original_path: Original icon path/URL for extension detection
        
    Returns:
        Local URL path for icon (e.g., '/api/icons/user/{hash}.png'), or None if storage fails
    """
    try:
        # Validate size
        if len(icon_data) > settings.ICON_MAX_SIZE_BYTES:
            logger.warning(f"Icon exceeds size limit: {len(icon_data)} bytes")
            return None

        # Get extension
        extension = _get_icon_extension(original_path)
        if not extension:
            logger.warning(f"Invalid icon extension: {original_path}")
            return None

        # Calculate hash
        icon_hash = _calculate_hash(icon_data)

        # Get storage path
        storage_path = _get_storage_path(icon_hash, extension)

        # Check if already exists
        if not storage_path.exists():
            # Write icon to storage
            storage_path.write_bytes(icon_data)
            logger.debug(f"Stored icon: {storage_path}")

        # Return URL path
        return f"/api/icons/user/{icon_hash}{extension}"

    except Exception as e:
        logger.error(f"Failed to store icon: {traceback.format_exc()}")
        return None


def process_icon_href(href: str, file_type: str, file_data: Optional[bytes] = None) -> Optional[str]:
    """
    Main entry point for processing icon hrefs.
    Handles both KMZ embedded icons and KML remote icons.
    
    Args:
        href: Icon href from KML/KMZ (can be URL or relative path)
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        
    Returns:
        Local URL path for icon, or None if processing fails
    """
    if not settings.ICON_PROCESSING_ENABLED:
        return None

    if not href or not isinstance(href, str):
        return None

    # Validate icon type
    if not _is_valid_icon_type(href):
        logger.debug(f"Skipping non-image href: {href}")
        return None

    icon_data = None

    # Check if it's a remote URL
    parsed = urlparse(href)
    is_remote = parsed.scheme in ('http', 'https')

    if file_type.lower() == 'kmz':
        # For KMZ, check if it's an embedded icon (not a remote URL)
        if not is_remote and file_data:
            # Extract from KMZ archive
            icon_data = extract_icon_from_kmz(file_data, href)
        elif is_remote:
            # Remote URL in KMZ - fetch it
            icon_data = fetch_remote_icon(href, settings.ICON_FETCH_TIMEOUT)
    elif file_type.lower() == 'kml':
        # For KML, fetch remote icons
        if is_remote:
            icon_data = fetch_remote_icon(href, settings.ICON_FETCH_TIMEOUT)
        else:
            # Local path in KML - not supported (would need file system access)
            logger.debug(f"Skipping local file path in KML: {href}")
            return None

    if icon_data:
        return store_icon(icon_data, href)

    return None


def process_geojson_icons(
        geojson_data: dict,
        file_type: str,
        file_data: Optional[bytes] = None,
        import_log: Optional[ImportLog] = None
) -> dict:
    """
    Process all icon hrefs in GeoJSON data structure.
    Recursively searches for icon hrefs in properties and replaces them with local paths.
    Only processes icons for Point geometries (skips LineString, Polygon, etc.).
    
    Args:
        geojson_data: GeoJSON data dictionary
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        import_log: Optional ImportLog for recording user-visible warnings
        
    Returns:
        Modified GeoJSON data with replaced icon hrefs
    """
    if not settings.ICON_PROCESSING_ENABLED:
        return geojson_data

    if not isinstance(geojson_data, dict):
        return geojson_data

    # Create mapping of original hrefs to new hrefs
    href_mapping: Dict[str, str] = {}

    # Process features - only process icons for Point geometries
    if 'features' in geojson_data:
        for feature in geojson_data['features']:
            if not isinstance(feature, dict):
                continue

            # Check geometry type - only process icons for Point features
            geometry = feature.get('geometry', {})
            geometry_type = geometry.get('type', '').lower() if isinstance(geometry, dict) else ''

            if geometry_type == 'point' and 'properties' in feature:
                _process_properties_icons(
                    feature['properties'],
                    file_type,
                    file_data,
                    href_mapping,
                    is_point=True,
                    import_log=import_log
                )
            elif geometry_type != 'point' and 'properties' in feature:
                # For non-Point features, remove icon properties entirely
                # This prevents fetching icons for LineString, Polygon, etc.
                props = feature.get('properties', {})
                icon_props = [k for k in ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol'] if k in props]
                if icon_props:
                    for prop_name in icon_props:
                        del props[prop_name]
                # Note: We keep 'styleUrl' as it's a style reference, not an icon URL

    # Process properties at root level if present
    # Root-level properties should not contain feature-specific icons, but if they do,
    # we should skip them since we don't know the geometry type
    # (Root-level properties are typically metadata, not feature icons)
    pass

    return geojson_data


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

        if 'caltopo.com' not in parsed.netloc.lower():
            return url

        if parsed.path.lower() != '/icon.png':
            return url

        query_params = parse_qs(parsed.query)

        if 'cfg' not in query_params:
            return url

        cfg_value = query_params['cfg'][0]
        cfg_decoded = unquote(cfg_value)

        # Check if cfg contains a full CalTopo URL (nested)
        if 'http://caltopo.com' in cfg_decoded.lower() or 'https://caltopo.com' in cfg_decoded.lower():
            inner_url_match = re.search(r'(https?://caltopo\.com/icon\.png\?cfg=[^#]+(?:#1\.0)?)', cfg_decoded, re.IGNORECASE)
            if inner_url_match:
                inner_url = inner_url_match.group(1)
                return inner_url

        # Not nested, return original
        return url
    except Exception:
        logger.debug(f"Failed to fix nested CalTopo URL {url}: {traceback.format_exc()}")
        return url


def _is_caltopo_url(url: str) -> bool:
    """
    Check if a URL is from CalTopo.
    
    Args:
        url: Icon URL to check
        
    Returns:
        True if this is a CalTopo icon URL, False otherwise
    """
    try:
        parsed = urlparse(url)
        return 'caltopo.com' in parsed.netloc.lower() if parsed.netloc else False
    except Exception:
        return False


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
    try:
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

    except Exception as e:
        logger.debug(f"Failed to check if CalTopo URL is point icon {url}: {traceback.format_exc()}")
        return False


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
    try:
        parsed = urlparse(url)

        # Check if it's a CalTopo URL
        if 'caltopo.com' not in parsed.netloc.lower():
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
    except Exception as e:
        return None


def _process_single_icon_href(
        href: str,
        file_type: str,
        file_data: Optional[bytes] = None,
        href_mapping: Optional[Dict[str, str]] = None,
        import_log: Optional[ImportLog] = None
) -> Tuple[Optional[str], Optional[str]]:
    """
    Process a single icon href and return the result.
    
    Args:
        href: Icon href to process
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        href_mapping: Optional pre-computed mapping of old hrefs to new hrefs
        import_log: Optional ImportLog for recording warnings
        
    Returns:
        Tuple of (new_href, extracted_color):
        - new_href: New local href if icon was fetched/stored, None if should be removed
        - extracted_color: Extracted color from CalTopo URL, or None
    """
    # START CALTOPO ICON PROCESSING

    # Fix nested CalTopo URLs first
    href = _fix_nested_caltopo_url(href)

    # Check if this is a CalTopo URL
    is_caltopo = _is_caltopo_url(href)
    
    # Extract color from CalTopo URL (works on both nested and fixed URLs)
    caltopo_color = _extract_color_from_caltopo_url(href)

    # Check if this is a CalTopo point icon - if so, skip fetching and just use the color (or default)
    if is_caltopo and _is_caltopo_point_icon(href):
        # Point icons use default marker, no need to fetch
        # CalTopo defaults to black (#000000) when no color is specified
        return None, caltopo_color if caltopo_color else '#000000'

    # Check href mapping if available
    if href_mapping and href in href_mapping:
        mapped_href = _fix_nested_caltopo_url(href_mapping[href])
        mapped_color = _extract_color_from_caltopo_url(mapped_href)
        mapped_is_caltopo = _is_caltopo_url(mapped_href)

        # If mapped href is a point icon, return the color (or default to black)
        if mapped_is_caltopo and _is_caltopo_point_icon(mapped_href):
            # Use mapped color if available, otherwise use original color, otherwise default to black
            color = mapped_color if mapped_color else (caltopo_color if caltopo_color else '#000000')
            return None, color

        # Use mapped href, prefer mapped color over original
        return mapped_href, mapped_color if mapped_color else caltopo_color

    # For non-point CalTopo icons, fetch the icon (we still want the actual icon image)
    # For non-CalTopo URLs, also fetch the icon
    if is_caltopo and caltopo_color:
        # Non-point CalTopo icon with color - fetch icon and return both
        new_href = process_icon_href(href, file_type, file_data)
        if new_href:
            return new_href, caltopo_color  # Return icon and color
        # Fetch failed but we have color - return color
        if import_log is not None:
            import_log.add(
                f"Failed to load icon '{href}', but extracted color {caltopo_color} from URL",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
        return None, caltopo_color

    # END CALTOPO ICON PROCESSING
    # ===========================================================================================================

    # Make sure that we aren't dealing with caltopo in any way
    assert caltopo_color is None

    # For non-CalTopo URLs, fetch/store the icon
    new_href = process_icon_href(href, file_type, file_data)
    if new_href:
        return new_href, None

    # Fetch failed and no color - log warning
    if import_log is not None:
        import_log.add(
            f"Failed to load icon '{href}', using default red icon",
            "Icon Processing",
            DatabaseLogLevel.WARNING
        )
    return None, None


def _process_properties_icons(
        properties: dict,
        file_type: str,
        file_data: Optional[bytes] = None,
        href_mapping: Optional[Dict[str, str]] = None,
        is_point: bool = False,
        import_log: Optional[ImportLog] = None
) -> None:
    """
    Process icon hrefs in properties dictionary.
    Looks for common icon-related property names and replaces hrefs.
    Only processes icons if is_point is True (to prevent fetching icons for LineString, Polygon, etc.).
    
    Args:
        properties: Properties dictionary
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        href_mapping: Optional pre-computed mapping of old hrefs to new hrefs
        is_point: Whether this is a Point feature (only True for Point geometries)
    """
    if not is_point or not isinstance(properties, dict):
        return

    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol'
    ]

    # Process known icon properties
    for prop_name in icon_property_names:
        if prop_name not in properties or not properties[prop_name]:
            continue

        href = properties[prop_name]
        if not isinstance(href, str):
            continue

        # Check if this is a CalTopo URL before processing
        is_caltopo = _is_caltopo_url(href)

        # Process the href
        new_href, extracted_color = _process_single_icon_href(
            href, file_type, file_data, href_mapping, import_log
        )

        # Handle the result
        if new_href and extracted_color:
            # Both icon fetched and color extracted - keep icon and set marker-color
            properties[prop_name] = new_href
            properties['marker-color'] = extracted_color
        elif extracted_color and not new_href:
            # Color extracted but no icon (point icon) - set marker-color and remove icon property
            properties['marker-color'] = extracted_color
            del properties[prop_name]
        elif is_caltopo and not extracted_color and not new_href:
            # CalTopo URL detected but both color extraction and fetch failed
            logger.warning(f"CalTopo URL detected but color extraction failed: {href}")
            del properties[prop_name]
            if 'marker-color' not in properties or not properties['marker-color']:
                properties['marker-color'] = '#ff0000'
        elif new_href:
            # Icon successfully fetched/stored - update href to local path
            properties[prop_name] = new_href
        else:
            # Icon fetch failed - remove icon property and set default red color if needed
            del properties[prop_name]
            if 'marker-color' not in properties or not properties['marker-color']:
                properties['marker-color'] = '#ff0000'

    # Process nested structures (e.g., style objects)
    for key, value in properties.items():
        if isinstance(value, dict):
            _process_properties_icons(value, file_type, file_data, href_mapping, is_point=True, import_log=import_log)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    _process_properties_icons(item, file_type, file_data, href_mapping, is_point=True, import_log=import_log)
        elif isinstance(value, str) and key not in icon_property_names:
            # Check if any string value matches a href in the mapping
            if href_mapping and value in href_mapping:
                properties[key] = href_mapping[value]
