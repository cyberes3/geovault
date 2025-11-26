#!/usr/bin/env python3
"""
ZeeMap Downloader - Python version

Downloads ZeeMap data to CSV or KML format.
Converts the TypeScript version to Python with command line interface.

Usage:
    python zeemap-downloader.py <map_id> [--format csv|kml]
    
Example:
    python zeemap-downloader.py 772301 --format kml
    python zeemap-downloader.py 772301 --format csv --output my_map.csv

Requirements:
    pip install requests beautifulsoup4
"""

import argparse
import csv
import json
import logging
import re
import sys
import time
import xml.etree.ElementTree as ET
from typing import Dict, List, Optional, Any
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup


class ZeeMapDownloader:
    """Downloads ZeeMap data and saves to CSV or KML format."""
    
    def __init__(self, output_file: str = "zeemap-output.csv", output_format: str = "csv", verbose: bool = False):
        """
        Initialize the ZeeMap downloader.
        
        Args:
            output_file: Path to output file
            output_format: Output format ('csv' or 'kml')
            verbose: Enable verbose logging
        """
        self.output_file = output_file
        self.output_format = output_format.lower()
        self.verbose = verbose
        
        # Setup logging
        logging.basicConfig(
            level=logging.INFO if verbose else logging.WARNING,
            format='%(asctime)s - %(levelname)s - %(message)s',
            handlers=[
                logging.StreamHandler(),
                logging.FileHandler('zeemap_downloader.log')
            ]
        )
        self.logger = logging.getLogger(__name__)
        
        # HTTP session with timeout
        self.session = requests.Session()
        self.session.timeout = 10
        
        # Initialize output file based on format
        if self.output_format == 'csv':
            self._init_csv_file()
        elif self.output_format == 'kml':
            self._init_kml_file()
    
    def _init_csv_file(self):
        """Initialize CSV file with headers if it doesn't exist."""
        try:
            with open(self.output_file, 'x', newline='', encoding='utf-8') as f:
                writer = csv.writer(f)
                writer.writerow([
                    'GROUP', 'MAP_TITLE', 'EMAIL', 'DESCRIPTION',
                    'M_NAME', 'M_ADDRESS', 'M_COUNTRY', 'M_LONG', 'M_LAT'
                ])
        except FileExistsError:
            # File already exists, append mode
            pass
    
    def _init_kml_file(self):
        """Initialize KML file with basic structure if it doesn't exist."""
        try:
            with open(self.output_file, 'x', encoding='utf-8') as f:
                f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
                f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
                f.write('  <Document>\n')
                f.write('  </Document>\n')
                f.write('</kml>\n')
        except FileExistsError:
            # File already exists, we'll append to it
            pass
    
    def _clean_string(self, text: str, replacement: str = " ") -> str:
        """
        Clean string by replacing newlines and carriage returns.
        
        Args:
            text: Input text
            replacement: Replacement string for newlines
            
        Returns:
            Cleaned text
        """
        if not text:
            return ""
        return re.sub(r'[\n\r]', replacement, str(text))
    
    def _format_data(self, group_id: int, page_html: str, markers_data: List[Dict]) -> Dict[str, Any]:
        """
        Format the downloaded data into CSV-compatible structure.
        
        Args:
            group_id: ZeeMap group ID
            page_html: HTML content from settings page
            markers_data: List of marker data from API
            
        Returns:
            Formatted data dictionary
        """
        soup = BeautifulSoup(page_html, 'html.parser')
        
        # Extract map metadata from HTML
        description_elem = soup.find('textarea', {'name': 'description'})
        description = self._clean_string(description_elem.get_text() if description_elem else "", "; ")
        
        email_elem = soup.find('input', {'name': 'email'})
        email = self._clean_string(email_elem.get('value', "") if email_elem else "", " ")
        
        name_elem = soup.find('input', {'name': 'name'})
        map_title = self._clean_string(name_elem.get('value', "") if name_elem else "", " ")
        
        # Extract marker data
        m_address = []
        m_country = []
        m_lat = []
        m_long = []
        m_name = []
        
        for marker in markers_data:
            # Address
            address = self._clean_string(marker.get('a', ""), " ")
            m_address.append(address)
            
            # Country
            country = self._clean_string(marker.get('cty', ""), "")
            m_country.append(country)
            
            # Latitude
            lat = marker.get('lat', 0)
            if isinstance(lat, (int, float)):
                m_lat.append(lat)
            else:
                m_lat.append(0)
            
            # Longitude
            lng = marker.get('lng', 0)
            if isinstance(lng, (int, float)):
                m_long.append(lng)
            else:
                m_long.append(0)
            
            # Name/Overlay
            name = self._clean_string(marker.get('ov', ""), "")
            m_name.append(name)
        
        return {
            'group': group_id,
            'map_title': map_title,
            'email': email,
            'description': description,
            'm_name': m_name,
            'm_address': m_address,
            'm_country': m_country,
            'm_long': m_long,
            'm_lat': m_lat
        }
    
    def _save_to_csv(self, data: Dict[str, Any]):
        """
        Save formatted data to CSV file.
        
        Args:
            data: Formatted data dictionary
        """
        # Flatten the data for CSV writing
        # Since markers are lists, we need to handle multiple markers per map
        max_markers = max(
            len(data['m_name']),
            len(data['m_address']),
            len(data['m_country']),
            len(data['m_long']),
            len(data['m_lat'])
        )
        
        with open(self.output_file, 'a', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            
            if max_markers == 0:
                # No markers, write single row with empty marker data
                writer.writerow([
                    data['group'],
                    data['map_title'],
                    data['email'],
                    data['description'],
                    '', '', '', '', ''
                ])
            else:
                # Write one row per marker
                for i in range(max_markers):
                    writer.writerow([
                        data['group'],
                        data['map_title'],
                        data['email'],
                        data['description'],
                        data['m_name'][i] if i < len(data['m_name']) else '',
                        data['m_address'][i] if i < len(data['m_address']) else '',
                        data['m_country'][i] if i < len(data['m_country']) else '',
                        data['m_long'][i] if i < len(data['m_long']) else '',
                        data['m_lat'][i] if i < len(data['m_lat']) else ''
                    ])
    
    def _save_to_kml(self, data: Dict[str, Any]):
        """
        Save formatted data to KML file.
        
        Args:
            data: Formatted data dictionary
        """
        # Read existing KML content
        try:
            tree = ET.parse(self.output_file)
            root = tree.getroot()
            document = root.find('.//{http://www.opengis.net/kml/2.2}Document')
        except (ET.ParseError, FileNotFoundError):
            # Create new KML structure
            root = ET.Element('kml')
            root.set('xmlns', 'http://www.opengis.net/kml/2.2')
            document = ET.SubElement(root, 'Document')
        
        # Add map information as a folder
        folder = ET.SubElement(document, 'Folder')
        
        # Add map metadata
        name_elem = ET.SubElement(folder, 'name')
        name_elem.text = data['map_title'] or f"ZeeMap {data['group']}"
        
        description_elem = ET.SubElement(folder, 'description')
        description_text = f"Group: {data['group']}"
        if data['email']:
            description_text += f"\nEmail: {data['email']}"
        if data['description']:
            description_text += f"\nDescription: {data['description']}"
        description_elem.text = description_text
        
        # Add markers as placemarks
        for i in range(len(data['m_name'])):
            if i < len(data['m_lat']) and i < len(data['m_long']) and data['m_lat'][i] != 0 and data['m_long'][i] != 0:
                placemark = ET.SubElement(folder, 'Placemark')
                
                # Marker name
                name_elem = ET.SubElement(placemark, 'name')
                name_elem.text = data['m_name'][i] or f"Marker {i+1}"
                
                # Marker description
                if data['m_address'][i] or data['m_country'][i]:
                    desc_elem = ET.SubElement(placemark, 'description')
                    desc_parts = []
                    if data['m_address'][i]:
                        desc_parts.append(f"Address: {data['m_address'][i]}")
                    if data['m_country'][i]:
                        desc_parts.append(f"Country: {data['m_country'][i]}")
                    desc_elem.text = "\n".join(desc_parts)
                
                # Marker coordinates
                point = ET.SubElement(placemark, 'Point')
                coordinates = ET.SubElement(point, 'coordinates')
                coordinates.text = f"{data['m_long'][i]},{data['m_lat'][i]},0"
        
        # Write the updated KML
        tree = ET.ElementTree(root)
        ET.indent(tree, space="  ", level=0)  # Pretty print
        tree.write(self.output_file, encoding='utf-8', xml_declaration=True)
    
    def download_map(self, map_id: int) -> bool:
        """
        Download a specific ZeeMap and save to CSV.
        
        Args:
            map_id: ZeeMap group ID
            
        Returns:
            True if successful, False otherwise
        """
        try:
            self.logger.info(f"Downloading map {map_id}...")
            
            # URLs for the map
            settings_url = f"https://www.zeemaps.com/map/settings?group={map_id}"
            markers_url = f"https://www.zeemaps.com/emarkers?g={map_id}"
            
            # Download settings page
            self.logger.debug(f"Fetching settings from: {settings_url}")
            settings_response = self.session.get(settings_url)
            settings_response.raise_for_status()
            
            # Download markers data
            self.logger.debug(f"Fetching markers from: {markers_url}")
            markers_response = self.session.get(markers_url)
            markers_response.raise_for_status()
            
            # Parse markers JSON
            try:
                markers_data = markers_response.json()
            except json.JSONDecodeError as e:
                self.logger.error(f"Failed to parse markers JSON for map {map_id}: {e}")
                return False
            
            # Format the data
            formatted_data = self._format_data(map_id, settings_response.text, markers_data)
            
            # Save to specified format
            if self.output_format == 'csv':
                self._save_to_csv(formatted_data)
            elif self.output_format == 'kml':
                self._save_to_kml(formatted_data)
            
            self.logger.info(f"Successfully downloaded map {map_id} with {len(markers_data)} markers")
            return True
            
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 403:
                self.logger.warning(f"Map {map_id} is private or not accessible")
            else:
                self.logger.error(f"HTTP error downloading map {map_id}: {e}")
            return False
            
        except requests.exceptions.RequestException as e:
            self.logger.error(f"Request error downloading map {map_id}: {e}")
            return False
            
        except Exception as e:
            self.logger.error(f"Unexpected error downloading map {map_id}: {e}")
            return False


def main():
    """Main function to handle command line arguments and run the downloader."""
    parser = argparse.ArgumentParser(
        description="Download ZeeMap data to CSV or KML format",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python zeemap-downloader.py 772301 --format csv
  python zeemap-downloader.py 772301 --format kml --output my_map.kml
  python zeemap-downloader.py 772301 --format csv --output my_map.csv --verbose
        """
    )
    
    parser.add_argument(
        'map_id',
        type=int,
        help='ZeeMap group ID to download'
    )
    
    parser.add_argument(
        '--format', '-f',
        choices=['csv', 'kml'],
        default='csv',
        help='Output format: csv or kml (default: csv)'
    )
    
    parser.add_argument(
        '--output', '-o',
        help='Output file path (default: zeemap_output.csv or zeemap_output.kml based on format)'
    )
    
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Enable verbose logging'
    )
    
    args = parser.parse_args()
    
    # Set default output file based on format if not specified
    if not args.output:
        if args.format == 'kml':
            args.output = 'zeemap_output.kml'
        else:
            args.output = 'zeemap_output.csv'
    
    # Create downloader instance
    downloader = ZeeMapDownloader(
        output_file=args.output,
        output_format=args.format,
        verbose=args.verbose
    )
    
    # Download the map
    success = downloader.download_map(args.map_id)
    
    if success:
        print(f"Successfully downloaded map {args.map_id} to {args.output}")
        sys.exit(0)
    else:
        print(f"Failed to download map {args.map_id}")
        sys.exit(1)


if __name__ == "__main__":
    main()
