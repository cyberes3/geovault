"""
Icon manager: resolves icon hrefs found in KML/KMZ/GeoJSON data, fetching or
extracting the referenced image and handing it off to `storage.py` to be
validated, re-encoded, and persisted. Also handles CalTopo-specific icon URL
conventions (e.g. deriving a marker color instead of fetching an image).
"""

from typing import Dict, Optional, Tuple
from urllib.parse import urlparse

from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_setting
from geo_lib.processing.icons.caltopo import _fix_nested_caltopo_url, _is_caltopo_url, _is_caltopo_point_icon, _extract_color_from_caltopo_url
from geo_lib.processing.icons.get import extract_icon_from_kmz, fetch_remote_icon
from geo_lib.processing.icons.storage import _is_valid_icon_type, store_icon
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel

_logger = get_tagged_logger()


def process_icon_href(href: str, file_type: str, import_log: ImportLog, stats: Dict[str, int], file_data: Optional[bytes] = None) -> Optional[str]:
    """
    Main entry point for processing icon hrefs.
    Handles both KMZ embedded icons and KML remote icons.
    
    Args:
        href: Icon href from KML/KMZ (can be URL or relative path)
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        import_log: ImportLog for recording user-visible warnings
        stats: Statistics dictionary for tracking icon processing
        
    Returns:
        Local URL path for icon, or None if processing fails
    """
    if not get_setting('ICON_PROCESSING_ENABLED', False):
        return None

    if not href or not isinstance(href, str):
        return None

    # Validate icon type
    if not _is_valid_icon_type(href):
        _logger.debug(f"Skipping non-image href: {href}")
        return None

    icon_data = None

    # Check if it's a remote URL
    parsed = urlparse(href)
    is_remote = parsed.scheme in ('http', 'https')

    if file_type.lower() == 'kmz':
        # For KMZ, check if it's an embedded icon (not a remote URL)
        if not is_remote and file_data:
            # Extract from KMZ archive
            icon_data = extract_icon_from_kmz(file_data, href, import_log)
        elif is_remote:
            # Remote URL in KMZ - fetch it
            icon_data = fetch_remote_icon(href, get_setting('ICON_FETCH_TIMEOUT', 10), import_log)
    elif file_type.lower() == 'kml':
        # For KML, fetch remote icons
        if is_remote:
            icon_data = fetch_remote_icon(href, get_setting('ICON_FETCH_TIMEOUT', 10), import_log)
        else:
            # Local path in KML - not supported (would need file system access)
            return None

    if icon_data:
        result = store_icon(icon_data, href, import_log, stats)
        # store_icon already incremented stats (successful or failed)
        return result

    # Failed to get icon_data (fetch/extract failed)
    stats['failed'] += 1
    return None


def process_geojson_icons(
        geojson_data: dict,
        file_type: str,
        import_log: ImportLog,
        file_data: Optional[bytes] = None
) -> dict:
    """
    Process all icon hrefs in GeoJSON data structure.
    Recursively searches for icon hrefs in properties and replaces them with local paths.
    Only processes icons for Point geometries (skips LineString, Polygon, etc.).
    
    Args:
        geojson_data: GeoJSON data dictionary
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        import_log: ImportLog for recording user-visible warnings
        
    Returns:
        Modified GeoJSON data with replaced icon hrefs
    """
    if not get_setting('ICON_PROCESSING_ENABLED', False):
        return geojson_data

    if not isinstance(geojson_data, dict):
        return geojson_data

    # Statistics tracking
    stats = {
        'successful': 0,
        'failed': 0,
        'icons_processed': 0  # Track if any icons were processed (even if they don't increment counters)
    }

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
                    import_log,
                    stats,
                    file_data,
                    href_mapping,
                    is_point=True
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

    # Log statistics - log if any icons were processed (even if counters are 0, e.g., CalTopo point icons)
    if stats['icons_processed'] > 0:
        import_log.add(
            f"Icon processing complete: {stats['successful']} successfully extracted, {stats['failed']} failed",
            "Icon Processing",
            DatabaseLogLevel.INFO
        )

    return geojson_data


def _process_single_icon_href(
        href: str,
        file_type: str,
        import_log: ImportLog,
        stats: Dict[str, int],
        file_data: Optional[bytes] = None,
        href_mapping: Optional[Dict[str, str]] = None
) -> Tuple[Optional[str], Optional[str]]:
    """
    Process a single icon href and return the result.
    
    Args:
        href: Icon href to process
        file_type: File type ('kmz' or 'kml')
        file_data: File data as bytes (required for KMZ)
        href_mapping: Optional pre-computed mapping of old hrefs to new hrefs
        import_log: ImportLog for recording warnings
        stats: Statistics dictionary for tracking icon processing
        
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
        new_href = process_icon_href(href, file_type, import_log, stats, file_data)
        if new_href:
            # process_icon_href already incremented successful via store_icon, don't double-count
            return new_href, caltopo_color  # Return icon and color
        # Fetch failed but we have color - return color
        # process_icon_href already incremented failed, don't double-count
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
    new_href = process_icon_href(href, file_type, import_log, stats, file_data)
    if new_href:
        # process_icon_href already incremented successful via store_icon, don't double-count
        return new_href, None

    # Fetch failed and no color - log warning
    # process_icon_href already incremented failed, don't double-count
    import_log.add(
        f"Failed to load icon '{href}', using default red icon",
        "Icon Processing",
        DatabaseLogLevel.WARNING
    )
    return None, None


def _process_properties_icons(
        properties: dict,
        file_type: str,
        import_log: ImportLog,
        stats: Dict[str, int],
        file_data: Optional[bytes] = None,
        href_mapping: Optional[Dict[str, str]] = None,
        is_point: bool = False
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
        import_log: ImportLog for recording warnings
        stats: Statistics dictionary for tracking icon processing
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

        # Track that we're processing an icon
        stats.setdefault('icons_processed', 0)
        stats['icons_processed'] += 1

        # Check if this is a CalTopo URL before processing
        is_caltopo = _is_caltopo_url(href)

        # Process the href
        new_href, extracted_color = _process_single_icon_href(
            href, file_type, import_log, stats, file_data, href_mapping
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
            _logger.warning(f"CalTopo URL detected but color extraction failed: {href}")
            import_log.add(
                f"CalTopo URL detected but color extraction failed: {href}",
                "Icon Processing",
                DatabaseLogLevel.WARNING
            )
            stats['failed'] += 1
            del properties[prop_name]
            if 'marker-color' not in properties or not properties['marker-color']:
                properties['marker-color'] = '#ff0000'
        elif new_href:
            # Icon successfully fetched/stored - update href to local path
            properties[prop_name] = new_href
        else:
            # Icon fetch failed - remove icon property and set default red color if needed
            # Note: failed count is already incremented in _process_single_icon_href or process_icon_href
            del properties[prop_name]
            if 'marker-color' not in properties or not properties['marker-color']:
                properties['marker-color'] = '#ff0000'

    # Process nested structures (e.g., style objects)
    for key, value in properties.items():
        if isinstance(value, dict):
            _process_properties_icons(value, file_type, import_log, stats, file_data, href_mapping, is_point=True)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    _process_properties_icons(item, file_type, import_log, stats, file_data, href_mapping, is_point=True)
        elif isinstance(value, str) and key not in icon_property_names:
            # Check if any string value matches a href in the mapping
            if href_mapping and value in href_mapping:
                properties[key] = href_mapping[value]
