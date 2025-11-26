#!/usr/bin/env python3
"""
Test script to verify that Google Earth MIME types are now accepted.
"""

import os
import sys
from pathlib import Path

# Add the project root to the Python path
project_root = Path(__file__).parent.parent.parent
sys.path.insert(0, str(project_root))

from geo_lib.security.file_validation import SecureFileValidator
from django.core.files.uploadedfile import SimpleUploadedFile


def test_mime_type(file_path, mime_type, should_pass=True):
    """Test a file with a specific MIME type."""
    print(f"\nTesting: {file_path} with MIME type: {mime_type}")
    
    try:
        with open(file_path, 'rb') as f:
            content = f.read()
        
        # Create uploaded file with specific MIME type
        uploaded_file = SimpleUploadedFile(
            os.path.basename(file_path),
            content,
            content_type=mime_type
        )
        
        # Test validation
        validator = SecureFileValidator()
        is_valid, message = validator.validate_file(uploaded_file)
        
        if should_pass:
            if is_valid:
                print(f"✅ PASS: File was accepted (as expected)")
                return True
            else:
                print(f"❌ FAIL: File was rejected (should be accepted)")
                print(f"   Rejection reason: {message}")
                return False
        else:
            if not is_valid:
                print(f"✅ PASS: File was rejected (as expected)")
                print(f"   Rejection reason: {message}")
                return True
            else:
                print(f"❌ FAIL: File was accepted (should be rejected)")
                return False
                
    except Exception as e:
        print(f"❌ ERROR: Exception during testing: {e}")
        return False


def main():
    """Test MIME type validation."""
    print("Testing MIME Type Validation")
    print("=" * 40)
    
    # Test with a valid KML file
    kml_file = "malicious_xss.kml"  # We'll use this for MIME type testing (content will be rejected for other reasons)
    kmz_file = "zip_slip_attack.kmz"  # We'll use this for MIME type testing
    
    passed = 0
    failed = 0
    
    # Test valid Google Earth MIME types (should pass MIME validation, but may fail for other reasons)
    valid_kmz_mime_types = [
        'application/vnd.google-earth.kmz',
        'application/vnd.google-earth.kmz+xml',
        'application/zip',
        'application/x-zip-compressed'
    ]
    
    valid_kml_mime_types = [
        'application/vnd.google-earth.kml+xml',
        'application/vnd.google-earth.kml',
        'text/xml',
        'application/xml'
    ]
    
    # Test KMZ MIME types
    for mime_type in valid_kmz_mime_types:
        file_path = os.path.join(os.path.dirname(__file__), kmz_file)
        if os.path.exists(file_path):
            # These should pass MIME validation (but may fail for zip slip attacks)
            if test_mime_type(file_path, mime_type, should_pass=True):
                passed += 1
            else:
                failed += 1
    
    # Test KML MIME types
    for mime_type in valid_kml_mime_types:
        file_path = os.path.join(os.path.dirname(__file__), kml_file)
        if os.path.exists(file_path):
            # These should pass MIME validation (but may fail for malicious content)
            if test_mime_type(file_path, mime_type, should_pass=True):
                passed += 1
            else:
                failed += 1
    
    # Test invalid MIME types (should be rejected)
    invalid_mime_types = [
        'application/javascript',
        'text/html',
        'application/pdf',
        'image/jpeg'
    ]
    
    for mime_type in invalid_mime_types:
        file_path = os.path.join(os.path.dirname(__file__), kml_file)
        if os.path.exists(file_path):
            if test_mime_type(file_path, mime_type, should_pass=False):
                passed += 1
            else:
                failed += 1
    
    print("\n" + "=" * 40)
    print(f"Test Results: {passed} passed, {failed} failed")
    
    if failed == 0:
        print("🎉 All MIME type tests passed!")
    else:
        print("⚠️  Some MIME type tests failed.")
    
    return failed == 0


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
