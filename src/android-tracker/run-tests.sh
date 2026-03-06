#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
./gradlew :app:testDebugUnitTest --tests "com.geovault.tracker.*" "$@"
