#!/bin/bash
# Build script for Android Uploader app

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load src/new-apps/.env if present
if [ -f "$SCRIPT_DIR/../.env" ]; then
    set -a
    source "$SCRIPT_DIR/../.env"
    set +a
fi

if [ ! -x "./gradlew" ]; then
    echo "Error: ./gradlew is missing or not executable"
    exit 1
fi

BUILD_TYPE="debug"
SKIP_MINIFY=false
INSTALL=false
OLD_VERSION=false

for arg in "$@"; do
    case "$arg" in
        debug|release|clean)
            BUILD_TYPE="$arg"
            ;;
        --skip-minify)
            SKIP_MINIFY=true
            ;;
        --install)
            INSTALL=true
            ;;
        --old-version)
            OLD_VERSION=true
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: ./build-android.sh [debug|release|clean] [--skip-minify] [--install] [--old-version]"
            exit 1
            ;;
    esac
done

if [ "$BUILD_TYPE" = "clean" ]; then
    ./gradlew clean
    echo "Clean complete."
    exit 0
fi

GRADLE_ARGS=()
if [ "$SKIP_MINIFY" = true ]; then
    GRADLE_ARGS+=("-PSKIP_MINIFY=true")
fi

if [ "$BUILD_TYPE" = "release" ]; then
    if [ -n "${ANDROID_KEY_PASSWORD_FILE:-}" ] && [ -f "$ANDROID_KEY_PASSWORD_FILE" ]; then
        RELEASE_STORE_PASSWORD="$(sed -n '1p' "$ANDROID_KEY_PASSWORD_FILE" | tr -d '\r\n')"
        RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"
    fi

    if [ -n "${RELEASE_STORE_FILE:-}" ]; then
        GRADLE_ARGS+=("-PRELEASE_STORE_FILE=$RELEASE_STORE_FILE")
    fi
    if [ -n "${RELEASE_KEY_ALIAS:-}" ]; then
        GRADLE_ARGS+=("-PRELEASE_KEY_ALIAS=$RELEASE_KEY_ALIAS")
    fi

    if [ -z "${RELEASE_STORE_PASSWORD:-}" ]; then
        echo -n "Enter keystore password (used for both keystore and key): "
        read -rs RELEASE_STORE_PASSWORD
        echo
    fi
    RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"

    GRADLE_ARGS+=("-PRELEASE_STORE_PASSWORD=$RELEASE_STORE_PASSWORD")
    GRADLE_ARGS+=("-PRELEASE_KEY_PASSWORD=$RELEASE_KEY_PASSWORD")
fi

OLD_VERSION_SHA=""
OLD_VERSION_DATE=""
if [ "$OLD_VERSION" = true ]; then
    LOOKBACK_DAYS=30
    REPO_DIR="$SCRIPT_DIR/.."
    OLD_VERSION_SHA=$(git -C "$REPO_DIR" log --before="$LOOKBACK_DAYS days ago" --format=%H -n 1 2>/dev/null || true)
    if [ -z "$OLD_VERSION_SHA" ]; then
        echo "Error: unable to find a commit from about $LOOKBACK_DAYS days ago."
        exit 1
    fi
    OLD_VERSION_DATE=$(git -C "$REPO_DIR" show -s --format=%cd --date=short "$OLD_VERSION_SHA" 2>/dev/null || true)
    echo "Using old version commit for BuildConfig.GIT_COMMIT_SHA:"
    echo "  commit: $OLD_VERSION_SHA"
    if [ -n "$OLD_VERSION_DATE" ]; then
        echo "  date:   $OLD_VERSION_DATE"
    fi
    GRADLE_ARGS+=("-PGIT_COMMIT_SHA_OVERRIDE=$OLD_VERSION_SHA")
fi

echo "Building Android app ($BUILD_TYPE)..."
if ! ./gradlew "assemble${BUILD_TYPE^}" "${GRADLE_ARGS[@]}"; then
    echo "Removing Gradle build outputs after failed build..."
    ./gradlew clean --quiet 2>/dev/null || true
    rm -rf "$SCRIPT_DIR/build" "$SCRIPT_DIR/app/build" "$SCRIPT_DIR/../android-common/build"
    exit 1
fi

APK_PATH="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(ls app/build/outputs/apk/$BUILD_TYPE/*.apk 2>/dev/null | sed -n '1p')"
fi

if [ -z "${APK_PATH:-}" ] || [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found after build"
    ./gradlew clean --quiet 2>/dev/null || true
    rm -rf "$SCRIPT_DIR/build" "$SCRIPT_DIR/app/build" "$SCRIPT_DIR/../android-common/build"
    exit 1
fi

INSTALL_APK_PATH="$APK_PATH"

echo ""
echo "Build successful!"
echo "APK location: $SCRIPT_DIR/$APK_PATH"

if [ "$BUILD_TYPE" = "release" ]; then
    if [ "$OLD_VERSION" = true ] && [ -n "$OLD_VERSION_SHA" ]; then
        BUILD_DATE=${OLD_VERSION_DATE:-$(date +%Y-%m-%d)}
        COMMIT_HASH=$(printf "%s" "$OLD_VERSION_SHA" | cut -c1-10)
    else
        BUILD_DATE="$(git -C "$SCRIPT_DIR/.." log -1 --format=%cd --date=short 2>/dev/null || date +%Y-%m-%d)"
        COMMIT_HASH="$(git -C "$SCRIPT_DIR/.." rev-parse --short=10 HEAD 2>/dev/null || echo "norepo")"
    fi
    DEST_NAME="GeoVault-Uploader-${BUILD_DATE}-${COMMIT_HASH}.apk"
    cp "$APK_PATH" "$SCRIPT_DIR/$DEST_NAME"
    echo "Copied release APK to: $SCRIPT_DIR/$DEST_NAME"
    INSTALL_APK_PATH="$DEST_NAME"
fi

echo ""
echo "To install on a connected device:"
echo "  adb install -r $SCRIPT_DIR/$INSTALL_APK_PATH"

if [ "$INSTALL" = true ]; then
    echo "Installing APK..."
    adb install -r "$SCRIPT_DIR/$INSTALL_APK_PATH"
fi

# Drop Gradle outputs under Nextcloud (large trees); release APK was copied above if applicable.
echo "Removing Gradle build outputs..."
./gradlew clean --quiet 2>/dev/null || true
rm -rf "$SCRIPT_DIR/build" "$SCRIPT_DIR/app/build" "$SCRIPT_DIR/../android-common/build"

