#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { kml } = require('@tmcw/togeojson');
const { DOMParser } = require('@xmldom/xmldom');

/**
 * Convert KML/KMZ file to GeoJSON
 * Usage: node convert.js <input_file> [output_file]
 * If output_file is not provided, outputs to stdout
 */

function kmzToKml(filePath) {
    const content = fs.readFileSync(filePath);
    
    // Check if it's a KMZ file (ZIP format)
    if (content[0] === 0x50 && content[1] === 0x4B) { // ZIP signature
        const AdmZip = require('adm-zip');
        const zip = new AdmZip(content);
        const entries = zip.getEntries();
        
        // Find the first .kml file
        const kmlEntry = entries.find(entry => entry.entryName.toLowerCase().endsWith('.kml'));
        if (kmlEntry) {
            return kmlEntry.getData().toString('utf8');
        }
    }
    
    // If not KMZ or no KML found, treat as regular KML
    return content.toString('utf8');
}

function convertKmlToGeojson(inputPath) {
    try {
        // Read and convert KMZ to KML if needed
        const kmlContent = kmzToKml(inputPath);
        
        // Parse the KML content
        const parser = new DOMParser();
        const kmlDoc = parser.parseFromString(kmlContent, 'text/xml');
        
        // Check for parsing errors
        const parseError = kmlDoc.getElementsByTagName('parsererror');
        if (parseError.length > 0) {
            throw new Error(`XML parsing error: ${parseError[0].textContent}`);
        }
        
        // Convert to GeoJSON
        const geojson = kml(kmlDoc);
        
        return geojson;
    } catch (error) {
        throw new Error(`Failed to convert KML: ${error.message}`);
    }
}

// Main execution
if (require.main === module) {
    const args = process.argv.slice(2);
    
    if (args.length === 0) {
        console.error('Usage: node convert.js <input_file> [output_file]');
        console.error('  input_file: Path to KML or KMZ file');
        console.error('  output_file: Optional output file path (defaults to stdout)');
        process.exit(1);
    }
    
    const inputPath = args[0];
    const outputPath = args[1];
    
    // Check if input file exists
    if (!fs.existsSync(inputPath)) {
        console.error(`Error: Input file '${inputPath}' does not exist`);
        process.exit(1);
    }
    
    try {
        const geojson = convertKmlToGeojson(inputPath);
        const output = JSON.stringify(geojson, null, 2);
        
        if (outputPath) {
            fs.writeFileSync(outputPath, output);
            console.log(`GeoJSON written to: ${outputPath}`);
        } else {
            console.log(output);
        }
    } catch (error) {
        console.error(`Error: ${error.message}`);
        process.exit(1);
    }
}

module.exports = { convertKmlToGeojson, kmzToKml };
