#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const {kml, gpx} = require('@tmcw/togeojson');
const {DOMParser} = require('@xmldom/xmldom');

/**
 * Main KML/GPX to GeoJSON converter module
 * Provides functions for converting KML/KMZ/GPX content to GeoJSON
 */

/**
 * Convert KML content string to GeoJSON
 * @param {string} kmlContent - KML content as string
 * @returns {Object} GeoJSON FeatureCollection
 */
function convertKmlContent(kmlContent) {
    try {
        // Remove BOM (Byte Order Mark) if present
        if (kmlContent.charCodeAt(0) === 0xFEFF) {
            kmlContent = kmlContent.slice(1);
        }

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
        throw new Error(`Failed to convert KML content: ${error.message}`);
    }
}

/**
 * Convert GPX content string to GeoJSON
 * @param {string} gpxContent - GPX content as string
 * @returns {Object} GeoJSON FeatureCollection
 */
function convertGpxContent(gpxContent) {
    try {
        // Remove BOM (Byte Order Mark) if present
        if (gpxContent.charCodeAt(0) === 0xFEFF) {
            gpxContent = gpxContent.slice(1);
        }

        // Parse the GPX content
        const parser = new DOMParser();
        const gpxDoc = parser.parseFromString(gpxContent, 'text/xml');

        // Check for parsing errors
        const parseError = gpxDoc.getElementsByTagName('parsererror');
        if (parseError.length > 0) {
            throw new Error(`XML parsing error: ${parseError[0].textContent}`);
        }

        // Convert to GeoJSON
        const geojson = gpx(gpxDoc);

        return geojson;
    } catch (error) {
        throw new Error(`Failed to convert GPX content: ${error.message}`);
    }
}


/**
 * File type configuration (should match backend file_types.py)
 * Note: KMZ is handled in Python before reaching this converter
 */
const FILE_TYPE_CONFIGS = {
    kml: {
        extensions: ['.kml'],
        signatures: ['<?xml', '<kml', '<KML'],
        xmlRoot: 'kml'
    },
    gpx: {
        extensions: ['.gpx'],
        signatures: ['<?xml', '<gpx', '<GPX'],
        xmlRoot: 'gpx'
    }
};

/**
 * Detect file type based on content and extension
 * @param {Buffer} content - File content
 * @param {string} filePath - File path for extension detection
 * @returns {string} File type ('kml', 'kmz', 'gpx')
 */
function detectFileType(content, filePath) {
    const ext = path.extname(filePath).toLowerCase();

    // Check file extension first
    for (const [type, config] of Object.entries(FILE_TYPE_CONFIGS)) {
        if (config.extensions.includes(ext)) {
            return type;
        }
    }

    // Check content signatures
    for (const [type, config] of Object.entries(FILE_TYPE_CONFIGS)) {
        if (config.signatures.some(sig => {
            if (typeof sig === 'string') {
                return content.toString('utf8').toLowerCase().includes(sig.toLowerCase());
            } else {
                // Binary signature
                return content[0] === sig[0] && content[1] === sig[1];
            }
        })) {
            return type;
        }
    }

    // Default to KML
    return 'kml';
}

/**
 * Convert file to GeoJSON
 * @param {string} filePath - Path to KML, KMZ, or GPX file
 * @returns {Object} GeoJSON FeatureCollection
 */
function convertFile(filePath) {
    try {
        if (!fs.existsSync(filePath)) {
            throw new Error(`File does not exist: ${filePath}`);
        }

        const content = fs.readFileSync(filePath);
        const fileType = detectFileType(content, filePath);

        switch (fileType) {
            case 'gpx':
                const gpxContent = content.toString('utf8');
                return convertGpxContent(gpxContent);
            case 'kml':
                const kmlContent = content.toString('utf8');
                return convertKmlContent(kmlContent);
            default:
                throw new Error(`Unsupported file type: ${fileType}`);
        }
    } catch (error) {
        throw new Error(`Failed to convert file: ${error.message}`);
    }
}

/**
 * Process command line input for direct usage
 * Reads from stdin if no arguments provided
 */
function processInput() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        // Read from stdin
        let input = '';
        process.stdin.setEncoding('utf8');

        process.stdin.on('data', (chunk) => {
            input += chunk;
        });

        process.stdin.on('end', () => {
            try {
                const geojson = convertKmlContent(input.trim());
                console.log(JSON.stringify(geojson));
            } catch (error) {
                console.error(`Error: ${error.message}`);
                process.exit(1);
            }
        });
    } else {
        // Process file
        const filePath = args[0];
        try {
            const geojson = convertFile(filePath);
            console.log(JSON.stringify(geojson));
        } catch (error) {
            console.error(`Error: ${error.message}`);
            process.exit(1);
        }
    }
}

// Export functions for use as module
module.exports = {
    convertKmlContent,
    convertGpxContent,
    convertFile,
    detectFileType
};

// If run directly, process input
if (require.main === module) {
    processInput();
}
