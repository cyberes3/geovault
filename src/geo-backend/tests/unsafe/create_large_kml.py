#!/usr/bin/env python3
"""
Script to create a large KML file for testing size limits.
"""

import os

def create_large_kml():
    """Create a large KML file that exceeds size limits."""
    
    # Start with basic KML structure
    large_kml = '''<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
    <Document>
        <name>Large KML File for Size Testing</name>
        <description>This KML file is designed to exceed size limits for testing purposes</description>'''
    
    # Add many placemarks to make the file large
    for i in range(50000):  # This should create a very large file
        large_kml += f'''
        <Placemark>
            <name>Placemark {i}</name>
            <description>This is placemark number {i} with some additional text to make the file larger. 
            Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. 
            Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. 
            Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. 
            Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
            Additional text to increase file size: {'A' * 1000}</description>
            <Point>
                <coordinates>-122.4194,37.7749,{i}</coordinates>
            </Point>
            <ExtendedData>
                <Data name="id">
                    <value>{i}</value>
                </Data>
                <Data name="description">
                    <value>Extended description for placemark {i} with lots of additional text to increase file size. {'B' * 500}</value>
                </Data>
                <Data name="category">
                    <value>Test Category {i % 10}</value>
                </Data>
            </ExtendedData>
        </Placemark>'''
    
    # Close the KML structure
    large_kml += '''
    </Document>
</kml>'''
    
    # Write to file
    with open('large_kml.kml', 'w', encoding='utf-8') as f:
        f.write(large_kml)
    
    # Get file size
    file_size = os.path.getsize('large_kml.kml')
    size_mb = file_size / (1024 * 1024)
    
    print(f"Created large_kml.kml with {size_mb:.2f} MB")
    print(f"File size: {file_size:,} bytes")
    print(f"This should exceed the 5MB KML limit")

if __name__ == "__main__":
    create_large_kml()
