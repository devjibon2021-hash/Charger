#!/bin/sh
set -e
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -f "$ROOT_DIR/android/gradlew" ]; then
    cd "$ROOT_DIR/android"
    exec ./gradlew "$@"
else
    echo "Error: android/gradlew not found" >&2
    exit 1
fi
