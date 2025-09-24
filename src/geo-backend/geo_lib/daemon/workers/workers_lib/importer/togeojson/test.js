#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { convertKmlContent, convertFile } = require('./index.js');

/**
 * Test script for the KML converter
 */

function runTests() {
    console.log('Testing KML to GeoJSON converter...\n');
    
    // Test with sample KML content
    const sampleKml = `<?xml version="1.0"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Test Point</name>
      <description>A test point</description>
      <Point>
        <coordinates>-103.23321,40.18681,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>`;
    
    try {
        console.log('1. Testing KML content conversion...');
        const result = convertKmlContent(sampleKml);
        
        if (result.type === 'FeatureCollection' && result.features.length > 0) {
            console.log('   ✓ KML content conversion successful');
            console.log(`   ✓ Generated ${result.features.length} feature(s)`);
        } else {
            console.log('   ✗ KML content conversion failed - invalid result structure');
        }
    } catch (error) {
        console.log(`   ✗ KML content conversion failed: ${error.message}`);
    }
    
    // Test with file if available
    const testKmlPath = path.join(__dirname, '../../../../../../tests/Test Items.kml');
    if (fs.existsSync(testKmlPath)) {
        try {
            console.log('\n2. Testing file conversion...');
            const result = convertFile(testKmlPath);
            
            if (result.type === 'FeatureCollection' && result.features.length > 0) {
                console.log('   ✓ File conversion successful');
                console.log(`   ✓ Generated ${result.features.length} feature(s)`);
                
                // Show sample feature
                if (result.features[0]) {
                    const feature = result.features[0];
                    console.log(`   ✓ Sample feature: ${feature.properties?.name || 'Unnamed'} (${feature.geometry?.type})`);
                }
            } else {
                console.log('   ✗ File conversion failed - invalid result structure');
            }
        } catch (error) {
            console.log(`   ✗ File conversion failed: ${error.message}`);
        }
    } else {
        console.log('\n2. Skipping file test - test KML file not found');
    }
    
    console.log('\nTest completed.');
}

// Run tests if this script is executed directly
if (require.main === module) {
    runTests();
}

module.exports = { runTests };
