import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from typing import Tuple

import markdownify

from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.types.geojson import GeojsonRawProperty


def html_to_markdown(html_content) -> str:
    """
    Convert HTML content to markdown format.
    
    Args:
        html_content: HTML string or dict with @type and value keys to convert
        
    Returns:
        Markdown formatted string
    """
    # Handle dictionary format from togeojson
    if isinstance(html_content, dict):
        if '@type' in html_content and html_content['@type'] == 'html' and 'value' in html_content:
            html_content = html_content['value']
        else:
            # If it's a dict but not the expected format, convert to string
            html_content = str(html_content)
    
    # Ensure we have a string
    if not isinstance(html_content, str):
        html_content = str(html_content)
    
    if not html_content or not html_content.strip():
        return ""

    # Convert HTML to markdown using markdownify
    markdown_content = markdownify.markdownify(
        html_content,
        heading_style="ATX",  # Use # for headings
        bullets="-",  # Use - for bullet points
        strip=['script', 'style']  # Remove script and style tags
    )

    # Clean up extra whitespace and newlines
    markdown_content = re.sub(r'\n\s*\n\s*\n', '\n\n', markdown_content)
    markdown_content = markdown_content.strip()

    return markdown_content


def normalize_kml_for_comparison(kml_content: str) -> str:
    """
    Normalize KML content for comparison by removing differences that don't affect the actual data.
    
    This function handles the differences between KML and KMZ files:
    1. Normalizes document names (removes .kml/.kmz extensions)
    2. Normalizes icon paths (converts both :/ and files/ paths to a standard format)
    3. Removes whitespace differences
    4. Standardizes XML formatting
    """
    if not kml_content:
        return ""

    # Parse the KML content with secure settings
    try:
        # Use secure parser to prevent XXE attacks
        parser = ET.XMLParser()
        
        # Disable entity processing to prevent XXE attacks
        # Note: In newer Python versions, parser.entity is readonly, so we use a different approach
        try:
            # Try to disable entity processing (works in older Python versions)
            parser.entity = {}
        except (AttributeError, TypeError):
            # In newer versions, we rely on the default secure behavior
            pass
            
        root = ET.fromstring(kml_content, parser=parser)
    except ET.ParseError:
        # If XML parsing fails, return the original content
        return kml_content

    # Normalize document name - remove .kml/.kmz extensions
    for name_elem in root.iter():
        if name_elem.tag.endswith('name') and name_elem.text:
            # Remove .kml or .kmz extensions from document names
            name_elem.text = re.sub(r'\.(kml|kmz)$', '', name_elem.text, flags=re.IGNORECASE)

    # Normalize icon paths - convert both :/ and files/ paths to a standard format
    for href_elem in root.iter():
        if href_elem.tag.endswith('href') and href_elem.text:
            href = href_elem.text
            # Convert :/ paths to standard format
            if href.startswith(':/'):
                href_elem.text = href[2:]  # Remove :/ prefix
            # Convert files/ paths to standard format  
            elif href.startswith('files/'):
                href_elem.text = href[6:]  # Remove files/ prefix

    # Convert back to string with consistent formatting
    try:
        # Use a consistent XML declaration and formatting
        normalized = ET.tostring(root, encoding='unicode', xml_declaration=True)
        # Normalize whitespace
        normalized = re.sub(r'\s+', ' ', normalized)
        normalized = re.sub(r'>\s+<', '><', normalized)
        return normalized.strip()
    except Exception:
        # If normalization fails, return the original content
        return kml_content


def hex_to_rgba(hex_color: str, opacity: float = 1.0) -> list:
    """Convert hex color string to RGBA array."""
    if not hex_color or not hex_color.startswith('#'):
        return [255, 0, 0, 1.0]  # Default red

    try:
        hex_color = hex_color.lstrip('#')
        if len(hex_color) == 6:
            red = int(hex_color[0:2], 16)
            green = int(hex_color[2:4], 16)
            blue = int(hex_color[4:6], 16)
            return [red, green, blue, opacity]
    except ValueError:
        pass

    return [255, 0, 0, 1.0]  # Default red


def preserve_togeojson_styling(properties: dict) -> dict:
    """Preserve togeojson styling and replace KML icons with red circles for points."""
    properties = properties.copy()

    # If this is a point feature with an icon, replace it with red circle styling
    if 'icon' in properties:
        # Remove the icon URL and add red circle styling
        del properties['icon']

        # Add red circle styling
        properties['marker-color'] = '#FF0000'
        properties['marker-size'] = 'medium'
        properties['marker-symbol'] = 'circle'

    # Convert HTML descriptions to markdown
    if 'description' in properties and properties['description']:
        properties['description'] = html_to_markdown(properties['description'])

    # Keep all other togeojson properties (stroke, fill, stroke-width, etc.)
    return properties


def split_geometry_collection(feature: dict) -> list:
    """Split GeometryCollection into separate features."""
    if feature['geometry']['type'] != 'GeometryCollection':
        return [feature]

    features = []
    geometries = feature['geometry']['geometries']

    # Prioritize polygons over other geometries
    polygon_geometries = [g for g in geometries if g['type'] == 'Polygon']
    other_geometries = [g for g in geometries if g['type'] in ['Point', 'LineString']]

    # Use polygons if available, otherwise use other geometries
    geometries_to_use = polygon_geometries if polygon_geometries else other_geometries

    for geom in geometries_to_use:
        new_feature = {
            'type': 'Feature',
            'geometry': geom,
            'properties': feature['properties'].copy()
        }
        features.append(new_feature)

    return features


