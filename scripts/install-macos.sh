#!/bin/bash
# Install or update Nodi for macOS (Apple Silicon) from the latest GitHub release.
#
#   curl -fsSL https://raw.githubusercontent.com/NODEPY/nodi/main/scripts/install-macos.sh | bash
#
# What it does:
#   1. asks the Nodi download link for the current release ZIP (the same link as the
#      website button, so installs are counted as daily totals; nothing personal is sent);
#   2. downloads the ZIP and SHA256SUMS.txt from that GitHub release and checks the hash;
#   3. replaces Nodi.app in /Applications (or ~/Applications if /Applications is not writable).
#
# Files downloaded with curl are not quarantined, so Gatekeeper does not block this
# ad-hoc signed, not yet notarized build. Read this script before running it.
#
# Options (environment variables):
#   NODI_INSTALL_DIR=<dir>  install somewhere else
#   NODI_NO_OPEN=1          do not start Nodi afterwards
#   NODI_INSTALL_TEST=1     use the uncounted test link (for the maintainer's own tests)

# Everything runs inside main so a partially downloaded script does nothing.
main() {
    set -euo pipefail
    local relay="https://nodi-relay.igor-shura2011faik.workers.dev"
    local link="$relay/download/macos"
    [ "${NODI_INSTALL_TEST:-0}" = "1" ] && link="$relay/download/test/macos"

    say() { printf '\033[1;35m==>\033[0m %s\n' "$1"; }
    fail() { printf '\033[1;31mError:\033[0m %s\n' "$1" >&2; exit 1; }

    [ "$(uname -s)" = "Darwin" ] || fail "This installer is for macOS. On Android, download the APK from https://github.com/NODEPY/nodi/releases"
    [ "$(uname -m)" = "arm64" ] || fail "The Nodi macOS build currently supports Apple Silicon (M1 or newer) only."
    local major
    major="$(sw_vers -productVersion | cut -d. -f1)"
    [ "$major" -ge 12 ] || fail "Nodi needs macOS 12 or newer."

    say "Finding the latest Nodi release…"
    local url
    url="$(curl -fsS -o /dev/null -A "NodiInstaller/1 (macOS)" -w '%{redirect_url}' "$link")" || fail "Could not reach the download link. Check your Internet connection."
    case "$url" in
        https://github.com/NODEPY/nodi/releases/download/*/Nodi-macOS-arm64-*.zip) ;;
        *) fail "Unexpected download address: ${url:-none}" ;;
    esac
    local base="${url%/*}" file="${url##*/}"
    local tag="${base##*/}"

    local work
    work="$(mktemp -d "${TMPDIR:-/tmp}/nodi-install.XXXXXX")"
    # Expand now: the trap runs after main returns, when local variables are gone.
    trap "rm -rf '$work'" EXIT

    say "Downloading $file ($tag)…"
    curl -fL --progress-bar -o "$work/$file" "$url" || fail "Download failed."
    curl -fsSL -o "$work/SHA256SUMS.txt" "$base/SHA256SUMS.txt" || fail "Could not download SHA256SUMS.txt for $tag."

    say "Checking the SHA-256 checksum…"
    local expected actual
    expected="$(awk -v f="$file" '$2 == f { print $1 }' "$work/SHA256SUMS.txt")"
    actual="$(shasum -a 256 "$work/$file" | awk '{ print $1 }')"
    [ -n "$expected" ] || fail "$file is not listed in SHA256SUMS.txt."
    [ "$expected" = "$actual" ] || fail "Checksum mismatch for $file. The download was not installed."

    ditto -x -k "$work/$file" "$work/unpacked" || fail "Could not unpack the archive."
    local app="$work/unpacked/Nodi.app"
    [ -d "$app" ] || fail "The archive does not contain Nodi.app."
    codesign --verify --deep --strict "$app" 2>/dev/null || fail "The app signature is broken. The download was not installed."

    local dest="${NODI_INSTALL_DIR:-}"
    if [ -z "$dest" ]; then
        dest="/Applications"
        [ -w "$dest" ] || { dest="$HOME/Applications"; mkdir -p "$dest"; }
    fi
    mkdir -p "$dest"

    # Only the copy being replaced is closed; a Nodi running from elsewhere is left alone.
    local running="$dest/Nodi.app/Contents/MacOS/Nodi"
    if pgrep -f "$running" >/dev/null; then
        say "Closing the running Nodi…"
        osascript -e "tell application \"$dest/Nodi.app\" to quit" >/dev/null 2>&1 || true
        local i
        for i in 1 2 3 4 5 6 7 8 9 10; do pgrep -f "$running" >/dev/null || break; sleep 1; done
        if pgrep -f "$running" >/dev/null; then fail "Nodi is still running (maybe a transfer is active). Quit it and run the installer again."; fi
    fi

    say "Installing to $dest/Nodi.app…"
    rm -rf "$dest/Nodi.app.new"
    ditto "$app" "$dest/Nodi.app.new"
    xattr -dr com.apple.quarantine "$dest/Nodi.app.new" 2>/dev/null || true
    rm -rf "$dest/Nodi.app"
    mv "$dest/Nodi.app.new" "$dest/Nodi.app"

    say "Nodi $tag is installed. Your paired devices and settings are kept."
    if [ "${NODI_NO_OPEN:-0}" != "1" ]; then open "$dest/Nodi.app"; fi
}

main "$@"
