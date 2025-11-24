#!/usr/bin/env python3
"""
Test script to validate and process GPX files that failed during upload.
Tests files in the 'from dad' directory and provides detailed error information.

Usage:
    python test_failed_gpx_files.py
    python test_failed_gpx_files.py <file_path>
"""

import sys
import os
import django
import traceback
from pathlib import Path

# Add the backend directory to the path so we can import geo_lib and website
backend_dir = os.path.dirname(os.path.dirname(os.path.dirname(__file__)))
sys.path.insert(0, backend_dir)

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')
django.setup()

from django.core.files.uploadedfile import SimpleUploadedFile
from geo_lib.security.file_validation import SecureFileValidator, FileValidationError, SecurityError
from geo_lib.processing.file_types import FileType, get_file_type_by_extension
from geo_lib.processing.processors import get_processor
import magic


def analyze_file_encoding(file_path: Path):
    """Analyze file encoding and BOM."""
    with open(file_path, 'rb') as f:
        raw_data = f.read()
    
    # Check for BOM
    bom_info = []
    if raw_data.startswith(b'\xef\xbb\xbf'):
        bom_info.append("UTF-8 BOM detected")
    elif raw_data.startswith(b'\xff\xfe'):
        bom_info.append("UTF-16 LE BOM detected")
    elif raw_data.startswith(b'\xfe\xff'):
        bom_info.append("UTF-16 BE BOM detected")
    else:
        bom_info.append("No BOM detected")
    
    # Try to detect encoding
    try:
        content = raw_data.decode('utf-8')
        bom_info.append("Successfully decoded as UTF-8")
    except UnicodeDecodeError as e:
        bom_info.append(f"UTF-8 decode error: {str(e)}")
        try:
            content = raw_data.decode('utf-8-sig')  # Try with BOM handling
            bom_info.append("Successfully decoded as UTF-8 (with BOM handling)")
        except:
            bom_info.append("Failed to decode as UTF-8 even with BOM handling")
    
    return bom_info, raw_data


def check_file_signature(file_data: bytes, filename: str):
    """Check file signature/magic numbers."""
    from geo_lib.processing.file_types import validate_file_signature, get_file_type_by_extension
    
    try:
        _, ext = os.path.splitext(filename)
        file_type = get_file_type_by_extension(ext)
        is_valid = validate_file_signature(file_data, file_type)
        return is_valid, file_type
    except Exception as e:
        return False, None


def check_mime_type(file_data: bytes, filename: str):
    """Check MIME type."""
    from geo_lib.processing.file_types import validate_mime_type, get_file_type_by_extension
    
    try:
        mime_type = magic.from_buffer(file_data[:1024], mime=True)
        _, ext = os.path.splitext(filename)
        file_type = get_file_type_by_extension(ext)
        is_valid = validate_mime_type(mime_type, file_type)
        return is_valid, mime_type, file_type
    except Exception as e:
        return False, None, None


def check_xml_structure(file_data: bytes):
    """Check XML structure without full validation."""
    import xml.etree.ElementTree as ET
    
    try:
        content = file_data.decode('utf-8')
    except UnicodeDecodeError:
        try:
            content = file_data.decode('utf-8-sig')
        except:
            return False, "Failed to decode as UTF-8"
    
    try:
        parser = ET.XMLParser()
        root = ET.fromstring(content, parser=parser)
        
        # Check for GPX namespace
        tag_lower = root.tag.lower()
        has_gpx = 'gpx' in tag_lower
        
        # Check for required GPX elements
        required_elements = ['trk', 'rte', 'wpt']
        found_elements = []
        for elem in root.iter():
            tag_name = elem.tag.split('}')[-1].lower() if '}' in elem.tag else elem.tag.lower()
            if tag_name in required_elements:
                found_elements.append(tag_name)
        
        return True, {
            'root_tag': root.tag,
            'has_gpx_namespace': has_gpx,
            'found_elements': list(set(found_elements))
        }
    except ET.ParseError as e:
        return False, f"XML Parse Error: {str(e)}"
    except Exception as e:
        return False, f"XML Error: {str(e)}"


