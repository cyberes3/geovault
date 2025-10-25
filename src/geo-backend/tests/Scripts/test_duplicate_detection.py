#!/usr/bin/env python3
"""
Test script to compare GeoJSON output from KML and KMZ files.
This helps verify that duplicate detection works correctly between different file formats.
"""

import sys
import os
import hashlib
import json

# Add parent directories to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..')))

# Setup Django
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "website.settings")
import django
django.setup()

from geo_lib.processing.processors import get_processor
from geo_lib.processing.geojson_normalization import hash_normalized_geojson


def hash_geojson(geojson_data: dict) -> str:
    """Create a hash of the GeoJSON data (non-normalized)."""
    geojson_str = json.dumps(geojson_data, sort_keys=True)
    return hashlib.sha256(geojson_str.encode('utf-8')).hexdigest()


def hash_geojson_normalized(geojson_data: dict) -> str:
    """Create a normalized hash of the GeoJSON data."""
    return hash_normalized_geojson(geojson_data)


def test_file_conversion(file_path: str) -> tuple:
    """Convert a file to GeoJSON and return the data and hash."""
    print(f"\n{'='*60}")
    print(f"Processing: {file_path}")
    print(f"{'='*60}")
    
    # Read file
    with open(file_path, 'rb') as f:
        file_data = f.read()
    
    file_size = len(file_data)
    print(f"File size: {file_size:,} bytes ({file_size / 1024:.2f} KB)")
    
    # Convert to GeoJSON
    try:
        processor = get_processor(file_data, os.path.basename(file_path))
        geojson_data, processing_log = processor.process()
        
        # Print conversion log
        print("\nConversion log:")
        for entry in processing_log.get():
            print(f"  [{entry.source}] {entry.msg}")
        
        # Get feature count
        feature_count = len(geojson_data.get('features', []))
        print(f"\nFeatures found: {feature_count}")
        
        # Calculate hashes (both raw and normalized)
        geojson_hash = hash_geojson(geojson_data)
        normalized_hash = hash_geojson_normalized(geojson_data)
        print(f"GeoJSON hash (raw):        {geojson_hash}")
        print(f"GeoJSON hash (normalized): {normalized_hash}")
        
        # Show first feature as sample
        if feature_count > 0:
            first_feature = geojson_data['features'][0]
            print(f"\nFirst feature sample:")
            print(f"  Type: {first_feature.get('geometry', {}).get('type')}")
            print(f"  Name: {first_feature.get('properties', {}).get('name', 'Unnamed')}")
            print(f"  Properties: {len(first_feature.get('properties', {}))} fields")
        
        return geojson_data, geojson_hash, normalized_hash, True
        
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()
        return None, None, None, False


