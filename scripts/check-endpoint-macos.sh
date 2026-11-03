#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
check_dir="$(mktemp -d "${TMPDIR:-/tmp}/nodi-endpoint-check.XXXXXX")"
trap 'rm -rf "$check_dir"' EXIT
xcrun swiftc -sdk "${SDKROOT:-$(xcrun --sdk macosx --show-sdk-path)}" \
  -module-cache-path "$check_dir/cache" \
  "$project_dir/macos/NodiEndpoint.swift" "$project_dir/tests/endpoint/main.swift" -o "$check_dir/check"
"$check_dir/check"
