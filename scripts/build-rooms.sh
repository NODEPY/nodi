#!/bin/bash
# Build test and public room packages without changing version or signing.
# Test builds carry NODI_TEST_INSTALL=true and are excluded from usage metrics.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

for variant in test public; do
    if [ "$variant" = test ]; then
        test_install=true
        android_name=nodi-android-rooms-test.apk
        macos_name=nodi-macos-rooms-test.zip
        app_dir="$project_dir/build/Nodi.app"
    else
        test_install=false
        android_name=nodi-android-rooms.apk
        macos_name=nodi-macos-rooms.zip
        app_dir="$project_dir/build/public/Nodi.app"
    fi

    echo "Building Android ($variant, NODI_TEST_INSTALL=$test_install)…"
    bash android/gradlew -p android assembleDebug -PnodiTestInstall="$test_install"
    cp android/app/build/outputs/apk/debug/app-debug.apk "$project_dir/build/$android_name"

    echo "Building macOS ($variant, NODI_TEST_INSTALL=$test_install)…"
    NODI_APP_DIR="$app_dir" NODI_TEST_INSTALL="$test_install" NODI_NO_OPEN=1 bash scripts/run-macos.sh

    echo "Packaging macOS ($variant)…"
    rm -f "$project_dir/build/$macos_name"
    (cd "$(dirname "$app_dir")" && zip -qry "$project_dir/build/$macos_name" "$(basename "$app_dir")")
done

echo "Built:"
ls -l build/nodi-android-rooms-test.apk build/nodi-android-rooms.apk \
      build/nodi-macos-rooms-test.zip build/nodi-macos-rooms.zip
