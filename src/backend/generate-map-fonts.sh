#!/bin/bash

# Script to download and generate MapLibre font glyphs
# Downloads OpenMapTiles fonts repo, generates PBF glyphs, and stores them in static files directory

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
REPO_URL="https://github.com/openmaptiles/fonts.git"
TMP_DIR="/tmp/openmaptiles-fonts"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/assets/fonts"

echo -e "${GREEN}Downloading and generating MapLibre font glyphs...${NC}"

# Clean up any existing temp directory
if [ -d "$TMP_DIR" ]; then
    echo -e "${YELLOW}Removing existing temp directory...${NC}"
    rm -rf "$TMP_DIR"
fi

# Clone the repository
echo -e "${GREEN}Cloning fonts repository to ${TMP_DIR}...${NC}"
git clone "$REPO_URL" "$TMP_DIR" || {
    echo -e "${RED}Failed to clone repository${NC}"
    exit 1
}

# Navigate to the repo directory
cd "$TMP_DIR"

# Install npm dependencies
echo -e "${GREEN}Installing npm dependencies...${NC}"
if ! npm install; then
    echo -e "${RED}Failed to install npm dependencies${NC}"
    exit 1
fi

# Generate fonts
echo -e "${GREEN}Generating font glyphs (this may take a while)...${NC}"
if ! node ./generate.js; then
    echo -e "${RED}Failed to generate fonts${NC}"
    exit 1
fi

# Check if _output directory was created
if [ ! -d "_output" ]; then
    echo -e "${RED}Font generation failed: _output directory not found${NC}"
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# Copy generated fonts to assets directory
echo -e "${GREEN}Copying generated fonts to ${OUTPUT_DIR}...${NC}"
if ! cp -r _output/* "$OUTPUT_DIR/"; then
    echo -e "${RED}Failed to copy fonts to output directory${NC}"
    exit 1
fi

# Clean up temp directory
echo -e "${GREEN}Cleaning up temp directory...${NC}"
rm -rf "$TMP_DIR"

echo -e "${GREEN}✓ Font glyphs successfully generated and stored in ${OUTPUT_DIR}${NC}"

