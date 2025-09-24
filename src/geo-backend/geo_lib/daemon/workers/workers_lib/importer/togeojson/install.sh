#!/bin/bash

# GeoServer KML Importer Installation Script
# This script sets up the Node.js environment and dependencies for KML to GeoJSON conversion

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

print_status "Starting GeoServer KML Importer installation..."
print_status "Installation directory: $PROJECT_DIR"

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    print_error "Node.js is not installed. Please install Node.js 16 or higher."
    print_status "You can install Node.js from: https://nodejs.org/"
    exit 1
fi

# Check Node.js version
NODE_VERSION=$(node --version | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 16 ]; then
    print_error "Node.js version 16 or higher is required. Current version: $(node --version)"
    exit 1
fi

print_success "Node.js $(node --version) is installed"

# Check if npm is installed
if ! command -v npm &> /dev/null; then
    print_error "npm is not installed. Please install npm."
    exit 1
fi

print_success "npm $(npm --version) is installed"

# Navigate to project directory
cd "$PROJECT_DIR"

# Check if package.json exists
if [ ! -f "package.json" ]; then
    print_error "package.json not found in $PROJECT_DIR"
    exit 1
fi

print_status "Installing Node.js dependencies..."

# Install dependencies
if npm install; then
    print_success "Dependencies installed successfully"
else
    print_error "Failed to install dependencies"
    exit 1
fi

# Make scripts executable
chmod +x index.js convert.js test.js

print_status "Making scripts executable..."

# Test the installation
print_status "Testing installation..."

if node test.js; then
    print_success "Installation test passed"
else
    print_warning "Installation test failed, but dependencies are installed"
fi

# Create a simple usage example
cat > usage_example.sh << 'EOF'
#!/bin/bash

# Example usage of the KML to GeoJSON converter

# Convert a KML file to GeoJSON and output to stdout
echo "Converting KML file to GeoJSON..."
node index.js /path/to/your/file.kml

# Convert a KML file and save to output file
echo "Converting KML file and saving to output..."
node convert.js /path/to/your/file.kml /path/to/output.geojson

# Convert KML content from stdin
echo "Converting KML from stdin..."
echo '<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>Test</name><Point><coordinates>-103.23321,40.18681,0</coordinates></Point></Placemark></Document></kml>' | node index.js
EOF

chmod +x usage_example.sh

print_success "Installation completed successfully!"
print_status ""
print_status "Usage examples:"
print_status "  Convert KML file:     node index.js /path/to/file.kml"
print_status "  Convert with output:  node convert.js /path/to/file.kml /path/to/output.geojson"
print_status "  Test installation:    node test.js"
print_status "  See examples:         cat usage_example.sh"
print_status ""
print_status "The converter supports both KML and KMZ files."
print_status "For integration with Python, use subprocess to call these Node.js scripts."
