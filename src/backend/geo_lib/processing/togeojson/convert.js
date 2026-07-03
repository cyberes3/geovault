#!/usr/bin/env node

const fs = require('fs');
const {kml} = require('@tmcw/togeojson');
const {DOMParser} = require('@xmldom/xmldom');
const {stripDoctype} = require('./index.js');

/**
 * Convert KML file to GeoJSON
 * Usage: node convert.js <input_file> [output_file]
 * If output_file is not provided, outputs to stdout
 * Note: KMZ files should be extracted to KML before using this tool
 */

function convertKmlToGeojson(inputPath) {
    try {
        // Read KML content
        const content = fs.readFileSync(inputPath);
        const kmlContent = content.toString('utf8');

        // Remove BOM (Byte Order Mark) if present
        let cleanKmlContent = kmlContent;
        if (kmlContent && kmlContent.charCodeAt(0) === 0xFEFF) {
            cleanKmlContent = kmlContent.slice(1);
        }

        cleanKmlContent = stripDoctype(cleanKmlContent);

        // Parse the KML content
        const parser = new DOMParser();
        const kmlDoc = parser.parseFromString(cleanKmlContent, 'text/xml');

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
        console.error('  input_file: Path to KML file');
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

module.exports = {convertKmlToGeojson};
