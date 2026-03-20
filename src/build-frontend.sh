#!/bin/bash
# Build script for GeoVault frontend and all extension frontends

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTENSIONS_DIR="$SCRIPT_DIR/backend/extensions"

echo "=========================================="
echo "Building GeoVault Frontends"
echo "=========================================="

# Build main frontend
echo ""
echo "Building main frontend..."
cd "$SCRIPT_DIR/frontend"
if [ ! -f "package.json" ]; then
    echo "Error: package.json not found in frontend directory"
    exit 1
fi

# Install dependencies
echo "Installing frontend dependencies..."
npm install
npm audit fix

echo "Running frontend build..."
npm run build
echo "✓ Main frontend built successfully"

# Build extension frontends
echo ""
echo "Building extension frontends..."
cd "$EXTENSIONS_DIR"

# Find all extensions with frontend directories
EXTENSIONS_BUILT=0
for EXT_DIR in */; do
    EXT_NAME="${EXT_DIR%/}"
    FRONTEND_DIR="$EXTENSIONS_DIR/$EXT_NAME/src/frontend"
    
    if [ -d "$FRONTEND_DIR" ] && [ -f "$FRONTEND_DIR/package.json" ]; then
        echo ""
        echo "Building extension: $EXT_NAME"
        cd "$FRONTEND_DIR"
        
        # Install dependencies
        echo "  Installing dependencies..."
        npm install
        npm audit fix
        
        echo "  Running build..."
        npm run build
        echo "  ✓ Extension '$EXT_NAME' built successfully"
        EXTENSIONS_BUILT=$((EXTENSIONS_BUILT + 1))
    fi
done

echo ""
echo "=========================================="
echo "Build complete!"
echo "  - Main frontend: ✓"
echo "  - Extensions built: $EXTENSIONS_BUILT"
echo "=========================================="
