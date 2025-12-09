from xml.etree import ElementTree as ET


def _is_valid_kml(root: ET.Element) -> bool:
    """Check if the XML is a valid KML document with geographic features."""
    # Check for KML namespace
    if 'kml' not in root.tag.lower():
        return False

    # Check for geographic features (Placemark, GroundOverlay, ScreenOverlay)
    # These are the elements that actually contain geographic data
    geographic_elements = ['placemark', 'groundoverlay', 'screenoverlay']
    has_geographic_features = any(
        any(geo in elem.tag.lower() for geo in geographic_elements)
        for elem in root.iter()
    )

    return has_geographic_features


def _is_valid_gpx(root: ET.Element) -> bool:
    """Check if the XML is a valid GPX document."""
    # Check for GPX namespace
    if 'gpx' not in root.tag.lower():
        return False

    # Check for required GPX elements (handle namespaced tags)
    required_elements = ['trk', 'rte', 'wpt']
    has_required = any(
        any(req in elem.tag.lower() for req in required_elements)
        for elem in root.iter()
    )

    return has_required
