#!/usr/bin/env python3
"""
Script to create a KMZ file with zip slip attack for testing.
"""

import zipfile
import os

def create_zip_slip_kmz():
    """Create a KMZ file with zip slip attack."""
    
    # Normal KML content
    normal_kml = '''<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
    <Document>
        <name>Zip Slip Attack Test</name>
        <description>This KMZ contains zip slip attack vectors</description>
        <Placemark>
            <name>Normal Placemark</name>
            <description>This is a normal placemark</description>
            <Point>
                <coordinates>-122.4194,37.7749,0</coordinates>
            </Point>
        </Placemark>
    </Document>
</kml>'''
    
    # Create the malicious KMZ file
    with zipfile.ZipFile('zip_slip_attack.kmz', 'w') as kmz:
        # Add normal KML file
        kmz.writestr('doc.kml', normal_kml)
        
        # Add files with directory traversal (zip slip attack)
        kmz.writestr('../../../etc/passwd', 'malicious content - should not be extracted')
        kmz.writestr('../../../../tmp/malicious.txt', 'another malicious file')
        kmz.writestr('/etc/shadow', 'absolute path attack')
        kmz.writestr('..\\..\\..\\windows\\system32\\malicious.exe', 'Windows path traversal')
        
        # Add executable files (should be blocked)
        kmz.writestr('malware.exe', b'This is not really malware, just a test')
        kmz.writestr('virus.bat', '@echo off\necho This is a test batch file')
        kmz.writestr('trojan.cmd', '@echo off\necho This is a test cmd file')
        kmz.writestr('backdoor.scr', b'This is not really a backdoor, just a test')
        kmz.writestr('keylogger.pif', b'This is not really a keylogger, just a test')
        
        # Add files with suspicious extensions
        kmz.writestr('suspicious.com', b'This is not really malware, just a test')
        kmz.writestr('malicious.vbs', 'MsgBox "This is a test VBS file"')
        kmz.writestr('dangerous.js', 'alert("This is a test JavaScript file")')
        
        # Add files with null bytes (another attack vector)
        kmz.writestr('file\x00.txt', 'null byte attack')
        kmz.writestr('file\x00.exe', b'null byte executable attack')
        
        # Add files with very long names (DoS attack)
        long_name = 'A' * 1000 + '.txt'
        kmz.writestr(long_name, 'very long filename attack')
        
        # Add deeply nested directories (DoS attack)
        deep_path = '/'.join(['dir' + str(i) for i in range(100)]) + '/file.txt'
        kmz.writestr(deep_path, 'deeply nested directory attack')
    
    print("Created zip_slip_attack.kmz with various attack vectors")

if __name__ == "__main__":
    create_zip_slip_kmz()
