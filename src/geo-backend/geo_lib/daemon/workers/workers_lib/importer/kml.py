import io
import json
import subprocess
import tempfile
import zipfile
from typing import Union, Tuple, Dict, Optional
from pathlib import Path

from geo_lib.daemon.workers.workers_lib.importer.logging import ImportLog
from geo_lib.types.geojson import GeojsonRawProperty


def kmz_to_kml(kml_bytes: Union[str, bytes]) -> str:
    """Convert KMZ (zip) to KML string."""
    if isinstance(kml_bytes, str):
        kml_bytes = kml_bytes.encode('utf-8')
    try:
        # Try to open as a zipfile (KMZ)
        with zipfile.ZipFile(io.BytesIO(kml_bytes), 'r') as kmz:
            # Find the first .kml file in the zipfile
            kml_file = [name for name in kmz.namelist() if name.endswith('.kml')][0]
            return kmz.read(kml_file).decode('utf-8')
    except zipfile.BadZipFile:
        # If not a zipfile, assume it's a KML file
        return kml_bytes.decode('utf-8')


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
    """Convert KML/KMZ to GeoJSON using togeojson library via Node.js."""
    # Get the path to the togeojson converter
    togeojson_path = Path(__file__).parent / 'togeojson'
    converter_script = togeojson_path / 'index.js'
    
    if not converter_script.exists():
        raise Exception(f"KML converter not found at {converter_script}. Run install.sh first.")
    
    # Create a temporary file for the KML/KMZ content
    with tempfile.NamedTemporaryFile(mode='wb', suffix='.kml', delete=False) as temp_file:
        if isinstance(kml_bytes, str):
            temp_file.write(kml_bytes.encode('utf-8'))
        else:
            temp_file.write(kml_bytes)
        temp_file_path = temp_file.name
    
    try:
        # Run the Node.js converter
        result = subprocess.run(
            ['node', str(converter_script), temp_file_path],
            capture_output=True,
            text=True,
            cwd=str(togeojson_path)
        )
        
        if result.returncode != 0:
            raise Exception(f"KML conversion failed: {result.stderr}")
        
        # Parse the GeoJSON output
        geojson_data = json.loads(result.stdout)
        
        # Process the features
        processed_features, import_log = process_togeojson_features(geojson_data['features'])
        
        # Create the final GeoJSON structure
        final_geojson = {
            'type': 'FeatureCollection',
            'features': processed_features
        }
        
        return final_geojson, import_log
        
    finally:
        # Clean up the temporary file
        Path(temp_file_path).unlink(missing_ok=True)


def load_geojson_type(data: dict) -> dict:
    """Convert the processed data to the expected format."""
    # The data is already in the correct format from our processing
    return data
