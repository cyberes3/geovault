#!/bin/bash

# Script to download OSM planet file for Overpass API
# Downloads PBF, converts to XML, and compresses to bz2
# Usage: ./download-planet.sh

set -e

DOWNLOAD_DIR="/srv/docker-data/overpass"
PLANET_URL="https://download.geofabrik.de/north-america/us-latest.osm.pbf"
PLANET_PBF="${DOWNLOAD_DIR}/planet.osm.pbf"
PLANET_XML="${DOWNLOAD_DIR}/planet.osm"
PLANET_BZ2="${DOWNLOAD_DIR}/planet.osm.bz2"

if ! command -v osmium &> /dev/null; then
    echo "Error: osmium-tool is not installed."
    echo "Please install it: apt-get install osmium-tool"
    exit 1
fi

echo "Creating download directory if it doesn't exist..."
mkdir -p "${DOWNLOAD_DIR}"

echo "Downloading planet file from ${PLANET_URL}..."
echo "This may take a while depending on your internet connection..."
echo "File will be saved to: ${PLANET_PBF}"

# Download with resume support in case of interruption
wget -c -O "${PLANET_PBF}" "${PLANET_URL}"

if [ ! -f "${PLANET_PBF}" ]; then
    echo "Error: Download failed!"
    exit 1
fi

PBF_SIZE=$(du -h "${PLANET_PBF}" | cut -f1)
echo "Download complete!"
echo "PBF file size: ${PBF_SIZE}"

echo "Converting PBF to XML format..."
echo "This may take a while..."

osmium cat "${PLANET_PBF}" -o "${PLANET_XML}" --overwrite

if [ ! -f "${PLANET_XML}" ]; then
    echo "Error: Conversion to XML failed!"
    exit 1
fi

XML_SIZE=$(du -h "${PLANET_XML}" | cut -f1)
echo "Conversion complete!"
echo "XML file size: ${XML_SIZE}"

echo "Compressing XML to bz2 format..."
echo "This may take a while..."

bzip2 -c "${PLANET_XML}" > "${PLANET_BZ2}"

if [ ! -f "${PLANET_BZ2}" ]; then
    echo "Error: Compression to bz2 failed!"
    exit 1
fi

BZ2_SIZE=$(du -h "${PLANET_BZ2}" | cut -f1)
echo "Compression complete!"
echo "BZ2 file size: ${BZ2_SIZE}"

# Clean up intermediate files
echo "Cleaning up intermediate files..."
rm -f "${PLANET_PBF}" "${PLANET_XML}"

echo ""
echo "=========================================="
echo "Planet file processing complete!"
echo "Final file: ${PLANET_BZ2}"
echo "Final size: ${BZ2_SIZE}"
echo "=========================================="


