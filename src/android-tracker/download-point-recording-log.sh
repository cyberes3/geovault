#!/bin/bash
# Export the installed Tracker app point recording log and pull it to /tmp.

set -euo pipefail

PACKAGE_NAME="com.geovault.tracker"
OUTPUT_DIR="/tmp"
TIMEOUT_SECONDS=180

if ! command -v adb >/dev/null 2>&1; then
    echo "adb was not found on PATH." >&2
    exit 1
fi

echo "Requesting point-recording-log export from $PACKAGE_NAME..."
adb shell am broadcast -a com.geovault.common.EXPORT_POINT_RECORDING_LOG -p "$PACKAGE_NAME" >/dev/null

echo "Bringing app to foreground so Android does not freeze the export worker..."
adb shell monkey -p "$PACKAGE_NAME" 1 >/dev/null || true

deadline=$((SECONDS + TIMEOUT_SECONDS))
done_line=""
failed_line=""
last_progress=""

while [ "$SECONDS" -lt "$deadline" ]; do
    while IFS= read -r line; do
        case "$line" in
            *point_recording_export_failed*)
                failed_line="$line"
                ;;
            *point_recording_export_progress*)
                last_progress="$line"
                ;;
            *point_recording_export_done*adb_pull_hint=*)
                done_line="$line"
                ;;
        esac
    done < <(adb logcat -d -v time)

    if [ -n "$failed_line" ]; then
        echo "Point-recording-log export failed:" >&2
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
    echo "Timed out waiting for point_recording_export_done after ${TIMEOUT_SECONDS}s." >&2
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

echo "Downloaded point recording log: $destination"
