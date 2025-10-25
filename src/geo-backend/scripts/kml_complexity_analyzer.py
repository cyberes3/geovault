#!/usr/bin/env python3
"""
KML Complexity Analyzer

This script analyzes KML files and generates a complexity score from 0 to 1.
The score is based on:
- Number of point features
- Number of polygon and line features  
- Number of vertices in polygon and line features

Usage:
    python kml_complexity_analyzer.py <kml_or_kmz_file_path>
    python kml_complexity_analyzer.py --help
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from typing import Dict, List, Tuple, Union
import math
import zipfile
import os
import tempfile


class KMLComplexityAnalyzer:
    """Analyzes KML files and calculates complexity scores."""
    
    def __init__(self):
        # Namespace mapping for KML elements
        self.namespaces = {
            'kml': 'http://www.opengis.net/kml/2.2',
            'gx': 'http://www.google.com/kml/ext/2.2'
        }
    
    def extract_kml_from_kmz(self, kmz_path: str) -> str:
        """
        Extract KML content from a KMZ file.
        
        Args:
            kmz_path: Path to KMZ file
            
        Returns:
            KML content as string
            
        Raises:
            ValueError: If KMZ file cannot be read or doesn't contain KML
        """
        try:
            with zipfile.ZipFile(kmz_path, 'r') as kmz_file:
                # Look for KML files in the archive
                kml_files = [f for f in kmz_file.namelist() if f.lower().endswith('.kml')]
                
                if not kml_files:
                    raise ValueError("No KML files found in KMZ archive")
                
                # Use the first KML file found (usually the main one)
                main_kml = kml_files[0]
                
                # Read the KML content
                with kmz_file.open(main_kml) as kml_file:
                    kml_content = kml_file.read()
                    
                    # Decode if it's bytes
                    if isinstance(kml_content, bytes):
                        try:
                            kml_content = kml_content.decode('utf-8')
                        except UnicodeDecodeError:
                            # Try with different encodings
                            try:
                                kml_content = kml_content.decode('latin-1')
                            except UnicodeDecodeError:
                                kml_content = kml_content.decode('utf-8', errors='ignore')
                
                return kml_content
                
        except zipfile.BadZipFile:
            raise ValueError("Invalid KMZ file: not a valid ZIP archive")
        except Exception as e:
            raise ValueError(f"Error reading KMZ file: {e}")
    
    def get_file_content(self, file_path: str) -> str:
        """
        Get KML content from either a KML or KMZ file.
        
        Args:
            file_path: Path to KML or KMZ file
            
        Returns:
            KML content as string
        """
        file_ext = os.path.splitext(file_path)[1].lower()
        
        if file_ext == '.kmz':
            return self.extract_kml_from_kmz(file_path)
        elif file_ext == '.kml':
            with open(file_path, 'r', encoding='utf-8') as f:
                return f.read()
        else:
            raise ValueError(f"Unsupported file type: {file_ext}. Expected .kml or .kmz")
    
    def parse_coordinates(self, coord_text: str) -> List[Tuple[float, float, float]]:
        """
        Parse coordinate string into list of (lon, lat, alt) tuples.
        
        Args:
            coord_text: Coordinate string from KML
            
        Returns:
            List of coordinate tuples
        """
        if not coord_text or not coord_text.strip():
            return []
        
        coordinates = []
        # Split by whitespace and newlines, then process each coordinate
        coord_parts = coord_text.replace('\n', ' ').split()
        
        for coord_part in coord_parts:
            coord_part = coord_part.strip()
            if not coord_part:
                continue
                
            # Split by comma and extract lon, lat, alt
            parts = coord_part.split(',')
            if len(parts) >= 2:
                try:
                    lon = float(parts[0])
                    lat = float(parts[1])
                    alt = float(parts[2]) if len(parts) > 2 else 0.0
                    coordinates.append((lon, lat, alt))
                except ValueError:
                    # Skip invalid coordinates
                    continue
        
        return coordinates
    
    def count_vertices_in_coordinates(self, coord_text: str) -> int:
        """
        Count the number of vertices in a coordinate string.
        
        Args:
            coord_text: Coordinate string from KML
            
        Returns:
            Number of vertices
        """
        if not coord_text or not coord_text.strip():
            return 0
        
        # Count coordinate triplets by counting commas and dividing by 2
        # Each coordinate has format "lon,lat,alt" so 2 commas per coordinate
        coord_parts = coord_text.replace('\n', ' ').split()
        vertex_count = 0
        
        for coord_part in coord_parts:
            coord_part = coord_part.strip()
            if not coord_part:
                continue
            # Count commas to determine number of coordinates
            comma_count = coord_part.count(',')
            if comma_count >= 2:  # At least lon,lat,alt
                vertex_count += 1
        
        return vertex_count
    
    def analyze_placemark(self, placemark: ET.Element) -> Dict[str, int]:
        """
        Analyze a single placemark element.
        
        Args:
            placemark: Placemark XML element
            
        Returns:
            Dictionary with feature counts and vertex counts
        """
        result = {
            'points': 0,
            'lines': 0,
            'polygons': 0,
            'point_vertices': 0,
            'line_vertices': 0,
            'polygon_vertices': 0
        }
        
        # Check for Point elements
        points = placemark.findall('.//kml:Point', self.namespaces)
        for point in points:
            coord_elem = point.find('kml:coordinates', self.namespaces)
            if coord_elem is not None and coord_elem.text:
                result['points'] += 1
                result['point_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
        
        # Check for LineString elements
        linestrings = placemark.findall('.//kml:LineString', self.namespaces)
        for linestring in linestrings:
            coord_elem = linestring.find('kml:coordinates', self.namespaces)
            if coord_elem is not None and coord_elem.text:
                result['lines'] += 1
                result['line_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
        
        # Check for Polygon elements
        polygons = placemark.findall('.//kml:Polygon', self.namespaces)
        for polygon in polygons:
            # Count outer boundary vertices
            outer_boundary = polygon.find('kml:outerBoundaryIs/kml:LinearRing', self.namespaces)
            if outer_boundary is not None:
                coord_elem = outer_boundary.find('kml:coordinates', self.namespaces)
                if coord_elem is not None and coord_elem.text:
                    result['polygons'] += 1
                    result['polygon_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
            
            # Count inner boundary vertices (holes)
            inner_boundaries = polygon.findall('kml:innerBoundaryIs/kml:LinearRing', self.namespaces)
            for inner_boundary in inner_boundaries:
                coord_elem = inner_boundary.find('kml:coordinates', self.namespaces)
                if coord_elem is not None and coord_elem.text:
                    result['polygon_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
        
        # Check for MultiGeometry elements
        multigeometries = placemark.findall('.//kml:MultiGeometry', self.namespaces)
        for multigeom in multigeometries:
            # Count points in MultiGeometry
            points = multigeom.findall('kml:Point', self.namespaces)
            for point in points:
                coord_elem = point.find('kml:coordinates', self.namespaces)
                if coord_elem is not None and coord_elem.text:
                    result['points'] += 1
                    result['point_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
            
            # Count linestrings in MultiGeometry
            linestrings = multigeom.findall('kml:LineString', self.namespaces)
            for linestring in linestrings:
                coord_elem = linestring.find('kml:coordinates', self.namespaces)
                if coord_elem is not None and coord_elem.text:
                    result['lines'] += 1
                    result['line_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
            
            # Count polygons in MultiGeometry
            polygons = multigeom.findall('kml:Polygon', self.namespaces)
            for polygon in polygons:
                # Count outer boundary vertices
                outer_boundary = polygon.find('kml:outerBoundaryIs/kml:LinearRing', self.namespaces)
                if outer_boundary is not None:
                    coord_elem = outer_boundary.find('kml:coordinates', self.namespaces)
                    if coord_elem is not None and coord_elem.text:
                        result['polygons'] += 1
                        result['polygon_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
                
                # Count inner boundary vertices (holes)
                inner_boundaries = polygon.findall('kml:innerBoundaryIs/kml:LinearRing', self.namespaces)
                for inner_boundary in inner_boundaries:
                    coord_elem = inner_boundary.find('kml:coordinates', self.namespaces)
                    if coord_elem is not None and coord_elem.text:
                        result['polygon_vertices'] += self.count_vertices_in_coordinates(coord_elem.text)
        
        return result
    
    def analyze_kml_file(self, file_path: str) -> Dict[str, Union[int, float]]:
        """
        Analyze a KML or KMZ file and return complexity metrics.
        
        Args:
            file_path: Path to KML or KMZ file
            
        Returns:
            Dictionary with analysis results
        """
        try:
            # Get KML content (extract from KMZ if needed)
            kml_content = self.get_file_content(file_path)
            
            # Parse the XML content
            root = ET.fromstring(kml_content)
            
            # Initialize counters
            total_metrics = {
                'points': 0,
                'lines': 0,
                'polygons': 0,
                'point_vertices': 0,
                'line_vertices': 0,
                'polygon_vertices': 0
            }
            
            # Find all placemarks
            placemarks = root.findall('.//kml:Placemark', self.namespaces)
            
            for placemark in placemarks:
                metrics = self.analyze_placemark(placemark)
                for key in total_metrics:
                    total_metrics[key] += metrics[key]
            
            # Calculate complexity score
            complexity_score = self.calculate_complexity_score(total_metrics)
            
            # Add complexity score to results
            result = total_metrics.copy()
            result['complexity_score'] = complexity_score
            result['total_features'] = total_metrics['points'] + total_metrics['lines'] + total_metrics['polygons']
            result['total_vertices'] = total_metrics['point_vertices'] + total_metrics['line_vertices'] + total_metrics['polygon_vertices']
            
            return result
            
        except ET.ParseError as e:
            raise ValueError(f"Failed to parse KML content: {e}")
        except FileNotFoundError:
            raise ValueError(f"File not found: {file_path}")
        except Exception as e:
            raise ValueError(f"Error analyzing file: {e}")
    
    def calculate_complexity_score(self, metrics: Dict[str, int]) -> float:
        """
        Calculate complexity score from 0 to 1 based on feature counts and vertex density.
        Uses logarithmic scaling to better differentiate between files of varying complexity.
        
        Args:
            metrics: Dictionary with feature and vertex counts
            
        Returns:
            Complexity score between 0 and 1
        """
        # Extract metrics
        points = metrics['points']
        lines = metrics['lines']
        polygons = metrics['polygons']
        point_vertices = metrics['point_vertices']
        line_vertices = metrics['line_vertices']
        polygon_vertices = metrics['polygon_vertices']
        
        total_features = points + lines + polygons
        total_vertices = point_vertices + line_vertices + polygon_vertices
        
        # Use logarithmic scaling for better differentiation
        # Feature complexity (0-0.25) - logarithmic scale
        if total_features == 0:
            feature_score = 0.0
        else:
            # Log scale: 0.25 at ~10k features, 0.125 at ~1k features
            feature_score = min(0.25 * (math.log10(total_features + 1) / 4.0), 0.25)
        
        # Vertex complexity (0-0.6) - logarithmic scale with higher ceiling
        if total_vertices == 0:
            vertex_score = 0.0
        else:
            # Log scale: 0.6 at ~10M vertices, 0.3 at ~1M vertices, 0.15 at ~100k vertices
            vertex_score = min(0.6 * (math.log10(total_vertices + 1) / 7.0), 0.6)
        
        # Geometric complexity (0-0.15) - based on feature type distribution
        # Higher weight for polygons and lines (more complex than points)
        if total_features > 0:
            # Calculate weighted complexity based on feature types
            weighted_features = (polygons * 3.0 + lines * 2.0 + points * 1.0)
            # Use log scale for geometric complexity too
            if weighted_features > 0:
                geometric_score = min(0.15 * (math.log10(weighted_features + 1) / 3.0), 0.15)
            else:
                geometric_score = 0.0
        else:
            geometric_score = 0.0
        
        # Vertex density (0-0.1) - average vertices per feature
        if total_features > 0:
            avg_vertices_per_feature = total_vertices / total_features
            # Log scale for density: 0.1 at ~1000 vertices per feature
            if avg_vertices_per_feature > 0:
                density_score = min(0.1 * (math.log10(avg_vertices_per_feature + 1) / 3.0), 0.1)
            else:
                density_score = 0.0
        else:
            density_score = 0.0
        
        # Combine all scores
        total_score = feature_score + vertex_score + geometric_score + density_score
        
        # Ensure score is between 0 and 1
        return min(max(total_score, 0.0), 1.0)


def main():
    """Main function to run the KML complexity analyzer."""
    parser = argparse.ArgumentParser(
        description='Analyze KML or KMZ file complexity and generate a score from 0 to 1',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python kml_complexity_analyzer.py test.kml
  python kml_complexity_analyzer.py test.kmz
  python kml_complexity_analyzer.py /path/to/complex.kml
        """
    )
    
    parser.add_argument(
        'kml_file',
        help='Path to the KML or KMZ file to analyze'
    )
    
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Show detailed analysis results'
    )
    
    args = parser.parse_args()
    
    try:
        analyzer = KMLComplexityAnalyzer()
        results = analyzer.analyze_kml_file(args.kml_file)
        
        # Print results
        print(f"KML Complexity Analysis: {args.kml_file}")
        print("=" * 50)
        
        if args.verbose:
            print(f"Point features: {results['points']}")
            print(f"Line features: {results['lines']}")
            print(f"Polygon features: {results['polygons']}")
            print(f"Total features: {results['total_features']}")
            print()
            print(f"Point vertices: {results['point_vertices']}")
            print(f"Line vertices: {results['line_vertices']}")
            print(f"Polygon vertices: {results['polygon_vertices']}")
            print(f"Total vertices: {results['total_vertices']}")
            print()
        
        print(f"Complexity Score: {results['complexity_score']:.4f}")
        
        # Provide interpretation
        score = results['complexity_score']
        if score < 0.2:
            interpretation = "Very Simple"
        elif score < 0.4:
            interpretation = "Simple"
        elif score < 0.6:
            interpretation = "Moderate"
        elif score < 0.8:
            interpretation = "Complex"
        else:
            interpretation = "Very Complex"
        
        print(f"Interpretation: {interpretation}")
        
    except ValueError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nAnalysis interrupted by user.", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
