#!/bin/bash
# Build script for GeoVault Android Uploader app

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

# Check if Gradle wrapper exists, if not, generate it
if [ ! -f "gradlew" ]; then
    echo "Gradle wrapper not found. Attempting to generate wrapper..."
    
    # Try to find Gradle
    GRADLE_CMD=""
    
    # Check if gradle is in PATH
    if command -v gradle &> /dev/null; then
        GRADLE_CMD="gradle"
    # Check common Android Studio Gradle locations
    elif [ -f "$HOME/Android/Sdk/tools/bin/gradle" ]; then
        GRADLE_CMD="$HOME/Android/Sdk/tools/bin/gradle"
    elif [ -f "$HOME/.gradle/wrapper/dists" ]; then
        # Try to find gradle in Android Studio installation
        if [ -d "$HOME/.local/share/Google/AndroidStudio" ]; then
            GRADLE_CMD=$(find "$HOME/.local/share/Google/AndroidStudio" -name "gradle" -type f 2>/dev/null | head -n 1)
        fi
    fi
    
    if [ -n "$GRADLE_CMD" ] && [ -x "$GRADLE_CMD" ]; then
        echo "Found Gradle at: $GRADLE_CMD"
        "$GRADLE_CMD" wrapper --gradle-version 8.2
    else
        echo ""
        echo "Error: Gradle wrapper not found and could not locate Gradle."
        echo ""
        echo "Please generate the Gradle wrapper using one of these methods:"
        echo ""
        echo "Option 1: Using Android Studio"
        echo "  1. Open Android Studio"
        echo "  2. Open this project: $SCRIPT_DIR"
        echo "  3. Android Studio will automatically generate the Gradle wrapper"
        echo ""
        echo "Option 2: Using command line (if Gradle is installed)"
        echo "  cd $SCRIPT_DIR"
        echo "  gradle wrapper --gradle-version 8.2"
        echo ""
        exit 1
    fi
fi

# Ensure Gradle wrapper is executable
chmod +x gradlew

# Clean up any duplicate icon XML files that Android Studio might create
if [ -f "app/src/main/res/drawable/ic_launcher_foreground.xml" ]; then
    echo "Removing duplicate ic_launcher_foreground.xml (using PNG instead)..."
    rm -f app/src/main/res/drawable/ic_launcher_foreground.xml
fi

# Build type: debug (default) or release
BUILD_TYPE=${1:-debug}

if [ "$BUILD_TYPE" != "debug" ] && [ "$BUILD_TYPE" != "release" ]; then
    echo "Error: Build type must be 'debug' or 'release'"
    echo "Usage: ./build-android.sh [debug|release]"
    exit 1
fi

# Build the APK
echo "Building Android app ($BUILD_TYPE)..."
./gradlew assemble"${BUILD_TYPE^}"  # Capitalize first letter: debug -> Debug, release -> Release

# Find the APK
APK_PATH=$(find app/build/outputs/apk/$BUILD_TYPE -name "*.apk" | head -n 1)

if [ -n "$APK_PATH" ]; then
    echo ""
    echo "Build successful!"
    echo "APK location: $SCRIPT_DIR/$APK_PATH"
    echo ""
    echo "To install on a connected device:"
    echo "  adb install $SCRIPT_DIR/$APK_PATH"
else
    echo "Error: APK not found after build"
    exit 1
fi

