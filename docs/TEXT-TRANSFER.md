# Text and links — completion notes

Both devices must run the updated source builds. Pair the devices and keep the
receiver enabled. Paste a message into Text, select the paired device, and send.
The receiving device offers Copy and, for an HTTP/HTTPS URL, Open. Neither action
runs automatically. The message is limited to 64 KiB encoded as UTF-8.

## Fixes applied to the supplied unfinished implementation

- Android preserves leading/trailing whitespace and multiline content.
- Android records incoming messages in a synchronized, bounded process-local
  history before acknowledging delivery. The latest 10 messages remain available
  while the activity is closed and after rotation/rebinding. Rebinding does not
  append duplicates. History is intentionally not persisted across process death.
- Explicit clipboard actions on both platforms; receiving does not overwrite
  an existing clipboard. Android no longer reports copying after a failed copy.
- Text transmission is cancelled along with file transmission when the Android
  activity stops.
- CLI stdin accepts the worst-case JSON escaping of a valid 64 KiB message.
- macOS build script discovers the installed SDK with xcrun.

## Validation

- Go: go test -race ./... passed, including existing file and text tests.
- All Android Kotlin application sources compiled against the Android API jar.
  This is compiler validation, not a Gradle APK build or emulator test.
- Actual TLS 1.3 Kotlin -> Go and Go -> Kotlin transfers passed for Unicode,
  emoji, whitespace, URLs, 64 KiB, and heavily JSON-escaped content.
- Process-local history probe passed: latest-10 bound, immutable snapshots and
  repeated reads without duplicates.
- bash -n scripts/run-macos.sh passed.
- Swift/AppKit compilation and UI on physical Mac/Android were not run in this
  Linux environment. They remain the required final device checks.

## Build on your Mac

Close the running Nodi application. Extract this archive to a separate folder
so your existing working project remains a backup. In the extracted NODI folder:

    bash scripts/run-macos.sh

Open the extracted NODI/android project in Android Studio and Run on the phone.
Old binaries/build outputs in the supplied archive are not updated executables;
always rebuild both platforms from these sources.

Device checks: send text in both directions, copy it, open an HTTPS link, send
several messages while Android is in the background, return to Text, rotate the
phone, and confirm that normal multi-file transfers still work.
