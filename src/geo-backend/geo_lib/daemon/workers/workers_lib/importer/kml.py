import io
import json
import subprocess
import tempfile
import zipfile
import re
from typing import Union, Tuple, Dict, Optional
from pathlib import Path

from geo_lib.daemon.workers.workers_lib.importer.logging import ImportLog
from geo_lib.types.geojson import GeojsonRawProperty

# Import fastkml geometry classes at module level
from fastkml.geometry import Point, LineString, Polygon, GeometryCollection, MultiPoint, MultiLineString, MultiPolygon


def kmz_to_kml(kml_bytes: Union[str, bytes]) -> str:
    """Convert KMZ (zip) to KML string."""
    if isinstance(kml_bytes, str):
        kml_bytes = kml_bytes.encode('utf-8')
    try:
        # Try to open as a zipfile (KMZ)
        with zipfile.ZipFile(io.BytesIO(kml_bytes), 'r') as kmz:
            # Look for doc.kml first (standard KMZ structure), then any .kml file
            kml_files = [name for name in kmz.namelist() if name.endswith('.kml')]
            if not kml_files:
                raise Exception("No KML file found in KMZ archive")
            
            # Prefer doc.kml if it exists, otherwise use the first .kml file
            kml_file = 'doc.kml' if 'doc.kml' in kml_files else kml_files[0]
            return kmz.read(kml_file).decode('utf-8')
    except zipfile.BadZipFile:
        # If not a zipfile, assume it's a KML file
        return kml_bytes.decode('utf-8')


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
    
    # Parse the KML content
    try:
        import xml.etree.ElementTree as ET
        root = ET.fromstring(kml_content)
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
    # If this is a point feature with an icon, replace it with red circle styling
    if 'icon' in properties:
        # Remove the icon URL and add red circle styling
        properties = properties.copy()
        del properties['icon']
        
        # Add red circle styling
        properties['marker-color'] = '#FF0000'
        properties['marker-size'] = 'medium'
        properties['marker-symbol'] = 'circle'
    
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
                # Replace KML icons with red circles for points
                split_feature['properties'] = preserve_togeojson_styling(split_feature['properties'])
                
                # Convert to our property format
                split_feature['properties'] = GeojsonRawProperty(**split_feature['properties']).model_dump()
                processed_features.append(split_feature)
            else:
                import_log.add(f'Unsupported geometry type: {split_feature["geometry"]["type"]}, skipping')
    
    return processed_features, import_log


def kml_to_geojson(kml_bytes) -> Tuple[dict, ImportLog]:
    """Convert KML/KMZ to GeoJSON using pure Python fastkml library."""
    import zipfile
    from fastkml import kml
    import geojson
    
    import_log = ImportLog()
    
    try:
        # Handle KMZ files (ZIP archives)
        if isinstance(kml_bytes, bytes) and kml_bytes.startswith(b'PK'):
            # It's a KMZ file
            with zipfile.ZipFile(io.BytesIO(kml_bytes)) as kmz:
                kml_files = [f for f in kmz.namelist() if f.endswith('.kml')]
                if not kml_files:
                    raise Exception("No KML file found in KMZ archive")
                
                # Use the first KML file
                kml_content = kmz.read(kml_files[0]).decode('utf-8')
        else:
            # It's a regular KML file
            if isinstance(kml_bytes, str):
                kml_content = kml_bytes
            else:
                kml_content = kml_bytes.decode('utf-8')
        
        # Parse KML
        k = kml.KML()
        # Convert to bytes if there's an XML declaration, otherwise use string
        if kml_content.strip().startswith('<?xml'):
            k.from_string(kml_content.encode('utf-8'))
        else:
            k.from_string(kml_content)
        
        features = []
        
        # Extract features from KML
        for document in k.features():
            features.extend(_extract_features_from_kml_feature(document))
        
        # Process the features
        processed_features, processing_log = process_togeojson_features(features)
        import_log.extend(processing_log)
        
        # Create the final GeoJSON structure
        final_geojson = {
            'type': 'FeatureCollection',
            'features': processed_features
        }
        
        return final_geojson, import_log
        
    except Exception as e:
        import_log.add(f"KML conversion failed: {str(e)}")
        raise


def _extract_features_from_kml_feature(kml_feature):
    """Extract GeoJSON features from a KML feature."""
    features = []
    
    # If this feature has sub-features (like a Folder), process them recursively
    if hasattr(kml_feature, 'features'):
        for sub_feature in kml_feature.features():
            features.extend(_extract_features_from_kml_feature(sub_feature))
    
    # If this feature has geometry, process it
    if hasattr(kml_feature, 'geometry') and kml_feature.geometry:
        geometry = kml_feature.geometry
        
        # Convert KML geometry to GeoJSON
        if isinstance(geometry, Point):
            # Point coordinates are accessed as a tuple (x, y, z)
            coords = geometry.coords[0] if geometry.coords else (0, 0, 0)
            geojson_geom = {
                'type': 'Point',
                'coordinates': [coords[0], coords[1], coords[2] if len(coords) > 2 else 0]
            }
        elif isinstance(geometry, LineString):
            # LineString coordinates are accessed as a list of tuples
            coords = [[coord[0], coord[1], coord[2] if len(coord) > 2 else 0] for coord in geometry.coords]
            geojson_geom = {
                'type': 'LineString',
                'coordinates': coords
            }
        elif isinstance(geometry, Polygon):
            # Polygon coordinates are accessed through exterior and interior rings
            coords = []
            for ring in geometry.exterior.coords:
                coords.append([ring[0], ring[1], ring[2] if len(ring) > 2 else 0])
            geojson_geom = {
                'type': 'Polygon',
                'coordinates': [coords]
            }
        elif isinstance(geometry, (GeometryCollection, MultiPoint, MultiLineString, MultiPolygon)):
            # Handle multi-geometry types by creating separate features
            if isinstance(geometry, GeometryCollection):
                geoms = geometry.geoms
            else:
                # MultiPoint, MultiLineString, MultiPolygon have different attribute names
                geoms = geometry.geoms if hasattr(geometry, 'geoms') else [geometry]
            
            for geom in geoms:
                sub_features = _extract_features_from_kml_feature(type('Feature', (), {'geometry': geom, 'name': kml_feature.name, 'description': getattr(kml_feature, 'description', '')})())
                features.extend(sub_features)
            return features
        else:
            return features  # Skip unsupported geometry types
        
        # Create properties from KML feature
        properties = {}
        if hasattr(kml_feature, 'name') and kml_feature.name:
            properties['name'] = kml_feature.name
        if hasattr(kml_feature, 'description') and kml_feature.description:
            properties['description'] = kml_feature.description
        
        # Add styling information
        if hasattr(kml_feature, 'styleUrl') and kml_feature.styleUrl:
            properties['styleUrl'] = kml_feature.styleUrl
        
        # Create GeoJSON feature
        feature = {
            'type': 'Feature',
            'geometry': geojson_geom,
            'properties': properties
        }
        
        features.append(feature)
    
    return features


def load_geojson_type(data: dict) -> dict:
    """Convert the processed data to the expected format."""
    # The data is already in the correct format from our processing
    return data
