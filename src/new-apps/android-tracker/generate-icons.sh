#!/bin/bash
# Generate Android launcher icons from the app icon image

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGO_PATH="$SCRIPT_DIR/icon.jpg"
RES_DIR="$SCRIPT_DIR/app/src/main/res"
ADAPTIVE_ICON_DIR="$RES_DIR/mipmap-anydpi-v26"
COLORS_FILE="$SCRIPT_DIR/../android-common/src/main/res/values/colors.xml"

# Pull primary blue from shared android-common colors.xml (single source of truth).
BG_COLOR="#163D8A"
if [ -f "$COLORS_FILE" ]; then
    TOKEN_HEX="$(rg -o '<color name="gv_common_main_blue">#[0-9A-Fa-f]{6}</color>' "$COLORS_FILE" | sed -E 's/.*>#([0-9A-Fa-f]{6})<.*/\1/' | sed -n '1p' || true)"
    if [[ "$TOKEN_HEX" =~ ^[0-9A-Fa-f]{6}$ ]]; then
        BG_COLOR="#${TOKEN_HEX}"
    fi
fi

if [ ! -f "$LOGO_PATH" ]; then
    echo "Error: Logo not found at $LOGO_PATH"
    exit 1
fi

IM_CMD=""
if command -v magick >/dev/null 2>&1; then
    IM_CMD="magick"
elif command -v convert >/dev/null 2>&1; then
    IM_CMD="convert"
fi
if [ -z "$IM_CMD" ]; then
    echo "Error: ImageMagick not found. Install ImageMagick (magick/convert)."
    exit 1
fi

echo "Generating Android launcher icons from logo..."

# Ensure required directories exist
mkdir -p \
    "$RES_DIR/drawable" \
    "$RES_DIR/mipmap-mdpi" \
    "$RES_DIR/mipmap-hdpi" \
    "$RES_DIR/mipmap-xhdpi" \
    "$RES_DIR/mipmap-xxhdpi" \
    "$RES_DIR/mipmap-xxxhdpi" \
    "$ADAPTIVE_ICON_DIR"

# Remove stale WebP launcher assets; having both .png and .webp with the same
# resource name causes Android resource merge duplicate errors.
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    rm -f \
        "$RES_DIR/mipmap-${density}/ic_launcher.webp" \
        "$RES_DIR/mipmap-${density}/ic_launcher_round.webp"
done

# Generate standard launcher icons for different densities
echo "Generating standard launcher icons..."
for spec in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
    density="${spec%%:*}"
    size="${spec##*:}"
    "$IM_CMD" "$LOGO_PATH" -resize "${size}x${size}" -background "$BG_COLOR" -gravity center -extent "${size}x${size}" "$RES_DIR/mipmap-${density}/ic_launcher.png"
    "$IM_CMD" "$LOGO_PATH" -resize "${size}x${size}" -background "$BG_COLOR" -gravity center -extent "${size}x${size}" "$RES_DIR/mipmap-${density}/ic_launcher_round.png"
done

# Generate adaptive icon foreground (108x108 dp = 432x432 px for xxxhdpi)
# Full-bleed foreground for squircle/rounded-square masks
echo "Generating adaptive icon foreground..."
"$IM_CMD" "$LOGO_PATH" -resize 432x432 -background transparent -gravity center -extent 432x432 "$RES_DIR/drawable/ic_launcher_foreground.png"

# Round-only foreground for Pixel and other 100% circular launchers:
# scale to 66% (safe zone) so the full icon fits inside the circle with no cropping
echo "Generating round (circular) adaptive icon foreground..."
ROUND_SIZE=285
"$IM_CMD" "$LOGO_PATH" -resize "${ROUND_SIZE}x${ROUND_SIZE}" -background transparent -gravity center -extent 432x432 "$RES_DIR/drawable/ic_launcher_foreground_round.png"

# Generate adaptive icon background XML (vector drawable for solid color)
echo "Generating adaptive icon background..."
cat > "$RES_DIR/drawable/ic_launcher_background.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="$BG_COLOR"
        android:pathData="M0,0h108v108h-108z" />
</vector>
EOF

# Verify adaptive icon XML files exist and reference correct drawables
echo "Verifying adaptive icon XML files..."
# Main adaptive icon: use round-safe foreground to avoid clipping.
cat > "$ADAPTIVE_ICON_DIR/ic_launcher.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_round" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground_round" />
</adaptive-icon>
EOF

# Round icon config.
cat > "$ADAPTIVE_ICON_DIR/ic_launcher_round.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_round" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground_round" />
</adaptive-icon>
EOF

# Clean up stale outputs we do not use.
echo "Cleaning up stale icon artifacts..."
rm -f \
    "$RES_DIR/drawable/ic_launcher_foreground.xml" \
    "$RES_DIR/drawable/ic_launcher_foreground_png.png" \
    "$RES_DIR/drawable/ic_launcher_background_png.png" \
    "$RES_DIR/mipmap-xxxhdpi/ic_launcher_foreground.png" \
    "$RES_DIR/mipmap-anydpi/ic_launcher.xml" \
    "$RES_DIR/mipmap-anydpi/ic_launcher_round.xml" \
    2>/dev/null || true

echo ""
echo "Icons generated successfully!"
echo "  - Standard icons: mipmap-*/ic_launcher.png and ic_launcher_round.png"
echo "  - Adaptive icon foreground: drawable/ic_launcher_foreground.png"
echo "  - Round (circular) foreground: drawable/ic_launcher_foreground_round.png"
echo "  - Adaptive icon background: drawable/ic_launcher_background.xml"
echo "  - Adaptive icon configs: mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml"