def test_file(file_path: Path):
    """Test a single file with comprehensive validation."""
    print(f"\n{'='*80}")
    print(f"Testing: {file_path.name}")
    print(f"{'='*80}\n")
    
    # Read file
    try:
        with open(file_path, 'rb') as f:
            file_data = f.read()
    except Exception as e:
        print(f"❌ ERROR: Failed to read file: {str(e)}")
        return False
    
    file_size = len(file_data)
    print(f"File size: {file_size:,} bytes ({file_size / 1024:.2f} KB)")
    
    # Analyze encoding
    print("\n📄 Encoding Analysis:")
    print("-" * 80)
    bom_info, raw_data = analyze_file_encoding(file_path)
    for info in bom_info:
        print(f"  • {info}")
    
    # Check file signature
    print("\n🔍 File Signature Check:")
    print("-" * 80)
    sig_valid, file_type = check_file_signature(file_data, file_path.name)
    if sig_valid:
        print(f"  ✅ Valid {file_type.value.upper()} signature")
    else:
        print(f"  ❌ Invalid signature for {file_type.value.upper() if file_type else 'unknown type'}")
        print(f"  First 100 bytes (hex): {file_data[:100].hex()}")
        print(f"  First 100 bytes (text): {file_data[:100]}")
        
        # Check if it's a BOM issue
        if file_data.startswith(b'\xef\xbb\xbf'):
            print(f"\n  ⚠️  ISSUE IDENTIFIED: File starts with UTF-8 BOM (Byte Order Mark)")
            print(f"     The file signature validation doesn't account for BOM.")
            print(f"     After BOM, file starts with: {file_data[3:50]}")
            # Try checking signature without BOM
            file_data_no_bom = file_data[3:]
            sig_valid_no_bom, _ = check_file_signature(file_data_no_bom, file_path.name)
            if sig_valid_no_bom:
                print(f"     ✅ Signature is valid when BOM is removed")
    
    # Check MIME type
    print("\n📋 MIME Type Check:")
    print("-" * 80)
    mime_valid, mime_type, detected_type = check_mime_type(file_data, file_path.name)
    if mime_valid:
        print(f"  ✅ Valid MIME type: {mime_type}")
    else:
        print(f"  ❌ Invalid MIME type: {mime_type} (expected {detected_type.value.upper() if detected_type else 'unknown'})")
    
    # Check XML structure
    print("\n🔧 XML Structure Check:")
    print("-" * 80)
    xml_valid, xml_info = check_xml_structure(file_data)
    if xml_valid:
        print(f"  ✅ Valid XML structure")
        if isinstance(xml_info, dict):
            print(f"  Root tag: {xml_info.get('root_tag', 'unknown')}")
            print(f"  Has GPX namespace: {xml_info.get('has_gpx_namespace', False)}")
            print(f"  Found GPX elements: {', '.join(xml_info.get('found_elements', []))}")
    else:
        print(f"  ❌ XML structure error: {xml_info}")
    
    # Full validation using SecureFileValidator
    print("\n🛡️  Full Validation (SecureFileValidator):")
    print("-" * 80)
    try:
        # Create a SimpleUploadedFile for validation
        uploaded_file = SimpleUploadedFile(
            name=file_path.name,
            content=file_data,
            content_type='text/xml'
        )
        
        validator = SecureFileValidator()
        is_valid, validation_message = validator.validate_file(uploaded_file)
        
        if is_valid:
            print(f"  ✅ Validation PASSED: {validation_message}")
        else:
            print(f"  ❌ Validation FAILED: {validation_message}")
            
            # Try to get more details by running individual validation steps
            print("\n  Detailed validation breakdown:")
            try:
                validator._validate_basic_properties(uploaded_file)
                print("    ✅ Basic properties: OK")
            except Exception as e:
                print(f"    ❌ Basic properties: {str(e)}")
            
            try:
                uploaded_file.seek(0)
                validator._validate_file_signature(uploaded_file)
                print("    ✅ File signature: OK")
            except Exception as e:
                print(f"    ❌ File signature: {str(e)}")
            
            try:
                uploaded_file.seek(0)
                validator._validate_mime_type(uploaded_file)
                print("    ✅ MIME type: OK")
            except Exception as e:
                print(f"    ❌ MIME type: {str(e)}")
            
            try:
                uploaded_file.seek(0)
                validator._validate_file_size(uploaded_file)
                print("    ✅ File size: OK")
            except Exception as e:
                print(f"    ❌ File size: {str(e)}")
            
            try:
                uploaded_file.seek(0)
                validator._validate_content(uploaded_file)
                print("    ✅ Content structure: OK")
            except Exception as e:
                print(f"    ❌ Content structure: {str(e)}")
                # Try to get more details about GPX structure
                if 'gpx' in file_path.name.lower():
                    try:
                        uploaded_file.seek(0)
                        gpx_content = uploaded_file.read().decode('utf-8')
                        uploaded_file.seek(0)
                        validator._validate_gpx_structure(gpx_content)
                        print("      (GPX structure validation passed when called directly)")
                    except Exception as e2:
                        print(f"      GPX structure error: {str(e2)}")
            
            return False
    except Exception as e:
        print(f"  ❌ Validation exception: {str(e)}")
        print(f"  Traceback:\n{traceback.format_exc()}")
        return False
    
    # Try processing the file
    if is_valid:
        print("\n⚙️  Processing Test:")
        print("-" * 80)
        try:
            processor = get_processor(file_data, file_path.name)
            print(f"  ✅ Processor created: {type(processor).__name__}")
            
            # Try conversion
            geojson = processor.convert_to_geojson()
            if geojson:
                feature_count = len(geojson.get('features', []))
                print(f"  ✅ Conversion successful: {feature_count} features")
            else:
                print(f"  ⚠️  Conversion returned empty result")
        except Exception as e:
            print(f"  ❌ Processing failed: {str(e)}")
            print(f"  Traceback:\n{traceback.format_exc()}")
    
    return is_valid


def main():
    """Main entry point."""
    if len(sys.argv) > 1:
        # Test a specific file
        file_path = Path(sys.argv[1])
        if not file_path.exists():
            print(f"Error: File not found: {file_path}")
            sys.exit(1)
        test_file(file_path)
    else:
        # Test all files in the 'from dad' directory
        test_dir = Path(__file__).parent.parent / 'private' / 'from dad'
        
        if not test_dir.exists():
            print(f"Error: Test directory not found: {test_dir}")
            sys.exit(1)
        
        gpx_files = sorted(test_dir.glob('*.gpx'))
        
        if not gpx_files:
            print(f"No GPX files found in {test_dir}")
            sys.exit(1)
        
        print(f"Found {len(gpx_files)} GPX files to test\n")
        
        results = []
        for gpx_file in gpx_files:
            is_valid = test_file(gpx_file)
            results.append((gpx_file.name, is_valid))
        
        # Summary
        print(f"\n{'='*80}")
        print("SUMMARY")
        print(f"{'='*80}\n")
        
        passed = sum(1 for _, valid in results if valid)
        failed = len(results) - passed
        
        print(f"Total files tested: {len(results)}")
        print(f"✅ Passed: {passed}")
        print(f"❌ Failed: {failed}\n")
        
        if failed > 0:
            print("Failed files:")
            for name, valid in results:
                if not valid:
                    print(f"  • {name}")
        
        print()


if __name__ == '__main__':
    main()

