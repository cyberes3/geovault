from xml.etree import ElementTree as ET

from geo_lib.processing.file_types import FileType, get_allowed_elements
from geo_lib.security.exceptions import FileValidationError, SecurityError

DANGEROUS_ELEMENTS = [
    'script', 'iframe', 'object', 'embed', 'applet', 'form', 'input',
    'button', 'meta'
]
HTML_DANGEROUS_ELEMENTS = [
    'link'  # HTML link elements, but KML link elements are allowed
]
DANGEROUS_ATTRIBUTES = [
    'onload', 'onerror', 'onclick', 'onmouseover', 'onfocus', 'onblur',
    'onchange', 'onsubmit', 'onreset', 'onselect', 'onunload'
]


def _secure_xml_parse(xml_content: str) -> ET.Element:
    """Parse XML with security measures against XXE attacks."""
    # Create a secure parser that disables external entities
    parser = ET.XMLParser()

    # Disable entity processing to prevent XXE attacks
    # Note: In newer Python versions, parser.entity is readonly, so we use a different approach
    try:
        # Try to disable entity processing (works in older Python versions)
        parser.entity = {}
    except (AttributeError, TypeError):
        # In newer versions, we rely on the default secure behavior
        pass

    # Parse the XML
    try:
        root = ET.fromstring(xml_content, parser=parser)
        return root
    except ET.ParseError:
        raise FileValidationError("The file contains invalid XML structure. Please check the file format and try again.")


def _check_dangerous_elements(root: ET.Element, file_type: FileType = None):
    """Check for dangerous XML elements."""
    allowed_elements = []
    if file_type:
        allowed_elements = get_allowed_elements(file_type)

    for elem in root.iter():
        # Extract the local name from namespaced tags (e.g., {namespace}tag -> tag)
        tag_name = elem.tag.split('}')[-1].lower() if '}' in elem.tag else elem.tag.lower()

        # Allow file-type-specific elements
        if tag_name in allowed_elements:
            continue

        # Check for dangerous elements
        if tag_name in DANGEROUS_ELEMENTS:
            raise SecurityError("The file contains content that cannot be processed safely. Please remove any scripts, forms, or other potentially unsafe elements and try again.")


def _check_dangerous_attributes(root: ET.Element):
    """Check for dangerous XML attributes."""
    for elem in root.iter():
        for attr_name in elem.attrib:
            if any(dangerous in attr_name.lower() for dangerous in DANGEROUS_ATTRIBUTES):
                raise SecurityError("The file contains attributes that cannot be processed safely. Please remove any event handlers or other potentially unsafe attributes and try again.")
