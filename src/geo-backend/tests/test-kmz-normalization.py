#!/usr/bin/env python3
"""
Test script to verify KMZ/KML normalization and duplicate detection.
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), '..'))

from geo_lib.daemon.workers.workers_lib.importer.kml import kmz_to_kml, normalize_kml_for_comparison
import hashlib


def _hash_kml(b: str):
    """Hash function for KML content (copied from import_item.py)"""
    if not isinstance(b, bytes):
        b = b.encode()
    return hashlib.sha256(b).hexdigest()


def test_normalization():
    """Test KML normalization with the sample files."""

    # Read the sample files
    kml_file = "Test Items Earth.kml"
    kmz_file = "Test Items Earth.kmz"

    print("Testing KMZ/KML normalization...")
    print("=" * 50)

    # Test 1: Read KML file directly
    with open(kml_file, 'r', encoding='utf-8') as f:
        kml_content = f.read()

    print(f"1. Original KML file size: {len(kml_content)} characters")

    # Test 2: Extract KML from KMZ
    with open(kmz_file, 'rb') as f:
        kmz_content = f.read()

    extracted_kml = kmz_to_kml(kmz_content)
    print(f"2. Extracted KML from KMZ size: {len(extracted_kml)} characters")

    # Test 3: Normalize both files
    normalized_kml = normalize_kml_for_comparison(kml_content)
    normalized_extracted = normalize_kml_for_comparison(extracted_kml)

    print(f"3. Normalized KML size: {len(normalized_kml)} characters")
    print(f"4. Normalized extracted KML size: {len(normalized_extracted)} characters")

    # Test 4: Check if they're identical after normalization
    are_identical = normalized_kml == normalized_extracted
    print(f"5. Files are identical after normalization: {are_identical}")

    # Test 5: Generate hashes
    hash_original = _hash_kml(kml_content)
    hash_extracted = _hash_kml(extracted_kml)
    hash_normalized_kml = _hash_kml(normalized_kml)
    hash_normalized_extracted = _hash_kml(normalized_extracted)

    print(f"6. Hash of original KML: {hash_original[:16]}...")
    print(f"7. Hash of extracted KML: {hash_extracted[:16]}...")
    print(f"8. Hash of normalized KML: {hash_normalized_kml[:16]}...")
    print(f"9. Hash of normalized extracted: {hash_normalized_extracted[:16]}...")

    # Test 6: Check if normalized hashes match
    hashes_match = hash_normalized_kml == hash_normalized_extracted
    print(f"10. Normalized hashes match: {hashes_match}")

    if hashes_match:
        print("\n✅ SUCCESS: KML and KMZ files are now properly normalized for duplicate detection!")
    else:
        print("\n❌ FAILURE: Files are still different after normalization")
        print("\nDifferences found:")
        # Show first few differences
        lines_kml = normalized_kml.split('\n')
        lines_extracted = normalized_extracted.split('\n')
        for i, (line1, line2) in enumerate(zip(lines_kml, lines_extracted)):
            if line1 != line2:
                print(f"Line {i + 1}:")
                print(f"  KML:  {line1}")
                print(f"  KMZ:  {line2}")
                if i > 5:  # Limit output
                    print("  ... (more differences)")
                    break

    return hashes_match


if __name__ == "__main__":
    success = test_normalization()
    test_with_other_files()
    sys.exit(0 if success else 1)
