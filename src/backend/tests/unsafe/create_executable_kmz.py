#!/usr/bin/env python3
"""
Script to create a KMZ file with executable files for testing.
"""

import zipfile

def create_executable_kmz():
    """Create a KMZ file containing executable files."""
    
    # Normal KML content
    normal_kml = '''<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
    <Document>
        <name>Executable Files Test</name>
        <description>This KMZ contains executable files for testing</description>
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
    with zipfile.ZipFile('executable_files.kmz', 'w') as kmz:
        # Add normal KML file
        kmz.writestr('doc.kml', normal_kml)
        
        # Add various executable files (should be blocked)
        kmz.writestr('malware.exe', b'This is not really malware, just a test executable')
        kmz.writestr('virus.bat', '@echo off\necho This is a test batch file\npause')
        kmz.writestr('trojan.cmd', '@echo off\necho This is a test cmd file\npause')
        kmz.writestr('backdoor.scr', b'This is not really a backdoor, just a test screen saver')
        kmz.writestr('keylogger.pif', b'This is not really a keylogger, just a test pif file')
        
        # Add files with suspicious extensions
        kmz.writestr('suspicious.com', b'This is not really malware, just a test com file')
        kmz.writestr('malicious.vbs', 'MsgBox "This is a test VBS file"\nWScript.Quit')
        kmz.writestr('dangerous.js', 'alert("This is a test JavaScript file");')
        kmz.writestr('payload.ps1', 'Write-Host "This is a test PowerShell script"')
        kmz.writestr('exploit.py', 'print("This is a test Python script")')
        kmz.writestr('attack.sh', '#!/bin/bash\necho "This is a test shell script"')
        
        # Add files with double extensions (common malware technique)
        kmz.writestr('document.pdf.exe', b'This is not really malware, just a test')
        kmz.writestr('image.jpg.bat', '@echo off\necho This is not really malware')
        kmz.writestr('video.mp4.scr', b'This is not really malware, just a test')
        
        # Add files with spaces and special characters
        kmz.writestr('malware file.exe', b'This is not really malware, just a test')
        kmz.writestr('virus (1).bat', '@echo off\necho This is not really malware')
        kmz.writestr('trojan-v2.cmd', '@echo off\necho This is not really malware')
        
        # Add files in subdirectories
        kmz.writestr('bin/malware.exe', b'This is not really malware, just a test')
        kmz.writestr('scripts/virus.bat', '@echo off\necho This is not really malware')
        kmz.writestr('tools/backdoor.scr', b'This is not really malware, just a test')
        
        # Add some legitimate files for comparison
        kmz.writestr('readme.txt', 'This is a legitimate text file')
        kmz.writestr('data.csv', 'name,value\nitem1,100\nitem2,200')
        kmz.writestr('config.xml', '<?xml version="1.0"?><config><setting>value</setting></config>')
    
    print("Created executable_files.kmz with various executable files")
    print("This file should be rejected by the security validation")

if __name__ == "__main__":
    create_executable_kmz()
