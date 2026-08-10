#!/bin/bash
# Export the installed Places app capture log and pull it to /tmp.

set -euo pipefail

OUTPUT_DIR="/tmp"
TIMEOUT_SECONDS=180

if ! command -v adb >/dev/null 2>&1; then
    echo "adb was not found on PATH." >&2
    exit 1
fi

detect_package() {
    if adb shell pm path com.geovault.places.debug >/dev/null 2>&1; then
        echo "com.geovault.places.debug"
    elif adb shell pm path com.geovault.places >/dev/null 2>&1; then
        echo "com.geovault.places"
    else
        echo ""
    fi
}

PACKAGE_NAME="$(detect_package)"
if [ -z "$PACKAGE_NAME" ]; then
    echo "Neither com.geovault.places nor com.geovault.places.debug is installed." >&2
    exit 1
fi

echo "Requesting capture-log export from $PACKAGE_NAME..."
adb shell am broadcast -a com.geovault.common.EXPORT_CAPTURE_LOG -p "$PACKAGE_NAME" >/dev/null

echo "Bringing app to foreground so Android does not freeze the export worker..."
adb shell am start -n "$PACKAGE_NAME/.MainActivity" >/dev/null 2>&1 || true

deadline=$((SECONDS + TIMEOUT_SECONDS))
done_line=""
failed_line=""
last_progress=""

while [ "$SECONDS" -lt "$deadline" ]; do
    while IFS= read -r line; do
        case "$line" in
            *capture_export_failed*)
                failed_line="$line"
                ;;
            *capture_export_progress*)
                last_progress="$line"
                ;;
            *capture_export_done*adb_pull_hint=*)
                done_line="$line"
                ;;
        esac
    done < <(adb logcat -d -v time)

    if [ -n "$failed_line" ]; then
        echo "Capture-log export failed:" >&2
        echo "$failed_line" >&2
        exit 1
    fi

    if [ -n "$done_line" ]; then
        break
    fi

    if [ -n "$last_progress" ]; then
        echo "Waiting for export: $last_progress"
    else
        echo "Waiting for export to start..."
    fi
    sleep 2
done

if [ -z "$done_line" ]; then
    echo "Timed out waiting for capture_export_done after ${TIMEOUT_SECONDS}s." >&2
    if [ -n "$last_progress" ]; then
        echo "Last progress: $last_progress" >&2
    fi
    exit 1
fi

pull_hint="${done_line##*adb_pull_hint=}"
pull_hint="${pull_hint%% *}"
file_name="${pull_hint##*/}"
destination="$OUTPUT_DIR/$file_name"

echo "Pulling $pull_hint to $destination..."
adb pull "$pull_hint" "$destination" >/dev/null

echo "Downloaded capture log: $destination"
