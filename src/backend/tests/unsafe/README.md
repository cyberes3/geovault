# Unsafe Test Files

This directory contains various unsafe test files designed to test the security validation system. **DO NOT USE THESE FILES IN PRODUCTION!**

## Test Files

### 1. `malicious_xss.kml`
**Purpose**: Tests XSS attack prevention
**Contains**:
- Script tags with JavaScript
- Iframe with javascript: URLs
- Object and embed tags with malicious content
- Event handlers (onclick, onload, onmouseover)
- Malicious forms and links
- CSS with javascript: URLs

**Expected Result**: Should be rejected due to dangerous elements and attributes

### 2. `xxe_attack.kml`
**Purpose**: Tests XXE (XML External Entity) attack prevention
**Contains**:
- External entity declarations
- File system access attempts (file:///etc/passwd, etc.)
- Network access attempts (http://, ftp://, gopher://)
- Entity references in content

**Expected Result**: Should be rejected due to XXE attack vectors

### 3. `fake_kml.kml`
**Purpose**: Tests file signature validation
**Contains**:
- Plain text content (not XML)
- Malicious script content
- Wrong file signature

**Expected Result**: Should be rejected due to invalid file signature

### 4. `fake_kmz.kmz`
**Purpose**: Tests KMZ file signature validation
**Contains**:
- Plain text content (not ZIP)
- Wrong file signature

**Expected Result**: Should be rejected due to invalid ZIP signature

### 5. `zip_slip_attack.kmz`
**Purpose**: Tests zip slip attack prevention
**Contains**:
- Files with directory traversal paths (../../../etc/passwd)
- Absolute paths (/etc/shadow)
- Executable files (.exe, .bat, .cmd, .scr, .pif)
- Files with null bytes
- Very long filenames
- Deeply nested directories

**Expected Result**: Should be rejected due to zip slip attacks and executable files

### 6. `executable_files.kmz`
**Purpose**: Tests executable file detection
**Contains**:
- Various executable files (.exe, .bat, .cmd, .scr, .pif, .com, .vbs, .js, .ps1, .py, .sh)
- Files with double extensions (.pdf.exe, .jpg.bat)
- Files with spaces and special characters
- Files in subdirectories
- Some legitimate files for comparison

**Expected Result**: Should be rejected due to executable files

### 7. `large_kml.kml`
**Purpose**: Tests file size limit validation
**Contains**:
- 50,000 placemarks with extensive descriptions
- Large amount of text content
- File size: ~134 MB

**Expected Result**: Should be rejected due to exceeding 5MB KML limit

### 8. `file<with>special:chars|and?wildcards*.kml`
**Purpose**: Tests filename validation
**Contains**:
- Normal KML content
- Filename with special characters: < > : | ? *

**Expected Result**: Should be rejected due to invalid filename characters

### 9. `../../../etc/passwd.kml`
**Purpose**: Tests path traversal filename validation
**Contains**:
- Normal KML content
- Filename with path traversal (../../../etc/passwd)

**Expected Result**: Should be rejected due to path traversal in filename

### 10. `empty_file.kml`
**Purpose**: Tests empty file validation
**Contains**:
- Empty file (0 bytes)

**Expected Result**: Should be rejected due to empty file

## Test Scripts

### `create_zip_slip_kmz.py`
Script to generate the zip slip attack KMZ file with various attack vectors.

### `create_large_kml.py`
Script to generate a large KML file that exceeds size limits.

### `create_executable_kmz.py`
Script to generate a KMZ file with various executable files.

## Usage

These files are designed to be used with the security validation system to ensure that all attack vectors are properly blocked. Each file should trigger specific security validations and be rejected by the system.

## Security Notes

- These files contain malicious content for testing purposes only
- Do not execute or process these files outside of the test environment
- The content is designed to be safe for testing but should not be trusted
- Always use these files in a controlled test environment

## Expected Test Results

All of these files should be rejected by the security validation system:

1. **File Signature Validation**: `fake_kml.kml`, `fake_kmz.kmz`
2. **Content Sanitization**: `malicious_xss.kml`
3. **XXE Prevention**: `xxe_attack.kml`
4. **Zip Slip Protection**: `zip_slip_attack.kmz`
5. **Executable File Detection**: `executable_files.kmz`
6. **File Size Limits**: `large_kml.kml`
7. **Filename Validation**: `file<with>special:chars|and?wildcards*.kml`, `../../../etc/passwd.kml`
8. **Empty File Detection**: `empty_file.kml`

If any of these files are accepted by the system, it indicates a security vulnerability that needs to be addressed.
