#!/bin/bash
# Build script for Android Tracker app

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

commit_fragment_from_git() {
    local git_dir="$1"
    local full
    full=$(git -C "$git_dir" rev-parse HEAD 2>/dev/null) || full=""
    if [ -z "$full" ]; then echo "norepo"; return; fi
    if [ ${#full} -le 10 ]; then echo "$full"; else echo "${full:0:10}"; fi
}

GIT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_TEMP_SLUG="geovault-tracker"

# Load env files if present (new-apps first, then repo root)
for ENV_FILE in "$SCRIPT_DIR/../.env" "$SCRIPT_DIR/../../.env"; do
    if [ -f "$ENV_FILE" ]; then
        set -a
        # shellcheck source=/dev/null
        source "$ENV_FILE"
        set +a
    fi
done

if [ ! -f "./gradlew" ]; then
    echo "Gradle wrapper not found. Attempting to generate wrapper..."
    GRADLE_CMD=""
    if command -v gradle &> /dev/null; then
        GRADLE_CMD="gradle"
    elif [ -d "$HOME/.local/share/Google/AndroidStudio" ]; then
        GRADLE_CMD=$(find "$HOME/.local/share/Google/AndroidStudio" -name "gradle" -type f 2>/dev/null | head -n 1)
    fi
    if [ -n "$GRADLE_CMD" ] && [ -x "$GRADLE_CMD" ]; then
        "$GRADLE_CMD" wrapper
    else
        echo "Error: could not locate Gradle to generate wrapper."
        exit 1
    fi
fi
chmod +x "./gradlew"

BUILD_TYPE="debug"
SKIP_MINIFY=false
INSTALL=false
OLD_VERSION=false
ADD_LOGGING=false

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
        --add-logging)
            ADD_LOGGING=true
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: ./build-android.sh [debug|release|clean] [--skip-minify] [--install] [--old-version] [--add-logging]"
            echo "  --add-logging compiles capture logging into both debug and release APKs (sets -PGEOVAULT_ADD_LOGGING=true)."
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
if [ "$ADD_LOGGING" = true ]; then
    GRADLE_ARGS+=("-PGEOVAULT_ADD_LOGGING=true")
fi

if [ "$BUILD_TYPE" = "release" ]; then
    RELEASE_STORE_PASSWORD="${RELEASE_STORE_PASSWORD:-${ANDROID_KEYSTORE_PASSWORD:-}}"
    RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-${ANDROID_KEY_PASSWORD:-}}"

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
        echo "Error: RELEASE_STORE_PASSWORD is not set for release builds."
        echo "Set it in env (or set ANDROID_KEYSTORE_PASSWORD), or provide ANDROID_KEY_PASSWORD_FILE."
        exit 1
    fi
    RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-$RELEASE_STORE_PASSWORD}"

    if [ -z "${RELEASE_STORE_FILE:-}" ]; then
        echo "Error: RELEASE_STORE_FILE is not set for release builds."
        exit 1
    fi

    GRADLE_ARGS+=("-PRELEASE_STORE_PASSWORD=$RELEASE_STORE_PASSWORD")
    GRADLE_ARGS+=("-PRELEASE_KEY_PASSWORD=$RELEASE_KEY_PASSWORD")
fi

OLD_VERSION_SHA=""
OLD_VERSION_DATE=""
if [ "$OLD_VERSION" = true ]; then
    LOOKBACK_DAYS="${OLD_VERSION_LOOKBACK_DAYS:-30}"
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

remove_android_build_outputs() {
    ./gradlew clean --quiet 2>/dev/null || true
    rm -rf "$SCRIPT_DIR/build" "$SCRIPT_DIR/app/build" \
        "$SCRIPT_DIR/../android-common/build" "$SCRIPT_DIR/../android-common-maps/build"
}

stage_built_apk() {
    local apk_path="$1"
    STAGED_APK_TMP=$(mktemp "${TMPDIR:-/tmp}/${APP_TEMP_SLUG}-${BUILD_TYPE}-apk-XXXXXX.apk")
    cp "$apk_path" "$STAGED_APK_TMP"
    case "$apk_path" in
        /*) STAGED_APK_DEST="$apk_path" ;;
        *) STAGED_APK_DEST="$SCRIPT_DIR/$apk_path" ;;
    esac
}

restore_staged_apk() {
    if [ -f "${STAGED_APK_TMP:-}" ]; then
        mkdir -p "$(dirname "$STAGED_APK_DEST")"
        mv "$STAGED_APK_TMP" "$STAGED_APK_DEST"
        echo "Restored APK to: $STAGED_APK_DEST"
    fi
}

echo "Building Android app ($BUILD_TYPE)..."
if [ "$ADD_LOGGING" = true ]; then
    echo "Capture logging is enabled for this build"
fi
if ! ./gradlew "assemble${BUILD_TYPE^}" "${GRADLE_ARGS[@]}"; then
    echo "Removing Gradle build outputs after failed build..."
    remove_android_build_outputs
    exit 1
fi

APK_PATH="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(ls app/build/outputs/apk/$BUILD_TYPE/*.apk 2>/dev/null | sed -n '1p')"
fi

if [ -z "${APK_PATH:-}" ] || [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found after build"
    remove_android_build_outputs
    exit 1
fi

INSTALL_APK_PATH="$APK_PATH"

echo ""
echo "Build successful!"
echo "APK location: $SCRIPT_DIR/$APK_PATH"

if [ "$BUILD_TYPE" = "release" ]; then
    if [ "$OLD_VERSION" = true ] && [ -n "$OLD_VERSION_SHA" ]; then
        BUILD_DATE="${OLD_VERSION_DATE:-$(git -C "$REPO_DIR" show -s --format=%cd --date=short "$OLD_VERSION_SHA" 2>/dev/null || date +%Y-%m-%d)}"
        if [ ${#OLD_VERSION_SHA} -le 10 ]; then
            COMMIT_FRAGMENT="$OLD_VERSION_SHA"
        else
            COMMIT_FRAGMENT="${OLD_VERSION_SHA:0:10}"
        fi
    else
        BUILD_DATE="$(git -C "$GIT_ROOT" log -1 --format=%cd --date=short 2>/dev/null || date +%Y-%m-%d)"
        COMMIT_FRAGMENT=$(commit_fragment_from_git "$GIT_ROOT")
    fi
    DEST_NAME="GeoVault-Live-Tracker-${BUILD_DATE}-${COMMIT_FRAGMENT}.apk"
    cp "$APK_PATH" "$SCRIPT_DIR/$DEST_NAME"
    echo "Copied release APK to: $SCRIPT_DIR/$DEST_NAME"
    INSTALL_APK_PATH="$DEST_NAME"
fi

stage_built_apk "$APK_PATH"

echo ""
echo "To install on a connected device:"
echo "  adb install -r $SCRIPT_DIR/$INSTALL_APK_PATH"

if [ "$INSTALL" = true ]; then
    echo "Installing APK..."
    adb install -r "$SCRIPT_DIR/$INSTALL_APK_PATH"
fi

echo "Removing Gradle build outputs..."
remove_android_build_outputs
restore_staged_apk
