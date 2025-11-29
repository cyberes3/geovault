#!/bin/bash
# Build script for GeoVault Android Uploader app

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/src/android"

cd "$ANDROID_DIR"

# Ensure Gradle wrapper is executable
if [ -f "gradlew" ]; then
    chmod +x gradlew
fi

# Build the debug APK
echo "Building Android app..."
./gradlew assembleDebug

# Find the APK
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)

if [ -n "$APK_PATH" ]; then
    echo ""
    echo "Build successful!"
    echo "APK location: $ANDROID_DIR/$APK_PATH"
    echo ""
    echo "To install on a connected device:"
    echo "  adb install $ANDROID_DIR/$APK_PATH"
else
    echo "Error: APK not found after build"
    exit 1
fi