def compare_files(file1_path: str, file2_path: str):
    """Compare GeoJSON output of two files."""
    print("\n" + "="*60)
    print("DUPLICATE DETECTION TEST")
    print("="*60)
    
    # Convert first file
    geojson1, hash1, norm_hash1, success1 = test_file_conversion(file1_path)
    
    # Convert second file
    geojson2, hash2, norm_hash2, success2 = test_file_conversion(file2_path)
    
    # Compare results
    print("\n" + "="*60)
    print("COMPARISON RESULTS")
    print("="*60)
    
    if not success1 or not success2:
        print("❌ One or both conversions failed - cannot compare")
        return
    
    print(f"\nFile 1: {os.path.basename(file1_path)}")
    print(f"  Raw Hash:        {hash1}")
    print(f"  Normalized Hash: {norm_hash1}")
    print(f"  Features: {len(geojson1.get('features', []))}")
    
    print(f"\nFile 2: {os.path.basename(file2_path)}")
    print(f"  Raw Hash:        {hash2}")
    print(f"  Normalized Hash: {norm_hash2}")
    print(f"  Features: {len(geojson2.get('features', []))}")
    
    # Check raw hashes
    print("\n" + "-"*60)
    print("RAW HASH COMPARISON")
    print("-"*60)
    if hash1 == hash2:
        print("✅ Raw hashes MATCH - Files are byte-for-byte identical after conversion")
    else:
        print("❌ Raw hashes DIFFER - Files have formatting differences")
    
    # Check normalized hashes
    print("\n" + "-"*60)
    print("NORMALIZED HASH COMPARISON (for duplicate detection)")
    print("-"*60)
    if norm_hash1 == norm_hash2:
        print("✅ NORMALIZED HASHES MATCH - These files ARE duplicates!")
        print("   Duplicate detection WILL work between these files.")
        print("   The files contain the same geographic data.")
    else:
        print("❌ NORMALIZED HASHES DIFFER - These files are NOT duplicates!")
        print("   Duplicate detection will NOT work between these files.")
        print("   The files contain different geographic data.")
        
        # Show what's different
        print("\nAnalyzing differences...")
        
        # Compare feature counts
        count1 = len(geojson1.get('features', []))
        count2 = len(geojson2.get('features', []))
        if count1 != count2:
            print(f"  - Feature count differs: {count1} vs {count2}")
        
        # Compare feature-by-feature
        if count1 == count2 and count1 > 0:
            print(f"  - Same number of features ({count1}), checking content...")
            diff_count = 0
            for i, (f1, f2) in enumerate(zip(geojson1['features'], geojson2['features'])):
                if f1 != f2:
                    diff_count += 1
                    print(f"\n    Feature {i} ({f1.get('properties', {}).get('name', 'Unnamed')}) differs:")
                    
                    # Compare geometry
                    if f1.get('geometry') != f2.get('geometry'):
                        print(f"      - Geometry differs")
                        g1 = f1.get('geometry', {})
                        g2 = f2.get('geometry', {})
                        if g1.get('type') != g2.get('type'):
                            print(f"        Type: {g1.get('type')} vs {g2.get('type')}")
                        if g1.get('coordinates') != g2.get('coordinates'):
                            print(f"        Coordinates differ:")
                            c1 = g1.get('coordinates')
                            c2 = g2.get('coordinates')
                            
                            # Detailed coordinate comparison
                            if isinstance(c1, list) and isinstance(c2, list):
                                if len(c1) != len(c2):
                                    print(f"          Length: {len(c1)} vs {len(c2)}")
                                else:
                                    # Check if it's a simple coordinate pair
                                    if len(c1) > 0 and isinstance(c1[0], (int, float)):
                                        print(f"          File 1: {c1}")
                                        print(f"          File 2: {c2}")
                                        # Check precision differences
                                        if len(c1) >= 2 and len(c2) >= 2:
                                            if c1[0] != c2[0] or c1[1] != c2[1]:
                                                print(f"          X/Y differ")
                                            if len(c1) > 2 and len(c2) > 2 and c1[2] != c2[2]:
                                                print(f"          Z differs: {c1[2]} vs {c2[2]}")
                                    else:
                                        # It's a complex coordinate structure
                                        print(f"          File 1 has {len(c1)} coordinate points")
                                        print(f"          File 2 has {len(c2)} coordinate points")
                                        # Show first few points
                                        for j in range(min(3, len(c1), len(c2))):
                                            if c1[j] != c2[j]:
                                                print(f"          Point {j}: {c1[j]} vs {c2[j]}")
                    
                    # Compare properties
                    if f1.get('properties') != f2.get('properties'):
                        print(f"      - Properties differ")
                        p1 = f1.get('properties', {})
                        p2 = f2.get('properties', {})
                        all_keys = set(p1.keys()) | set(p2.keys())
                        for key in sorted(all_keys):
                            if p1.get(key) != p2.get(key):
                                val1 = p1.get(key)
                                val2 = p2.get(key)
                                print(f"        {key}:")
                                print(f"          File 1: {repr(val1)}")
                                print(f"          File 2: {repr(val2)}")
                    
                    # Only show first few differences
                    if diff_count >= 3:
                        remaining = count1 - i - 1
                        if remaining > 0:
                            print(f"\n    ... ({remaining} more features not shown)")
                        break
            
            if diff_count == 0:
                print(f"    All features are identical!")
            else:
                print(f"\n    Total features with differences: {diff_count}")


if __name__ == '__main__':
    # Get test files directory
    tests_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    # Test files
    kml_file = os.path.join(tests_dir, "Test Items.kml")
    kmz_file = os.path.join(tests_dir, "Test Items.kmz")
    
    # Verify files exist
    if not os.path.exists(kml_file):
        print(f"ERROR: File not found: {kml_file}")
        sys.exit(1)
    
    if not os.path.exists(kmz_file):
        print(f"ERROR: File not found: {kmz_file}")
        sys.exit(1)
    
    # Compare the files
    compare_files(kml_file, kmz_file)

