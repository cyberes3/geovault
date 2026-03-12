#!/bin/bash
# Generate Android launcher icons from the app icon image

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGO_PATH="$SCRIPT_DIR/icon.jpg"
RES_DIR="$SCRIPT_DIR/app/src/main/res"

# Primary blue color from colors.xml (used for legacy icon padding if needed)
BG_COLOR="#163D8A"

if [ ! -f "$LOGO_PATH" ]; then
    echo "Error: Logo not found at $LOGO_PATH"
    exit 1
fi

if ! command -v convert &> /dev/null; then
    echo "Error: ImageMagick 'convert' command not found. Please install ImageMagick."
    exit 1
fi

echo "Generating Android launcher icons from logo..."

# Remove old .webp icons if they exist
echo "Removing old .webp icons..."
find "$RES_DIR/mipmap-"* -name "*.webp" -type f -delete 2>/dev/null || true

# Generate standard launcher icons for different densities
echo "Generating standard launcher icons..."
# mdpi: 48x48
convert "$LOGO_PATH" -resize 48x48 -background "$BG_COLOR" -gravity center -extent 48x48 "$RES_DIR/mipmap-mdpi/ic_launcher.png"
convert "$LOGO_PATH" -resize 48x48 -background "$BG_COLOR" -gravity center -extent 48x48 "$RES_DIR/mipmap-mdpi/ic_launcher_round.png"

# hdpi: 72x72
convert "$LOGO_PATH" -resize 72x72 -background "$BG_COLOR" -gravity center -extent 72x72 "$RES_DIR/mipmap-hdpi/ic_launcher.png"
convert "$LOGO_PATH" -resize 72x72 -background "$BG_COLOR" -gravity center -extent 72x72 "$RES_DIR/mipmap-hdpi/ic_launcher_round.png"

# xhdpi: 96x96
convert "$LOGO_PATH" -resize 96x96 -background "$BG_COLOR" -gravity center -extent 96x96 "$RES_DIR/mipmap-xhdpi/ic_launcher.png"
convert "$LOGO_PATH" -resize 96x96 -background "$BG_COLOR" -gravity center -extent 96x96 "$RES_DIR/mipmap-xhdpi/ic_launcher_round.png"

# xxhdpi: 144x144
convert "$LOGO_PATH" -resize 144x144 -background "$BG_COLOR" -gravity center -extent 144x144 "$RES_DIR/mipmap-xxhdpi/ic_launcher.png"
convert "$LOGO_PATH" -resize 144x144 -background "$BG_COLOR" -gravity center -extent 144x144 "$RES_DIR/mipmap-xxhdpi/ic_launcher_round.png"

# xxxhdpi: 192x192
convert "$LOGO_PATH" -resize 192x192 -background "$BG_COLOR" -gravity center -extent 192x192 "$RES_DIR/mipmap-xxxhdpi/ic_launcher.png"
convert "$LOGO_PATH" -resize 192x192 -background "$BG_COLOR" -gravity center -extent 192x192 "$RES_DIR/mipmap-xxxhdpi/ic_launcher_round.png"

# Ensure drawable directory exists
mkdir -p "$RES_DIR/drawable"

# Generate adaptive icon foreground (108x108 dp = 432x432 px for xxxhdpi)
# Full-bleed foreground for squircle/rounded-square masks
echo "Generating adaptive icon foreground..."
convert "$LOGO_PATH" -resize 432x432 -background transparent -gravity center -extent 432x432 "$RES_DIR/drawable/ic_launcher_foreground.png"

# Round-only foreground for Pixel and other 100% circular launchers:
# scale to 66% (safe zone) so the full icon fits inside the circle with no cropping
echo "Generating round (circular) adaptive icon foreground..."
ROUND_SIZE=285
convert "$LOGO_PATH" -resize "${ROUND_SIZE}x${ROUND_SIZE}" -background transparent -gravity center -extent 432x432 "$RES_DIR/drawable/ic_launcher_foreground_round.png"

# Generate adaptive icon background XML (vector drawable for solid color)
echo "Generating adaptive icon background..."
cat > "$RES_DIR/drawable/ic_launcher_background.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#163D8A"
        android:pathData="M0,0h108v108h-108z" />
</vector>
EOF

# Verify adaptive icon XML files exist and reference correct drawables
echo "Verifying adaptive icon XML files..."
ADAPTIVE_ICON_DIR="$RES_DIR/mipmap-anydpi-v26"
mkdir -p "$ADAPTIVE_ICON_DIR"

# Main adaptive icon: use circle foreground so icon looks correct on Samsung (circle) and all launchers
cat > "$ADAPTIVE_ICON_DIR/ic_launcher.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_round" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground_round" />
</adaptive-icon>
EOF

# Create ic_launcher_round.xml with round-specific foreground (for Pixel and other circular launchers)
cat > "$ADAPTIVE_ICON_DIR/ic_launcher_round.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_round" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground_round" />
</adaptive-icon>
EOF

# Clean up any temporary files and duplicates
echo "Cleaning up temporary files and duplicates..."
# Remove XML file if it exists (we use PNG directly)
rm -f "$RES_DIR/drawable/ic_launcher_foreground.xml" 2>/dev/null || true
# Remove any temporary files
rm -f "$RES_DIR/drawable/ic_launcher_foreground_png.png" \
      "$RES_DIR/drawable/ic_launcher_background_png.png" \
      "$RES_DIR/mipmap-xxxhdpi/ic_launcher_foreground.png" 2>/dev/null || true

echo ""
echo "Icons generated successfully!"
echo "  - Standard icons: mipmap-*/ic_launcher.png and ic_launcher_round.png"
echo "  - Adaptive icon foreground: drawable/ic_launcher_foreground.png"
echo "  - Round (circular) foreground: drawable/ic_launcher_foreground_round.png"
echo "  - Adaptive icon background: drawable/ic_launcher_background.xml"
echo "  - Adaptive icon configs: mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml"

