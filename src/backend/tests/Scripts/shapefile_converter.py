#!/usr/bin/env python3
"""
Shapefile to KML/GPX Converter

This script converts shapefiles to KML and GPX formats.
It supports batch processing of multiple shapefiles and provides
options for coordinate system transformation and styling.

Usage:
    python shapefile_converter.py <input_path> [options]
    
Examples:
    # Convert single shapefile to both KML and GPX
    python shapefile_converter.py /path/to/shapefile.shp
    
    # Convert all shapefiles in a directory
    python shapefile_converter.py /path/to/shapefiles/ --batch
    
    # Convert with specific output directory
    python shapefile_converter.py /path/to/shapefile.shp --output-dir /path/to/output/
    
    # Convert only to KML
    python shapefile_converter.py /path/to/shapefile.shp --format kml
    
    # Convert with custom styling
    python shapefile_converter.py /path/to/shapefile.shp --style-color red --style-width 2
"""

import os
import sys
import argparse
import zipfile
import tempfile
import shutil
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
import json
import logging

try:
    import fiona
    import shapely
    from shapely.geometry import mapping, shape
    from shapely.ops import transform
    import pyproj
    from pyproj import Transformer
    from lxml import etree
    import geojson
except ImportError as e:
    print(f"Error: Missing required dependencies. Please install them with:")
    print(f"pip install fiona shapely pyproj lxml geojson")
    print(f"Missing: {e}")
    sys.exit(1)

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class ShapefileConverter:
    """Main class for converting shapefiles to KML and GPX formats."""
    
    def __init__(self, output_dir: Optional[str] = None, target_crs: str = "EPSG:4326"):
        """
        Initialize the converter.
        
        Args:
            output_dir: Directory to save output files (default: same as input)
            target_crs: Target coordinate reference system (default: WGS84)
        """
        self.output_dir = output_dir
        self.target_crs = target_crs
        self.transformer = None
        
    def _setup_transformer(self, source_crs: str) -> None:
        """Setup coordinate transformation if needed."""
        if source_crs != self.target_crs:
            try:
                self.transformer = Transformer.from_crs(source_crs, self.target_crs, always_xy=True)
                logger.info(f"Coordinate transformation setup: {source_crs} -> {self.target_crs}")
            except Exception as e:
                logger.warning(f"Failed to setup coordinate transformation: {e}")
                self.transformer = None
    
    def _transform_geometry(self, geometry: Dict[str, Any]) -> Dict[str, Any]:
        """Transform geometry coordinates if transformer is available."""
        if not self.transformer:
            return geometry
            
        try:
            # Convert to shapely geometry
            geom = shape(geometry)
            
            # Transform coordinates
            def transform_coords(x, y, z=None):
                new_x, new_y = self.transformer.transform(x, y)
                return (new_x, new_y, z) if z is not None else (new_x, new_y)
            
            # Apply transformation
            transformed_geom = transform(transform_coords, geom)
            
            # Convert back to GeoJSON
            return mapping(transformed_geom)
        except Exception as e:
            logger.warning(f"Failed to transform geometry: {e}")
            return geometry
    
    def shapefile_to_geojson(self, shapefile_path: str) -> Dict[str, Any]:
        """
        Convert shapefile to GeoJSON format.
        
        Args:
            shapefile_path: Path to the shapefile (.shp)
            
        Returns:
            GeoJSON FeatureCollection
        """
        logger.info(f"Converting shapefile to GeoJSON: {shapefile_path}")
        
        features = []
        
        try:
            with fiona.open(shapefile_path) as src:
                # Setup coordinate transformation
                logger.debug(f"CRS type: {type(src.crs)}")
                logger.debug(f"CRS value: {src.crs}")
                
                if src.crs:
                    try:
                        # Try to get the CRS as a string
                        if hasattr(src.crs, 'to_string'):
                            crs_string = src.crs.to_string()
                        elif hasattr(src.crs, 'to_wkt'):
                            crs_string = src.crs.to_wkt()
                        else:
                            crs_string = str(src.crs)
                        
                        # Extract EPSG code if possible
                        if 'EPSG:' in crs_string:
                            import re
                            epsg_match = re.search(r'EPSG:(\d+)', crs_string)
                            if epsg_match:
                                source_crs = f"EPSG:{epsg_match.group(1)}"
                            else:
                                source_crs = 'EPSG:4326'
                        else:
                            # Try to detect common projections from the CRS string
                            if 'NAD83' in crs_string and 'Washington' in crs_string:
                                # This is likely Washington State Plane South (EPSG:32148)
                                source_crs = 'EPSG:32148'
                            elif 'NAD83' in crs_string and 'Oregon' in crs_string:
                                # This is likely Oregon State Plane (EPSG:32126)
                                source_crs = 'EPSG:32126'
                            else:
                                source_crs = 'EPSG:4326'
                    except Exception as e:
                        logger.warning(f"Failed to parse CRS: {e}")
                        source_crs = 'EPSG:4326'
                else:
                    source_crs = 'EPSG:4326'
                
                self._setup_transformer(source_crs)
                
                logger.info(f"Source CRS: {source_crs}")
                logger.info(f"Feature count: {len(src)}")
                
                for feature in src:
                    # Transform geometry if needed
                    geometry = self._transform_geometry(feature['geometry'])
                    
                    # Create GeoJSON feature
                    geojson_feature = {
                        'type': 'Feature',
                        'geometry': geometry,
                        'properties': feature['properties']
                    }
                    
                    features.append(geojson_feature)
                
                return {
                    'type': 'FeatureCollection',
                    'features': features
                }
                
        except Exception as e:
            logger.error(f"Failed to convert shapefile to GeoJSON: {e}")
            raise
    
    def geojson_to_kml(self, geojson_data: Dict[str, Any], output_path: str, 
                      style_color: str = "blue", style_width: int = 2) -> None:
        """
        Convert GeoJSON to KML format.
        
        Args:
            geojson_data: GeoJSON FeatureCollection
            output_path: Path for the output KML file
            style_color: Color for styling features
            style_width: Width for line features
        """
        logger.info(f"Converting GeoJSON to KML: {output_path}")
        
        # Create KML root element
        kml = etree.Element('kml', xmlns='http://www.opengis.net/kml/2.2')
        document = etree.SubElement(kml, 'Document')
        
        # Add document name
        name = etree.SubElement(document, 'name')
        name.text = Path(output_path).stem
        
        # Add style
        style = etree.SubElement(document, 'Style', id='defaultStyle')
        line_style = etree.SubElement(style, 'LineStyle')
        color = etree.SubElement(line_style, 'color')
        color.text = self._color_to_kml(style_color)
        width = etree.SubElement(line_style, 'width')
        width.text = str(style_width)
        
        poly_style = etree.SubElement(style, 'PolygonStyle')
        poly_color = etree.SubElement(poly_style, 'color')
        poly_color.text = self._color_to_kml(style_color, alpha=128)
        
        # Process features
        for feature in geojson_data.get('features', []):
            self._add_feature_to_kml(document, feature, style_color, style_width)
        
        # Write KML file
        tree = etree.ElementTree(kml)
        tree.write(output_path, pretty_print=True, xml_declaration=True, encoding='UTF-8')
        logger.info(f"KML file created: {output_path}")
    
    def _add_feature_to_kml(self, document: etree.Element, feature: Dict[str, Any], 
                           style_color: str, style_width: int) -> None:
        """Add a single feature to KML document."""
        geometry = feature.get('geometry', {})
        properties = feature.get('properties', {})
        
        if geometry.get('type') == 'Point':
            placemark = etree.SubElement(document, 'Placemark')
            
            # Add name from properties
            if 'name' in properties:
                name = etree.SubElement(placemark, 'name')
                name.text = str(properties['name'])
            
            # Add description
            if properties:
                description = etree.SubElement(placemark, 'description')
                desc_text = ', '.join([f"{k}: {v}" for k, v in properties.items()])
                description.text = desc_text
            
            # Add point geometry
            point = etree.SubElement(placemark, 'Point')
            coordinates = etree.SubElement(point, 'coordinates')
            coords = geometry['coordinates']
            coordinates.text = f"{coords[0]},{coords[1]},0"
            
        elif geometry.get('type') == 'LineString':
            placemark = etree.SubElement(document, 'Placemark')
            
            # Add name
            if 'name' in properties:
                name = etree.SubElement(placemark, 'name')
                name.text = str(properties['name'])
            
            # Add style reference
            style_url = etree.SubElement(placemark, 'styleUrl')
            style_url.text = '#defaultStyle'
            
            # Add line geometry
            line_string = etree.SubElement(placemark, 'LineString')
            coordinates = etree.SubElement(line_string, 'coordinates')
            coords_text = ' '.join([f"{coord[0]},{coord[1]},0" for coord in geometry['coordinates']])
            coordinates.text = coords_text
            
        elif geometry.get('type') == 'Polygon':
            placemark = etree.SubElement(document, 'Placemark')
            
            # Add name
            if 'name' in properties:
                name = etree.SubElement(placemark, 'name')
                name.text = str(properties['name'])
            
            # Add style reference
            style_url = etree.SubElement(placemark, 'styleUrl')
            style_url.text = '#defaultStyle'
            
            # Add polygon geometry
            polygon = etree.SubElement(placemark, 'Polygon')
            outer_boundary = etree.SubElement(polygon, 'outerBoundaryIs')
            linear_ring = etree.SubElement(outer_boundary, 'LinearRing')
            coordinates = etree.SubElement(linear_ring, 'coordinates')
            
            # Get outer ring coordinates
            outer_ring = geometry['coordinates'][0]
            coords_text = ' '.join([f"{coord[0]},{coord[1]},0" for coord in outer_ring])
            coordinates.text = coords_text
    
    def geojson_to_gpx(self, geojson_data: Dict[str, Any], output_path: str) -> None:
        """
        Convert GeoJSON to GPX format.
        
        Args:
            geojson_data: GeoJSON FeatureCollection
            output_path: Path for the output GPX file
        """
        logger.info(f"Converting GeoJSON to GPX: {output_path}")
        
        # Create GPX root element
        gpx = etree.Element('gpx', 
                          version='1.1',
                          creator='Shapefile Converter',
                          xmlns='http://www.topografix.com/GPX/1/1')
        
        # Process features
        for feature in geojson_data.get('features', []):
            self._add_feature_to_gpx(gpx, feature)
        
        # Write GPX file
        tree = etree.ElementTree(gpx)
        tree.write(output_path, pretty_print=True, xml_declaration=True, encoding='UTF-8')
        logger.info(f"GPX file created: {output_path}")
    
    def _add_feature_to_gpx(self, gpx: etree.Element, feature: Dict[str, Any]) -> None:
        """Add a single feature to GPX document."""
        geometry = feature.get('geometry', {})
        properties = feature.get('properties', {})
        
        if geometry.get('type') == 'Point':
            # Add waypoint
            wpt = etree.SubElement(gpx, 'wpt', 
                                 lat=str(geometry['coordinates'][1]),
                                 lon=str(geometry['coordinates'][0]))
            
            if 'name' in properties:
                name = etree.SubElement(wpt, 'name')
                name.text = str(properties['name'])
            
            if 'description' in properties:
                desc = etree.SubElement(wpt, 'desc')
                desc.text = str(properties['description'])
                
        elif geometry.get('type') == 'LineString':
            # Add track
            trk = etree.SubElement(gpx, 'trk')
            
            if 'name' in properties:
                name = etree.SubElement(trk, 'name')
                name.text = str(properties['name'])
            
            trkseg = etree.SubElement(trk, 'trkseg')
            
            for coord in geometry['coordinates']:
                trkpt = etree.SubElement(trkseg, 'trkpt',
                                       lat=str(coord[1]),
                                       lon=str(coord[0]))
    
    def _color_to_kml(self, color: str, alpha: int = 255) -> str:
        """Convert color name to KML AABBGGRR format."""
        color_map = {
            'red': 'ff0000ff',
            'green': 'ff00ff00',
            'blue': 'ffff0000',
            'yellow': 'ff00ffff',
            'orange': 'ff00a5ff',
            'purple': 'ff800080',
            'pink': 'ffc0cbff',
            'black': 'ff000000',
            'white': 'ffffffff',
            'gray': 'ff808080'
        }
        
        base_color = color_map.get(color.lower(), 'ffff0000')  # Default to blue
        return f"{alpha:02x}{base_color[2:]}"
    
    def convert_shapefile(self, input_path: str, output_formats: List[str] = None, 
                         style_color: str = "blue", style_width: int = 2) -> List[str]:
        """
        Convert a single shapefile to specified formats.
        
        Args:
            input_path: Path to the shapefile
            output_formats: List of output formats ('kml', 'gpx', 'geojson')
            style_color: Color for styling
            style_width: Width for line features
            
        Returns:
            List of created output file paths
        """
        if output_formats is None:
            output_formats = ['kml', 'gpx']
        
        input_path = Path(input_path)
        if not input_path.exists():
            raise FileNotFoundError(f"Shapefile not found: {input_path}")
        
        # Determine output directory
        if self.output_dir:
            output_dir = Path(self.output_dir)
            output_dir.mkdir(parents=True, exist_ok=True)
        else:
            output_dir = input_path.parent
        
        # Convert to GeoJSON first
        geojson_data = self.shapefile_to_geojson(str(input_path))
        
        # Create output files
        output_files = []
        base_name = input_path.stem
        
        # Save GeoJSON if requested
        if 'geojson' in output_formats:
            geojson_path = output_dir / f"{base_name}.geojson"
            with open(geojson_path, 'w') as f:
                json.dump(geojson_data, f, indent=2)
            output_files.append(str(geojson_path))
            logger.info(f"GeoJSON file created: {geojson_path}")
        
        # Convert to KML if requested
        if 'kml' in output_formats:
            kml_path = output_dir / f"{base_name}.kml"
            self.geojson_to_kml(geojson_data, str(kml_path), style_color, style_width)
            output_files.append(str(kml_path))
        
        # Convert to GPX if requested
        if 'gpx' in output_formats:
            gpx_path = output_dir / f"{base_name}.gpx"
            self.geojson_to_gpx(geojson_data, str(gpx_path))
            output_files.append(str(gpx_path))
        
        return output_files
    
    def convert_batch(self, input_dir: str, output_formats: List[str] = None,
                     style_color: str = "blue", style_width: int = 2) -> Dict[str, List[str]]:
        """
        Convert all shapefiles in a directory.
        
        Args:
            input_dir: Directory containing shapefiles
            output_formats: List of output formats
            style_color: Color for styling
            style_width: Width for line features
            
        Returns:
            Dictionary mapping input files to output files
        """
        input_dir = Path(input_dir)
        if not input_dir.exists():
            raise FileNotFoundError(f"Input directory not found: {input_dir}")
        
        # Find all shapefiles
        shapefiles = list(input_dir.glob("*.shp"))
        if not shapefiles:
            logger.warning(f"No shapefiles found in {input_dir}")
            return {}
        
        logger.info(f"Found {len(shapefiles)} shapefiles to convert")
        
        results = {}
        for shapefile in shapefiles:
            try:
                logger.info(f"Processing: {shapefile.name}")
                output_files = self.convert_shapefile(
                    str(shapefile), output_formats, style_color, style_width
                )
                results[str(shapefile)] = output_files
            except Exception as e:
                logger.error(f"Failed to convert {shapefile}: {e}")
                results[str(shapefile)] = []
        
        return results