def process_togeojson_features(features: list) -> Tuple[list, ImportLog]:
    """Process features from togeojson output."""
    processed_features = []
    import_log = ImportLog()
    
    for feature in features:
        # Split GeometryCollection into separate features
        split_features = split_geometry_collection(feature)

        for split_feature in split_features:
            if split_feature['geometry']['type'] in ['Point', 'LineString', 'Polygon']:
                try:
                    # Replace KML icons with red circles for points
                    split_feature['properties'] = preserve_togeojson_styling(split_feature['properties'])

                    # Convert to our property format
                    split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump()
                    processed_features.append(split_feature)
                except Exception as e:
                    import_log.add(f'Failed to process feature properties: {str(e)}, skipping feature', 'ToGeoJSON Process', DatabaseLogLevel.ERROR)
            else:
                import_log.add(f'Unsupported geometry type: {split_feature["geometry"]["type"]}, skipping', 'ToGeoJSON Process', DatabaseLogLevel.WARNING)

    return processed_features, import_log


def kml_to_geojson(kml_bytes, timeout_seconds: int = None) -> Tuple[dict, ImportLog]:
    """Convert KML/KMZ to GeoJSON using JavaScript togeojson library for proper styling support.
    
    Args:
        kml_bytes: KML/KMZ file content as bytes or string
        timeout_seconds: Timeout in seconds for the conversion process. If None, calculated based on file size.
    """
    import_log = ImportLog()

    # Calculate dynamic timeout based on file size if not specified
    if timeout_seconds is None:
        file_size = len(kml_bytes) if isinstance(kml_bytes, bytes) else len(kml_bytes.encode('utf-8'))
        file_size_mb = file_size / (1024 * 1024)
        
        # Base timeout of 30 seconds, plus 2 seconds per MB for large files
        # This gives us: 30s for small files, 60s for 15MB, 120s for 45MB, 240s for 105MB
        timeout_seconds = max(30, int(30 + (file_size_mb * 2)))
        
        import_log.add(f'Calculated timeout: {timeout_seconds}s for {file_size_mb:.1f}MB file', 'Processing', DatabaseLogLevel.INFO)

    try:
        # Get the path to the togeojson converter
        current_dir = os.path.dirname(os.path.abspath(__file__))
        # togeojson is in the parent processing directory, not in the kml subdirectory
        processing_dir = os.path.dirname(current_dir)
        togeojson_path = os.path.join(processing_dir, 'togeojson', 'index.js')

        # Handle KMZ files (ZIP archives)
        if isinstance(kml_bytes, bytes) and kml_bytes.startswith(b'PK'):
            # It's a KMZ file - write to temporary file for JavaScript converter
            with tempfile.NamedTemporaryFile(suffix='.kmz', delete=False) as temp_file:
                temp_file.write(kml_bytes)
                temp_file_path = temp_file.name

            try:
                # Use the JavaScript converter with file path
                result = subprocess.run(
                    ['node', togeojson_path, temp_file_path],
                    capture_output=True,
                    text=True,
                    timeout=timeout_seconds
                )

                if result.returncode != 0:
                    raise Exception(f"JavaScript converter failed: {result.stderr}")

                geojson_data = json.loads(result.stdout)

            finally:
                # Clean up temporary file
                os.unlink(temp_file_path)
        else:
            # It's a regular KML file
            if isinstance(kml_bytes, str):
                kml_content = kml_bytes
            else:
                kml_content = kml_bytes.decode('utf-8')

            # Remove namespaces from KML content to make it compatible with togeojson
            # The togeojson library doesn't handle namespaced KML well
            import re
            # Remove namespace declarations
            kml_content = re.sub(r'xmlns:ns\d+="[^"]*"', '', kml_content)
            # Remove namespace prefixes from tags
            kml_content = re.sub(r'ns\d+:', '', kml_content)
            
            # Write KML content to temporary file to avoid stdin issues
            with tempfile.NamedTemporaryFile(mode='w', suffix='.kml', delete=False, encoding='utf-8') as temp_file:
                temp_file.write(kml_content)
                temp_file_path = temp_file.name

            try:
                # Use the JavaScript converter with file path
                result = subprocess.run(
                    ['node', togeojson_path, temp_file_path],
                    capture_output=True,
                    text=True,
                    timeout=timeout_seconds
                )
            finally:
                # Clean up temporary file
                os.unlink(temp_file_path)

            if result.returncode != 0:
                raise Exception(f"JavaScript converter failed: {result.stderr}")

            geojson_data = json.loads(result.stdout)

        # Process the features using the existing processing logic
        processed_features, processing_log = process_togeojson_features(geojson_data['features'])
        import_log.extend(processing_log)

        # Create the final GeoJSON structure
        final_geojson = {
            'type': 'FeatureCollection',
            'features': processed_features
        }

        return final_geojson, import_log

    except Exception as e:
        import_log.add(f"KML conversion failed: {str(e)}", 'KML to GeoJSON', DatabaseLogLevel.ERROR)
        raise


def load_geojson_type(data: dict) -> dict:
    """Convert the processed data to the expected format."""
    # The data is already in the correct format from our processing
    return data
