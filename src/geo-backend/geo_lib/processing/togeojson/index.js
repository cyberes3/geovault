#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { kml } = require('@tmcw/togeojson');
const { DOMParser } = require('@xmldom/xmldom');

/**
 * Main KML to GeoJSON converter module
 * Provides functions for converting KML/KMZ content to GeoJSON
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
 * Convert KMZ content buffer to GeoJSON
 * @param {Buffer} kmzBuffer - KMZ file content as buffer
 * @returns {Object} GeoJSON FeatureCollection
 */
function convertKmzContent(kmzBuffer) {
    try {
        // Check if it's a KMZ file (ZIP format)
        if (kmzBuffer[0] === 0x50 && kmzBuffer[1] === 0x4B) { // ZIP signature
            const AdmZip = require('adm-zip');
            const zip = new AdmZip(kmzBuffer);
            const entries = zip.getEntries();
            
            // Find the first .kml file
            const kmlEntry = entries.find(entry => entry.entryName.toLowerCase().endsWith('.kml'));
            if (kmlEntry) {
                const kmlContent = kmlEntry.getData().toString('utf8');
                return convertKmlContent(kmlContent);
            } else {
                throw new Error('No KML file found in KMZ archive');
            }
        } else {
            // Treat as regular KML
            const kmlContent = kmzBuffer.toString('utf8');
            return convertKmlContent(kmlContent);
        }
    } catch (error) {
        throw new Error(`Failed to convert KMZ content: ${error.message}`);
    }
}

/**
 * Convert file to GeoJSON
 * @param {string} filePath - Path to KML or KMZ file
 * @returns {Object} GeoJSON FeatureCollection
 */
function convertFile(filePath) {
    try {
        if (!fs.existsSync(filePath)) {
            throw new Error(`File does not exist: ${filePath}`);
        }
        
        const content = fs.readFileSync(filePath);
        
        // Try to determine if it's KMZ or KML
        if (content[0] === 0x50 && content[1] === 0x4B) { // ZIP signature
            return convertKmzContent(content);
        } else {
            const kmlContent = content.toString('utf8');
            return convertKmlContent(kmlContent);
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
    convertKmzContent,
    convertFile
};

// If run directly, process input
if (require.main === module) {
    processInput();
}
