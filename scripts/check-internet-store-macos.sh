#!/bin/bash
# Relay credential storage of the Mac app, run against a throwaway home folder.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
check_dir="$(mktemp -d "${TMPDIR:-/tmp}/nodi-store-check.XXXXXX")"
trap 'rm -rf "$check_dir"' EXIT
mkdir -p "$check_dir/home/Library/Application Support/Nodi/Devices"
xcrun swiftc -sdk "${SDKROOT:-$(xcrun --sdk macosx --show-sdk-path)}" \
  -module-cache-path "$check_dir/cache" \
  "$project_dir/macos/NodiInternet.swift" "$project_dir/tests/internet-store/main.swift" -o "$check_dir/check"
CFFIXED_USER_HOME="$check_dir/home" "$check_dir/check"
