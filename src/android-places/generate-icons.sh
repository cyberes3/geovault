#!/bin/bash
# icon.jpg → mipmaps + adaptive icons. Optional icon-monochrome.png → <monochrome> (same 285→432² layout as round foreground).
# PNG output is deterministic (SOURCE_DATE_EPOCH, single-thread IM, fixed zlib + no date/time chunks).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGO_PATH="$SCRIPT_DIR/icon.jpg"
RES_DIR="$SCRIPT_DIR/app/src/main/res"
ADAPTIVE_ICON_DIR="$RES_DIR/mipmap-anydpi-v26"
COLORS_FILE="$SCRIPT_DIR/../android-common/src/main/res/values/colors.xml"

if [ ! -f "$LOGO_PATH" ]; then
    echo "Error: Logo not found at $LOGO_PATH"
    exit 1
fi

BG_COLOR="#163D8A"
if [ -f "$COLORS_FILE" ]; then
    TOKEN_HEX="$(rg -o '<color name="gv_common_main_blue">#[0-9A-Fa-f]{6}</color>' "$COLORS_FILE" | sed -E 's/.*>#([0-9A-Fa-f]{6})<.*/\1/' | sed -n '1p' || true)"
    if [[ "$TOKEN_HEX" =~ ^[0-9A-Fa-f]{6}$ ]]; then
        BG_COLOR="#${TOKEN_HEX}"
    fi
fi

if command -v magick >/dev/null 2>&1; then
    im() { magick "$@"; }
elif command -v convert >/dev/null 2>&1; then
    im() { convert "$@"; }
else
    echo "Error: ImageMagick not found (magick or convert)."
    exit 1
fi

# Deterministic PNG bytes (same inputs → identical files; respects SOURCE_DATE_EPOCH if set).
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-946684800}"
export MAGICK_THREAD_LIMIT="${MAGICK_THREAD_LIMIT:-1}"
IM_PNG="-strip -define png:compression-level=9 -define png:exclude-chunks=date,time"

echo "Generating launcher icons from $LOGO_PATH..."

mkdir -p \
    "$RES_DIR/drawable" \
    "$RES_DIR/drawable-nodpi" \
    "$RES_DIR/raw" \
    "$RES_DIR/mipmap-mdpi" \
    "$RES_DIR/mipmap-hdpi" \
    "$RES_DIR/mipmap-xhdpi" \
    "$RES_DIR/mipmap-xxhdpi" \
    "$RES_DIR/mipmap-xxxhdpi" \
    "$ADAPTIVE_ICON_DIR"

for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    rm -f "$RES_DIR/mipmap-${density}/ic_launcher.webp" "$RES_DIR/mipmap-${density}/ic_launcher_round.webp"
done

for spec in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
    density="${spec%%:*}"
    size="${spec##*:}"
    im "$LOGO_PATH" -resize "${size}x${size}" -background "$BG_COLOR" -gravity center -extent "${size}x${size}" $IM_PNG \
        "$RES_DIR/mipmap-${density}/ic_launcher.png"
    im "$LOGO_PATH" -resize "${size}x${size}" -background "$BG_COLOR" -gravity center -extent "${size}x${size}" $IM_PNG \
        "$RES_DIR/mipmap-${density}/ic_launcher_round.png"
done

im "$LOGO_PATH" -resize 432x432 -background transparent -gravity center -extent 432x432 $IM_PNG \
    "$RES_DIR/drawable/ic_launcher_foreground.png"

ROUND_SIZE=285
im "$LOGO_PATH" -resize "${ROUND_SIZE}x${ROUND_SIZE}" -background transparent -gravity center -extent 432x432 $IM_PNG \
    "$RES_DIR/drawable/ic_launcher_foreground_round.png"

MONO_SRC="$SCRIPT_DIR/icon-monochrome.png"
MONO_LINE=""
if [ -f "$MONO_SRC" ]; then
    mkdir -p "$RES_DIR/drawable-nodpi"
    im "$MONO_SRC" -resize "${ROUND_SIZE}x${ROUND_SIZE}" -background transparent -gravity center -extent 432x432 \
        -type TrueColorAlpha $IM_PNG "PNG32:$RES_DIR/drawable-nodpi/ic_launcher_monochrome.png"
    MONO_LINE='    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />'
else
    rm -f "$RES_DIR/drawable-nodpi/ic_launcher_monochrome.png" 2>/dev/null || true
fi

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

for adaptive_name in ic_launcher.xml ic_launcher_round.xml; do
    cat > "$ADAPTIVE_ICON_DIR/$adaptive_name" << EOF
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_round" />
${MONO_LINE}
</adaptive-icon>
EOF
done

rm -f \
    "$RES_DIR/drawable/ic_launcher_foreground.xml" \
    "$RES_DIR/drawable/ic_launcher_foreground_png.png" \
    "$RES_DIR/drawable/ic_launcher_background_png.png" \
    "$RES_DIR/drawable-nodpi/ic_launcher_monochrome_mask.png" \
    "$RES_DIR/raw/ic_launcher_monochrome.svg" \
    "$RES_DIR/mipmap-xxxhdpi/ic_launcher_foreground.png" \
    "$RES_DIR/mipmap-anydpi/ic_launcher.xml" \
    "$RES_DIR/mipmap-anydpi/ic_launcher_round.xml" \
    2>/dev/null || true

echo "Done."
