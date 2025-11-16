"""
Icon manager for processing and storing icons from KML/KMZ files.
Handles extraction from KMZ archives, fetching from remote URLs, and storage with hash-based filenames.
"""

import hashlib
import io
import logging
import os
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import urlparse, parse_qs, unquote
from urllib.request import urlopen, Request
from urllib.error import URLError, HTTPError

from django.conf import settings

logger = logging.getLogger(__name__)

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
            # Try exact match first
            if normalized_path in zip_file.namelist():
                return zip_file.read(normalized_path)
            
            # Try case-insensitive search
            for entry_name in zip_file.namelist():
                if entry_name.lower() == normalized_path.lower():
                    return zip_file.read(entry_name)
            
            logger.warning(f"Icon not found in KMZ archive: {icon_path}")
            return None
            
    except zipfile.BadZipFile:
        logger.error(f"Invalid KMZ/ZIP file format")
        return None
    except Exception as e:
        logger.error(f"Failed to extract icon from KMZ: {str(e)}")
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
        req = Request(url, headers={'User-Agent': 'GeoServer/1.0'})
        
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
        logger.warning(f"URL error fetching icon: {url} - {str(e)}")
        return None
    except TimeoutError:
        logger.warning(f"Timeout fetching icon: {url}")
        return None
    except Exception as e:
        logger.error(f"Failed to fetch remote icon: {url} - {str(e)}")
        return None


def store_icon(icon_data: bytes, original_path: str) -> Optional[str]:
    """
    Store icon using SHA-256 hash as filename.
    
    Args:
        icon_data: Icon file content as bytes
        original_path: Original icon path/URL for extension detection
        
    Returns:
        Local URL path for icon (e.g., '/api/data/icons/{hash}.png'), or None if storage fails
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
        if storage_path.exists():
            logger.debug(f"Icon already exists: {icon_hash}")
        else:
            # Write icon to storage
            storage_path.write_bytes(icon_data)
            logger.debug(f"Stored icon: {storage_path}")
        
        # Return URL path
        return f"/api/data/icons/{icon_hash}{extension}"
        
    except Exception as e:
        logger.error(f"Failed to store icon: {str(e)}")
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


def process_geojson_icons(geojson_data: dict, file_type: str, file_data: Optional[bytes] = None, kml_content: Optional[str] = None) -> dict:
    """
    Process all icon hrefs in GeoJSON data structure.
    Recursively searches for icon hrefs in properties and replaces them with local paths.
    Also extracts icon hrefs from original KML if provided.
    
    Args:
        geojson_data: GeoJSON data dictionary
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        kml_content: Original KML content as string (optional, for extracting icon hrefs)
        
    Returns:
        Modified GeoJSON data with replaced icon hrefs
    """
    if not settings.ICON_PROCESSING_ENABLED:
        return geojson_data
    
    if not isinstance(geojson_data, dict):
        return geojson_data
    
    # Create mapping of original hrefs to new hrefs
    href_mapping: Dict[str, str] = {}
    
    # Extract icon hrefs from KML if provided
    if kml_content:
        icon_hrefs = extract_icon_hrefs_from_kml(kml_content)
        for href in icon_hrefs:
            # Skip CalTopo URLs - they will be handled by extracting color
            if _extract_color_from_caltopo_url(href):
                continue
            new_href = process_icon_href(href, file_type, file_data)
            if new_href:
                href_mapping[href] = new_href
    
    # Process features
    if 'features' in geojson_data:
        for feature in geojson_data['features']:
            if isinstance(feature, dict) and 'properties' in feature:
                _process_properties_icons(feature['properties'], file_type, file_data, href_mapping)
    
    # Process properties at root level if present
    if 'properties' in geojson_data:
        _process_properties_icons(geojson_data['properties'], file_type, file_data, href_mapping)
    
    return geojson_data


def extract_icon_hrefs_from_kml(kml_content: str) -> List[str]:
    """
    Extract all icon hrefs from KML XML content.
    
    Args:
        kml_content: KML content as string
        
    Returns:
        List of icon hrefs found in the KML
    """
    icon_hrefs = []
    try:
        # Parse KML XML
        root = ET.fromstring(kml_content)
        
        # Find all href elements (icons are typically in Icon/href or ItemIcon/href)
        for href_elem in root.iter():
            if href_elem.tag.endswith('href') and href_elem.text:
                href = href_elem.text.strip()
                if href and _is_valid_icon_type(href):
                    icon_hrefs.append(href)
        
        # Remove duplicates while preserving order
        seen = set()
        unique_hrefs = []
        for href in icon_hrefs:
            if href not in seen:
                seen.add(href)
                unique_hrefs.append(href)
        
        return unique_hrefs
    except ET.ParseError:
        logger.warning("Failed to parse KML for icon extraction")
        return []
    except Exception as e:
        logger.error(f"Error extracting icon hrefs from KML: {str(e)}")
        return []


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
        # Look for hex color pattern (6 hex digits)
        color_match = re.search(r'([0-9A-Fa-f]{6})', cfg_decoded)
        if color_match:
            hex_color = color_match.group(1).upper()
            return f'#{hex_color}'
        
        return None
    except Exception as e:
        logger.debug(f"Failed to extract color from CalTopo URL {url}: {str(e)}")
        return None


def _process_properties_icons(properties: dict, file_type: str, file_data: Optional[bytes] = None, href_mapping: Optional[Dict[str, str]] = None) -> None:
    """
    Process icon hrefs in properties dictionary.
    Looks for common icon-related property names and replaces hrefs.
    
    Args:
        properties: Properties dictionary
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        href_mapping: Optional pre-computed mapping of old hrefs to new hrefs
    """
    if not isinstance(properties, dict):
        return
    
    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol',
        'styleUrl',  # KML style URLs might reference icons
    ]
    
    # Process known icon properties
    for prop_name in icon_property_names:
        if prop_name in properties and properties[prop_name]:
            href = properties[prop_name]
            if isinstance(href, str):
                # Check if this is a CalTopo URL - if so, extract color and remove icon
                color = _extract_color_from_caltopo_url(href)
                if color:
                    # Set marker-color and remove icon property
                    properties['marker-color'] = color
                    del properties[prop_name]
                    logger.debug(f"Replaced CalTopo icon with marker-color: {color}")
                    continue
                
                # Check mapping first if available
                if href_mapping and href in href_mapping:
                    mapped_href = href_mapping[href]
                    # Check if mapped href is also a CalTopo URL (shouldn't happen, but be safe)
                    mapped_color = _extract_color_from_caltopo_url(mapped_href)
                    if mapped_color:
                        properties['marker-color'] = mapped_color
                        del properties[prop_name]
                        logger.debug(f"Replaced mapped CalTopo icon with marker-color: {mapped_color}")
                        continue
                    properties[prop_name] = mapped_href
                else:
                    # Process directly
                    new_href = process_icon_href(href, file_type, file_data)
                    if new_href:
                        properties[prop_name] = new_href
    
    # Also check for nested structures (e.g., style objects)
    for key, value in properties.items():
        if isinstance(value, dict):
            _process_properties_icons(value, file_type, file_data, href_mapping)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    _process_properties_icons(item, file_type, file_data, href_mapping)
        elif isinstance(value, str) and key not in icon_property_names:
            # Check if any string value matches a href in the mapping
            if href_mapping and value in href_mapping:
                properties[key] = href_mapping[value]

