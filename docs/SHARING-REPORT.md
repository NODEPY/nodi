# Share → Nodi and macOS file drops

Date: 2026-11-02

## Behavior

Android registers ACTION_SEND and ACTION_SEND_MULTIPLE for shared content. A cold
launch and an existing singleTop activity both accept incoming shares. Stream and
ClipData URIs are deduplicated; only content URIs are accepted, with a limit of
100 shared files. Metadata uses the existing background selection workflow.
Text-only shares open the Text tab (up to 64 KiB UTF-8). Nothing is sent until the
user chooses the destination and taps Send. Incoming shares during selection,
pairing, scanning or sending show a retry message instead of replacing the work.
URI access uses the sending app's temporary read grant, not persistent grants.

The macOS document view accepts up to 100 regular file URLs, highlights an accepted
drag and feeds the same selection method as the file picker. Directories and
non-file data are rejected. Drops cannot replace a running outgoing transfer.
The Send card includes a localized drop hint. Sending remains explicit.

## Included working-tree changes

This branch also preserves the previously prepared text-transfer/UI/core changes
and the six Kotlin ↔ Go integration tests from the same workspace. It is a source
update for review, not a new versioned or notarized release. Generated binaries,
Gradle caches, local SDK settings, credentials and backups are excluded.

## Validation

- Android assembleDebug and testDebugUnitTest: passed. Existing Android unit tests
  are template tests, not evidence that system sharing works on a physical phone.
- Swift typecheck and full optimized macOS compilation with SDK 26.5: passed.
  Bundle plist validation and deep/strict ad-hoc signature verification: passed
  after removing Finder metadata from the generated bundle. The script now removes
  FinderInfo/ResourceFork metadata before signing.
- go vet and go test -race: passed.
- CLI integration: passed (file/text transfer, duplicate rejection, SIGTERM cleanup).
- Kotlin ↔ Go suite: all six tests previously passed; the final Gradle invocation
  confirmed the unchanged test inputs were up to date and rebuilt the Go helper.
- git diff --check and shell syntax: passed.
- Strix: sandbox downloaded, but model preflight failed because chatgpt/gpt-5.4
  is unavailable to this account. No security assessment completed.

## Build portability

The Mac build script creates a fresh temporary module cache, avoiding stale paths
from moved checkouts. It detects the default SDK through xcrun and accepts SDKROOT.
This machine's default SDK 27.0 requires a newer compiler than its installed Swift
6.3.3; verification uses SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk.

## Manual acceptance still required

1. Android: share one image and multiple documents from Files/Gallery into both a
   closed and an already-open Nodi; select a peer, send, compare received bytes.
2. Share text/link; verify the Text tab, explicit sending, and the 64 KiB limit.
3. Share during an active upload; verify the current upload is unchanged and a
   retry message appears. Retry after completion.
4. Deny/revoke source access and confirm a useful error without a partial final file.
5. Mac: drop one/multiple files on the window; verify highlight, names, cancellation
   and explicit Send. Reject folders and drops during sending.
6. Confirm on-device behavior after activity recreation and with different content
   providers. Sharing does not promise persistent access after the source grant ends.

Native interaction coverage is incomplete: inspecting the running Mac app reached
an older existing process; it did not verify a drag gesture against the new build.