def main():
    """Main function for command-line interface."""
    parser = argparse.ArgumentParser(
        description="Convert shapefiles to KML and GPX formats",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    
    parser.add_argument('input_path', help='Path to shapefile (.shp) or directory containing shapefiles')
    parser.add_argument('--batch', action='store_true', help='Process all shapefiles in input directory')
    parser.add_argument('--output-dir', '-o', help='Output directory (default: same as input)')
    parser.add_argument('--format', '-f', choices=['kml', 'gpx', 'geojson'], 
                       nargs='+', default=['kml', 'gpx'],
                       help='Output formats (default: kml gpx)')
    parser.add_argument('--style-color', choices=['red', 'green', 'blue', 'yellow', 'orange', 'purple', 'pink', 'black', 'white', 'gray'],
                       default='blue', help='Color for styling features (default: blue)')
    parser.add_argument('--style-width', type=int, default=2, help='Width for line features (default: 2)')
    parser.add_argument('--target-crs', default='EPSG:4326', help='Target coordinate reference system (default: EPSG:4326)')
    parser.add_argument('--verbose', '-v', action='store_true', help='Enable verbose logging')
    
    args = parser.parse_args()
    
    # Set logging level
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Create converter
    converter = ShapefileConverter(
        output_dir=args.output_dir,
        target_crs=args.target_crs
    )
    
    try:
        if args.batch:
            # Batch processing
            results = converter.convert_batch(
                args.input_path, 
                args.format, 
                args.style_color, 
                args.style_width
            )
            
            # Print summary
            total_inputs = len(results)
            successful = sum(1 for outputs in results.values() if outputs)
            total_outputs = sum(len(outputs) for outputs in results.values())
            
            print(f"\nBatch conversion completed:")
            print(f"  Input files: {total_inputs}")
            print(f"  Successful: {successful}")
            print(f"  Total output files: {total_outputs}")
            
            for input_file, output_files in results.items():
                if output_files:
                    print(f"  {Path(input_file).name} -> {len(output_files)} files")
                else:
                    print(f"  {Path(input_file).name} -> FAILED")
        
        else:
            # Single file processing
            output_files = converter.convert_shapefile(
                args.input_path,
                args.format,
                args.style_color,
                args.style_width
            )
            
            print(f"\nConversion completed:")
            print(f"  Input: {args.input_path}")
            print(f"  Output files: {len(output_files)}")
            for output_file in output_files:
                print(f"    {output_file}")
    
    except Exception as e:
        logger.error(f"Conversion failed: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()