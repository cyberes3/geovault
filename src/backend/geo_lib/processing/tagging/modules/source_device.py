"""
Source device tag generator.
Generates source-device:* tags from GPX file creator attribute.
"""
import traceback
from typing import List, Optional, Union

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.security.exceptions import FileValidationError
from geo_lib.security.xml import parse_xml
from geo_lib.types.feature import GeoFeatureSupported

_logger = get_tagged_logger('SOURCEDEVICE')


class SourceDeviceTagGenerator(TagGenerator):
    """Generates source-device:* tags from GPX file creator attribute."""

    priority = 50  # Execute after track detection

    def __init__(self):
        super().__init__('source-device')

    def process(
            self,
            feature: GeoFeatureSupported,
            import_log=None,
            file_content: Optional[Union[str, bytes]] = None,
            **kwargs
    ) -> List[str]:
        """
        Generate source-device tag if GPX file has creator attribute.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            file_content: Optional file content (string or bytes) to parse for GPX creator
            **kwargs: Additional keyword arguments
            
        Returns:
            List containing source-device tag if creator found, empty list otherwise
        """
        tags = []

        if not file_content:
            return tags

        # Processor should normalize file_content and strip BOM
        assert isinstance(file_content, str)

        # Check if this looks like a GPX file
        if not (file_content.strip().startswith('<?xml') and '<gpx' in file_content.lower()):
            return tags

        try:
            root = parse_xml(file_content)

            # Check if root element is gpx (handle namespaces - tag might be '{namespace}gpx')
            tag_name = root.tag
            if tag_name.startswith('{'):
                # Has namespace, extract local name (everything after the closing brace)
                tag_name = tag_name.split('}')[-1] if '}' in tag_name else tag_name

            if tag_name.lower() != 'gpx':
                return tags

            # Extract creator attribute
            creator = root.get('creator')
            if creator and creator.strip():
                tags.append(f'source-device:{creator.strip()}')
        except FileValidationError:
            # Invalid XML, silently return empty list
            pass
        except Exception:
            # General error, log and ignore
            _logger.warning(f"Error extracting device from GPX: {traceback.format_exc()}")

        return tags
