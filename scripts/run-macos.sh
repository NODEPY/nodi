#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [ "$(uname -s)" != "Darwin" ]; then
    echo 'Цей скрипт збирає віконний застосунок лише на macOS.' >&2
    exit 1
fi
if ! command -v go >/dev/null 2>&1; then
    echo 'Go не знайдено. Встанови Go з https://go.dev/dl/ і відкрий новий термінал.' >&2
    exit 1
fi
if ! xcrun --find swiftc >/dev/null 2>&1; then
    echo 'Потрібні Apple Command Line Tools. Виконай: xcode-select --install' >&2
    echo 'Після завершення встановлення повтори: bash scripts/run-macos.sh' >&2
    exit 1
fi
app_dir="$project_dir/build/Nodi.app"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources" "$project_dir/build/ModuleCache-26.5"
case "$(uname -m)" in
    arm64) go_arch=arm64; swift_target=arm64-apple-macos12.0 ;;
    x86_64) go_arch=amd64; swift_target=x86_64-apple-macos12.0 ;;
    *) echo 'Непідтримувана архітектура Mac.' >&2; exit 1 ;;
esac
echo '1/3 · Збираю Go-ядро…'
GOOS=darwin GOARCH="$go_arch" CGO_ENABLED=0 go build -buildvcs=false -trimpath -o "$app_dir/Contents/MacOS/nodi-core" ./cmd/nodi
echo '2/3 · Збираю нативне вікно…'
xcrun swiftc -swift-version 5 -O -target "$swift_target" \
    -sdk "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk" \
    -module-cache-path "$project_dir/build/ModuleCache-26.5" \
    -framework AppKit -framework QuartzCore -framework UniformTypeIdentifiers -framework ImageIO -framework CoreVideo -framework CoreMedia -framework AVFoundation -framework CoreImage macos/Nodi.swift -o "$app_dir/Contents/MacOS/Nodi"
cp macos/Info.plist "$app_dir/Contents/Info.plist"
cp macos/Nodi.icns "$app_dir/Contents/Resources/Nodi.icns"
cp android/app/src/main/assets/NodiMark.png "$app_dir/Contents/Resources/NodiMark.png"
plutil -lint "$app_dir/Contents/Info.plist"
echo '3/3 · Підписую локальну збірку й відкриваю Nodi…'
codesign --force --sign - "$app_dir/Contents/MacOS/nodi-core"
codesign --force --sign - "$app_dir"
codesign --verify --deep --strict "$app_dir"
open "$app_dir"
printf '\nГотово: %s\nНаступного разу відкривай Nodi.app подвійним кліком.\n' "$app_dir"
