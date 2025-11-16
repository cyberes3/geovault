#!/bin/bash

# Script to download OSM planet file for Overpass API
# Usage: ./download-planet.sh

set -e

DOWNLOAD_DIR="/srv/docker-data/overpass"
PLANET_URL="https://download.geofabrik.de/north-america/us-latest.osm.pbf"
PLANET_FILE="${DOWNLOAD_DIR}/planet.osm.pbf"

echo "Creating download directory if it doesn't exist..."
mkdir -p "${DOWNLOAD_DIR}"

echo "Downloading planet file from ${PLANET_URL}..."
echo "This may take a while depending on your internet connection..."
echo "File will be saved to: ${PLANET_FILE}"

# Download with resume support in case of interruption
wget -c -O "${PLANET_FILE}" "${PLANET_URL}" || {
    echo "Download failed. Trying with curl..."
    curl -L -o "${PLANET_FILE}" "${PLANET_URL}"
}

if [ -f "${PLANET_FILE}" ]; then
    FILE_SIZE=$(du -h "${PLANET_FILE}" | cut -f1)
    echo "Download complete!"
    echo "File size: ${FILE_SIZE}"
    echo "File location: ${PLANET_FILE}"
else
    echo "Error: Download failed!"
    exit 1
fi

