# Nodi — handoff for a new session

Last updated: 2026-11-26 · release branch `codex/alpha-10` (Alpha 10). Everything below
"History" is older checkpoint notes, newest first; where they disagree with this section,
this section wins.

## 1. Status in one minute

- **Product:** Nodi sends files, text and links between a Mac (Apple Silicon) and an Android
  phone — directly on the same Wi-Fi, or across networks through an encrypted relay.
  No accounts, no cloud storage. Apache-2.0, public repo `NODEPY/nodi`.
- **Latest release:** `v1.0.0-alpha.10` (pre-release, 2026-11-26). Android `1.0.0-alpha.10` /
  versionCode 10, macOS `1.0.0` / build 12. Alpha 10 = Wi-Fi | Internet route switch (same
  screen in both modes), Internet files up to 48 MiB and 2–4× faster (64 KiB relay frames),
  macOS relay credentials in a 0600 file instead of the Keychain, «Support the author» (Ko-fi
  https://ko-fi.com/nodepy_nodi). Alpha 9 = long-poll relay. Alpha 8 = EN/RU. Alpha 7 = design.
- **Physically verified:** Internet scenarios 1–8 (rooms both ways, files/text both ways,
  restart, cancel, wrong/expired code) on the owner's Mac (Wi-Fi) ↔ Galaxy S21 FE (mobile
  data) with the rooms test builds of 2026-11-06/23 (version 1.0.0-alpha.5). Everything after Alpha 6 was
  verified by automated tests, an Android API 37 emulator and macOS window snapshots. The
  owner ran a pre-Alpha-10 test build on the Mac (2026-11-25, «все працює»); not yet on the phone.
- **Current goal of the owner:** promote Nodi (Reddit, Hacker News, then AlternativeTo,
  awesome-lists, Ukrainian communities). Post drafts are in Appendix A. Nothing has been
  posted yet; the owner posts from their own accounts (or approves each post explicitly).

## 2. Live services

| What | Where | Notes |
| --- | --- | --- |
| Landing page | https://getnodi.pages.dev | Classic Cloudflare Pages project `getnodi`, files from `site/public`. Live copy matches `main`. |
| Old landing URL | https://nodi.igor-shura2011faik.workers.dev | Worker `nodi` with assets only; 301-redirects every path to getnodi.pages.dev. Source is not in the repo (a one-line `_redirects`). |
| Relay | https://nodi-relay.igor-shura2011faik.workers.dev | Worker `nodi-relay` + Durable Object `RelayHub` (one instance, name `private-alpha-v1`). Source `server/cloudflare`. Current version `39d9e621-…` (code from PR #13 + alpha.9 download secrets). |
| Download links | `<relay>/download/android`, `<relay>/download/macos` | Counted 302 redirects to the release assets. `/download/test/*` are never counted. |
| Releases | https://github.com/NODEPY/nodi/releases | Assets: APK, Mac ZIP, BUILD-MANIFEST.json, BUILD-REPORT.md, SHA256SUMS.txt. |
| Repo homepage | set to https://getnodi.pages.dev | Description + 12 topics set via `gh repo edit`. Social preview image NOT set (manual, Settings → Social preview → `docs/images/hero.png`). |

The relay hostname contains the Cloudflare account subdomain derived from the owner's email.
It is baked into released apps (Go `internal/internet/client.go` const `Server`, Android
`NodiInternet.SERVER`), README buttons and the landing page. Changing the account subdomain
would break Internet transfers in every released build. Only a custom domain + a new app
release can remove it.

| Donations | https://ko-fi.com/nodepy_nodi | Owner's Ko-fi (personal PayPal). Linked from the landing `#support` section, README and `.github/FUNDING.yml`. The apps' «Підтримати автора» button opens `getnodi.pages.dev/#support`, so the donation link changes on the site only. Landing deployed 2026-11-25 with this section. |

## 3. Repository map

| Path | What |
| --- | --- |
| `internal/transfer` | Go core: pairing, TLS 1.3 pinning, file/text protocol, device book |
| `internal/internet` | Go relay client: enrollment/rooms (`pairing.go`), WSS tunnel + receiver loop (`client.go`) |
| `cmd/nodi` | Go CLI / JSON core used by the Mac app (`nodi-core` inside Nodi.app) |
| `internal/relay`, `cmd/nodi-relay` | Old loopback relay prototype (historical, not deployed) |
| `android/` | Android app (Kotlin, no Compose; UI built in code in `MainActivity.kt` ~1.7k lines). `NodiText.kt` = translations, `NodiInternet.kt` = relay client, `NodiReceiveService.kt` = foreground receiver |
| `macos/` | macOS app (AppKit, single `Nodi.swift` ~1.5k lines + `NodiInternet.swift`, `NodiEndpoint.swift`). No Xcode project: built by `scripts/run-macos.sh` |
| `server/cloudflare` | Relay Worker (`src/worker.js`), Miniflare tests (`test/`), `wrangler` is installed in its `node_modules` |
| `site/public` | Landing page (one self-contained `index.html` with inline EN/UK/RU dictionary, `_headers`, `404.html`, `assets/`) |
| `tests/interop` | Kotlin ↔ Go tests using the production Kotlin transport; 2 of them hit the live relay |
| `scripts/` | `run-macos.sh` (build Mac app), `build-rooms.sh` (test+public variants), `install-macos.sh` (end-user installer), `check-translations.py`, `check-core.py`, `check-endpoint-macos.sh`, `internet-report.mjs` (owner's private stats) |
| `docs/` | `INTERNET-ALPHA.md` (rooms user guide + physical test checklist), `INTERNET-RELAY.md`, `VPN-ADDRESS.md`, `CLI.md`, `images/` (README/landing images) |

## 4. This Mac, toolchain and quirks

- **The project folder name ends with a space:** `/Users/node/Projects/NODI ` — always quote paths.
- Go 1.27.2; Node 26 (Homebrew, installed 2026-11-24); JDK 25 (Temurin); Android SDK at
  `~/Library/Android/sdk` (build-tools 36.0.0), emulator AVD `Medium_Phone_API_37.0`.
- **macOS SDK:** the default SDK 27 is incompatible with Swift 6.3.3 here. `run-macos.sh`
  picks `/Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk` automatically;
  `check-endpoint-macos.sh` does NOT — run it as
  `SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk bash scripts/check-endpoint-macos.sh`.
- The terminal has no Screen Recording / Accessibility permission. To see the Mac UI use
  snapshot mode: `NODI_SNAPSHOT=/path/out.png <Nodi.app>/Contents/MacOS/Nodi -nodi.language en`
  (empty temporary device store, no receiver, renders the window and quits).
- Android emulator: boots with a "System UI isn't responding" dialog — tap **Wait**
  (`adb shell input tap 163 1330` at 1080×2400). Clean status bar for screenshots:
  `adb shell settings put global sysui_demo_allowed 1` + `am broadcast -a com.android.systemui.demo -e command enter|clock -e hhmm 0941|...`.
- Image work (README/landing images) used Pillow in a throwaway venv:
  `python3 -m venv /tmp/venv && /tmp/venv/bin/pip install pillow qrcode`.
- `.claude/launch.json` (untracked, local only) serves `site/public` on port 8137 for the
  desktop app's browser preview.
- The owner's running dev Nodi may be launched from `build/Nodi.app`; rebuilding that path
  replaces the app under it.

## 5. Secrets and private files — never print, commit or upload

| File | Contents |
| --- | --- |
| `server/cloudflare/.dev.vars.relay-admin` | `ADMIN_TOKEN=<64 hex>` for the relay admin API (also a Worker secret) |
| `build/internet-activation/` | Old operator activation codes for the owner's Mac and S21 (pre-rooms) |
| `build/private-report/` | Generated private stats report (0600/0700) |

All three are git-ignored (`build/`, `.dev.vars*`). Worker secrets: `ADMIN_TOKEN`,
`DOWNLOAD_ANDROID_URL`, `DOWNLOAD_MACOS_URL`. Wrangler is logged in with the owner's OAuth
token (`~/Library/Preferences/.wrangler/config/default.toml`).

- **Private report:** `node scripts/internet-report.mjs` → `build/private-report/index.html`
  (daily pseudonymous usage, feedback, download counts). It also prints the **owner link**
  (`<relay>/download/owner/<sha256("nodi-owner-link:"+ADMIN_TOKEN)>`): opening it sets an
  HttpOnly cookie so that browser's downloads are not counted. The owner has the link;
  do not post it anywhere.
- Using the admin token for public interop tests: `export NODI_TEST_RELAY_ADMIN="$(cut -d= -f2 server/cloudflare/.dev.vars.relay-admin | tr -d '\n')"`
  — the tests create temporary devices and delete them.

## 6. Everyday checks (all were green on 2026-11-24)

```bash
go vet ./... && go test -race -count=1 ./...
python3 scripts/check-core.py
python3 scripts/check-translations.py
(cd server/cloudflare && npm test && npm run check)          # Miniflare 27 tests + dry-run deploy
bash android/gradlew -p android assembleDebug testDebugUnitTest
bash android/gradlew -p tests/interop test --rerun-tasks --no-daemon   # 9 pass, 2 public skipped without NODI_TEST_RELAY_ADMIN
SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.sdk bash scripts/check-endpoint-macos.sh
NODI_NO_OPEN=1 NODI_APP_DIR="$TMPDIR/Nodi.app" bash scripts/run-macos.sh   # Mac build without touching build/Nodi.app
```

Gradle may treat tests as up-to-date and print nothing — use `--rerun-tasks` to really run them.

## 7. Working agreements with the owner

- The owner writes in Ukrainian; answer in Ukrainian. Repo docs, commits, release notes and
  the landing page default language are English.
- Branch per change (`codex/<topic>`), PR into `main`, merge with `gh pr merge <n> --merge`
  **only after CI is green** (the owner usually says «Перевір і змердж»). GitHub auto-merge is
  disabled for the repo. Each push runs CI twice (push + pull_request); if the
  pull_request run is fully green and only the duplicate push run is pending, merging is
  acceptable — say so explicitly.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; PR
  bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Deploying the relay, publishing releases, changing Worker secrets and anything public
  needs the owner's go-ahead in chat (the auto-mode classifier blocked the first Worker
  deploy until the owner approved). Posting on social sites always needs explicit approval
  of the exact text.
- Don't hit the counted `/download/android|macos` links with a browser-like User-Agent when
  testing (it counts). Plain `curl` is not counted; `/download/test/*` is never counted.
  (On 2026-11-24 one such check added +1 Android and +1 macOS.)

## 8. Release procedure (as done for Alpha 7–9)

1. Branch `codex/alpha-N`. Bump `android/app/build.gradle.kts` (`versionCode`, `versionName
   = "1.0.0-alpha.N"`) and `macos/Info.plist` `CFBundleVersion` (+1). Edit the plist with a
   text replace, **not** PlistBuddy (it reorders the file). Update README «Versioning»,
   CHANGELOG (turn «Unreleased» into the new section), HANDOFF.
2. Commit, push, open PR. While CI runs, build the **public** packages from that commit:
   ```bash
   rm -rf build/public/Nodi.app
   NODI_TEST_INSTALL=false NODI_NO_OPEN=1 NODI_APP_DIR="$PWD/build/public/Nodi.app" bash scripts/run-macos.sh
   bash android/gradlew -p android assembleDebug testDebugUnitTest -PnodiTestInstall=false --rerun-tasks
   r=build/releases/alpha.N; mkdir -p $r
   cp android/app/build/outputs/apk/debug/app-debug.apk $r/Nodi-Android-alpha.N.apk
   (cd build/public && zip -qryX ../../$r/Nodi-macOS-arm64-alpha.N.zip Nodi.app)   # zip, not ditto: ditto adds ._ files that break the signature after unzip
   ```
3. Verify: `BuildConfig.NODI_TEST_INSTALL = false` (in `android/app/build/generated/source/buildConfig/debug/...`),
   apksigner cert SHA-256 `c14152d8…b7` (debug key — same for all releases so updates install
   over old ones), `aapt2 dump badging` version, ZIP extracted with both `unzip` and
   `ditto -x -k` passes `codesign --verify --deep --strict`, `NodiTestInstall=false`, no
   `/._`, `.dev.vars`, `activation` or `private-report` entries, the public app launches
   (`NODI_SNAPSHOT=…`).
4. After CI is green: merge; check the merge commit's tree equals the built commit's tree
   (`git rev-parse <merge>^{tree}` vs `<built>^{tree}`); write `BUILD-MANIFEST.json`,
   `BUILD-REPORT.md`, `SHA256SUMS.txt` (see `build/releases/alpha.9/` for the format).
5. `gh release create v1.0.0-alpha.N --target <merge sha> --prerelease --title "Nodi — Alpha N · <theme>" --notes-file … <5 files>`.
   Notes: hero image via raw.githubusercontent.com at the merge sha, quick download links,
   «Installing on macOS» (installer command + macOS 15 Open Anyway path), what's new,
   limitations. Compare uploaded asset digests with SHA256SUMS.txt.
6. Point the download counter at the new assets (from `server/cloudflare`):
   ```bash
   printf '%s' 'https://github.com/NODEPY/nodi/releases/download/v1.0.0-alpha.N/Nodi-Android-alpha.N.apk' | npx wrangler secret put DOWNLOAD_ANDROID_URL
   printf '%s' 'https://github.com/NODEPY/nodi/releases/download/v1.0.0-alpha.N/Nodi-macOS-arm64-alpha.N.zip' | npx wrangler secret put DOWNLOAD_MACOS_URL
   ```
   It takes ~30 s to propagate; then `curl -s -o /dev/null -w '%{redirect_url}' <relay>/download/android`.
7. Check the Mac installer picks the new release:
   `curl -fsSL https://raw.githubusercontent.com/NODEPY/nodi/main/scripts/install-macos.sh | NODI_INSTALL_TEST=1 NODI_NO_OPEN=1 NODI_INSTALL_DIR=$TMPDIR/x bash`.

## 9. Relay: rules that must not be broken

- **Free plan budget:** 100,000 Worker requests/day and 100,000 Durable Object rows
  written/day (each `put`, `delete` and `setAlarm` is a written row). Before Alpha 9 every
  poll wrote 2+ rows and every relayed frame wrote one — keep it that way:
  - rate windows, presence and the alarm time live in `this.mem`, not in storage;
  - per-frame counters/flow-control flags live in WebSocket attachments
    (`serializeAttachment`), not in the session record;
  - `save()` skips empty transactions; the alarm is set only when its time changes.
- **Long-poll:** `/v1/incoming` with `X-Nodi-Wait: 1` holds ≤50 s (`LONG_POLL_MS`, env
  override `LONG_POLL_MS` for tests) and is woken in `run()` **after** `save()`
  (`this.mem.wake`) — waking before the save made the receiver see an empty list.
  Without the header the relay answers immediately: **Alpha 6–8 clients rely on this.**
- Clients: first poll immediate (status shows at once); quick empty answer or error → 10 s
  pause; answer with only in-progress sessions → 1 s pause (otherwise the Go loop re-polls
  at full speed into the 120 req/min rate limit); re-read the allowlist when sessions
  arrive (room-paired peers).
- Capacity: `MAX_DEVICES` 1000, `MAX_SESSIONS` 32 (≤2 per device), 32 pending rooms
  (≤2 per host), presence 75 s, session rendezvous `WAIT_MS` 30 s, life 10 min, 64 MiB and
  4096 frames per session (data frames + empty ACKs), 64 KiB frames. Clients pack TLS records
  into frames of up to 64 KiB (Go: 2 ms read deadline on the pipe; Kotlin: `available()`), which
  allows Internet files of 48 MiB (client side, `internet.MaxFileBytes`); 47 MiB both ways
  took ~130 s through the live relay. Do not shrink frames again without lowering the limit. Roughly 50
  devices can keep receiving on all day within the free plan.
- **Before deploying relay changes:** Miniflare tests, then deploy, then the 2 public
  interop tests with the new clients AND with the old clients — the old-client check:
  ```bash
  git worktree add "$TMPDIR/nodi-main" <previous release tag>
  cd "$TMPDIR/nodi-main" && NODI_TEST_RELAY_ADMIN=… bash "/Users/node/Projects/NODI /android/gradlew" -p tests/interop test --rerun-tasks --no-daemon
  ```
- Deploy: `cd server/cloudflare && npx wrangler deploy` (needs owner approval).
- `server/cloudflare/src/worker.js` line 3 comment still says credentials are operator
  provisioned; devices actually self-enroll (cosmetic).

## 10. Landing page

- Deploy: `cd server/cloudflare && npx wrangler pages deploy ../../site/public --project-name getnodi --branch main`
  (no `--force`; the project already exists on classic Pages. Wrangler 4.157 otherwise
  creates Workers-with-assets projects.)
- Static only (no Functions) → requests are free and do not count toward the relay's quota.
  CSP/security headers in `_headers`; images are WebP made from `docs/images/*.png`;
  `assets/og.jpg` 1200×630 for link previews; `assets/qr-android.svg` → Android download.
- English is the default; UK/RU only after the visitor picks them (localStorage
  `nodi.lang`). Texts: the `T` dictionary near the bottom of `index.html`; English is read
  from the HTML itself.
- Device-aware hero: Mac → macOS button first + installer command; Android → APK first;
  other desktops → QR; iPhone/iPad → "not available yet".

## 11. Translations

- Source strings are Ukrainian. Android: `NodiText.kt` (`"uk" to arrayOf("en","ru")`; labels,
  buttons and `NodiDialog` translate automatically, other places call `tr()`/`NodiText.text`).
  macOS: `nodiWords` in `Nodi.swift` (`"uk": ["en","ru"]`, `NodiL()`; `NodiLabel`/`NodiButton`
  translate automatically; NSAlert texts must be wrapped). Go core event messages are
  shown by the Mac app, so they also need entries in `nodiWords`.
- Keys ending in `:` or a space work as prefixes ("Код кімнати:" + value).
- **A duplicate key in `nodiWords` crashes the Mac app at launch** (Swift dictionary
  literal). `scripts/check-translations.py` (CI) catches untranslated literals, duplicates,
  Cyrillic in English and Ukrainian letters in Russian.
- Translate parts before concatenating — a combined string is not in the table.

## 12. Known gaps and ideas (roughly by priority)

0. Videos for TikTok live outside the repo in `~/Projects/nodi-video/` (scripts + synthesized
   music; ElevenLabs voice by the owner). video3 = reply to a comment, publish only after Alpha 10.


1. **Promotion** (owner's current goal) — Appendix A; then AlternativeTo, PRs to awesome
   lists, DOU/Telegram (Ukrainian), Product Hunt only after notarization.
2. Install Alpha 9 on the physical S21 FE + Mac and re-run a quick Internet transfer
   (long-poll was only tested via interop and emulator).
3. Mac notarization needs the Apple Developer Program ($99/yr, owner pays). Plan: Developer
   ID Application cert + `notarytool store-credentials nodi-notary` (owner enters the
   app-specific password) → script: sign `nodi-core` then the app with hardened runtime +
   timestamp, notarize, staple, zip, `spctl` check. No identities exist on this Mac yet.
4. Android release signing: APKs use the debug key `c14152d8…`. Switching to a real key
   breaks in-place updates for existing users — decide before the user base grows (and
   before Play Store / F-Droid).
5. Custom domain (owner buys) → move landing and, with a new app release, the relay off the
   email-derived workers.dev subdomain.
6. Statistics scenarios 9–10 in `docs/INTERNET-ALPHA.md` (test vs public build counting) are
   automated-only; the owner said they are not needed now.
7. Monoliths `MainActivity.kt` and `Nodi.swift` — split when touching big UI features.
8. Roadmap from README: Windows/Linux, NAT traversal, offline delivery, folders, resume.

---

## Appendix A — launch post drafts (prepared 2026-11-24, not posted)

Post on weekdays 15:00–18:00 Kyiv time, one channel per day (e.g. HN Monday, r/macapps
Tuesday, r/androidapps Wednesday, r/opensource Thursday). Answer every comment in the first
2 hours; never ask for upvotes. Attach `docs/images/hero.png` / `android.png` / `macos.png`.

**Hacker News** — Title: `Show HN: Nodi – Encrypted file and text transfer between Mac and Android`,
URL `https://getnodi.pages.dev`, first comment by the owner:

> Hi HN, I built Nodi because moving files between my Galaxy and my MacBook was always a detour through a cloud drive or a messenger.
>
> How it works:
> - Same Wi-Fi: mDNS discovery, then you compare a short code on both screens. After that devices talk directly over TLS 1.3 pinned to each other's certificate.
> - Different networks: one device creates a "room" with a 16-character code, the other joins, the host approves. From then on Nodi tries a direct TCP connection and falls back to a relay (a Cloudflare Worker + Durable Object) that only forwards the pinned TLS stream – it never sees file contents. The room code never reaches the server in clear: it gets a digest and an AES-256-GCM blob.
> - Receivers long-poll the relay, so Internet transfers start immediately.
>
> Stack: Go core (also a CLI), native Kotlin app on Android, AppKit on macOS. Apache-2.0, no accounts, no analytics unless you opt in.
>
> It's an alpha and I'd rather be upfront about the rough edges: Internet transfers are capped at 16 MiB, the Mac build is Apple Silicon only and not notarized yet (there's a one-line installer that verifies the SHA-256), and the APK is debug-signed. No independent security audit yet.
>
> I'd love feedback on the pairing flow and the relay design.
>
> Code: https://github.com/NODEPY/nodi

**r/macapps** — Title: `I made Nodi – AirDrop-style file & text transfer between your Mac and an Android phone (free, open source)`.
Body: developer intro; bullets (Wi-Fi find + code + drag & drop; rooms across networks;
text/links to clipboard; pinned TLS, no account/cloud/tracking); the installer command;
macOS 15 Open Anyway note for the ZIP; website + source links; "alpha, Feedback button in the app".

**r/androidapps** — Title: `[DEV] Nodi – send files, text and links between Android and Mac, on Wi-Fi or over the Internet (free, open source, no account)`.
Body: why it was built; bullets (Find on Wi-Fi → code → send, up to 2 GiB on LAN; rooms
across networks with automatic Wi-Fi/Internet switching; Share → Nodi and Text tab;
encrypted, no account/ads/tracking, Apache-2.0); APK link via the website; honest notes
(GitHub APK not Play Store, 16 MiB over the Internet, desktop side = Apple Silicon Mac).
Check the subreddit's self-promotion rules/days first.

**r/opensource** — Title: `Nodi: open-source (Apache-2.0) encrypted file transfer between Mac and Android – Go core, Kotlin and AppKit apps, Cloudflare relay`.
Body: architecture bullets (Go core/CLI, Kotlin, AppKit; mDNS + comparison codes + pinned
TLS + SHA-256; rooms + Worker relay forwarding the pinned stream, long-poll; CI covering
Go, Kotlin↔Go interop, Miniflare relay tests and translation completeness); links; ask for
security review and Windows/Linux contributions.

**Prepared answers:** vs LocalSend/KDE Connect (works across networks via encrypted rooms
without VPN or port forwarding; relay can't read data; KDE Connect is broader device
integration); why not Play Store / notarized (early alpha, planned); does the server see
files (no — device-to-device TLS; it sees device IDs, IPs, timing, size); Windows/iPhone
(not yet; Windows/Linux on the roadmap, help welcome).

---

# History (older checkpoints, newest first)

## Relay capacity and Alpha 9 (2026-11-24)

- Long-poll: clients send `X-Nodi-Wait: 1` to /v1/incoming; the relay holds up to 50 s and
  wakes the receiver after the new session is saved. First poll after start or an error is
  immediate (status shows at once); quick empty answers or errors pause 10 s; answers with
  only in-progress sessions pause 1 s (this prevented a re-poll storm that hit the
  120/min rate limit). The allowlist is re-read when sessions arrive (room-paired peers).
- Writes: rate windows, presence and per-frame counters are in memory / WebSocket
  attachments; save() skips empty transactions; the alarm is set only when it changes.
  Free plan: 100k Worker requests/day and 100k DO rows written/day.
- Limits: MAX_DEVICES 1000, MAX_SESSIONS 32, presence 75 s. Deployed before the release;
  public interop passed with both the new clients and Alpha 8 clients (main) against it.

---

## Landing page (2026-11-24)

- Live at https://getnodi.pages.dev — a classic Cloudflare Pages project `getnodi` (no account subdomain
  in the URL). `site/public` is the whole site: static files only, no Functions, so
  requests are free and do not touch the relay's Workers quota.
- Deploy: `cd server/cloudflare && npx wrangler pages deploy ../../site/public --project-name getnodi --branch main`
  (no `--force`: the project already exists on classic Pages).
  Preview locally: `python3 -m http.server 8137 --directory site/public`.
- The first deployment, a Worker named `nodi` on https://nodi.igor-shura2011faik.workers.dev, now only redirects
  to the Pages site.
- Download buttons use the counted /download links; checking them with a browser-like
  User-Agent counts a download (curl's default UA is not counted).
- English is the default; UK/RU only after the visitor picks them (saved in localStorage).
  Texts live in the inline EN/UK/RU dictionary in index.html; images come from docs/images.

---

## Alpha 8 release (2026-11-24)

- `v1.0.0-alpha.8`: full English/Russian translations (PR #8), Android 1.0.0-alpha.8 / 8,
  macOS build 10, public variant. Assets and report in `build/releases/alpha.8/`.
- `scripts/check-translations.py` runs in the Go checks workflow (Ubuntu, stable Go).
  New UI text must be added to NodiText.kt (Android) or nodiWords in Nodi.swift (macOS;
  Go core messages too). A duplicate key in nodiWords crashes the Mac app at launch.
- Download secrets point at the alpha.8 assets after release.

---

## Alpha 7 release (2026-11-24)

- `v1.0.0-alpha.7` pre-release on main `fb304fa` (PR #5 design, PR #6 bump): Android
  1.0.0-alpha.7 / 7, macOS build 9, public variant. Assets and report in
  `build/releases/alpha.7/` and on GitHub; checksums match the uploads.
- Download secrets now point at the alpha.7 assets (Worker version `7fbc1a70-…`).
- README redesigned with images in `docs/images/` (hero, android, macos). Regenerate:
  Android shots from the API 37 emulator in English; macOS via
  `NODI_SNAPSHOT=<png> build/…/Nodi.app/Contents/MacOS/Nodi -nodi.language en`.
- Repo description, homepage and topics set; social preview image must be uploaded
  manually in GitHub Settings.
- New UI not yet checked on the physical S21 FE / Mac with real transfers.

---

## Alpha 6 release and download counter (2026-11-23)

- Released `v1.0.0-alpha.6` (pre-release, tag on main `158aade`, PR #3; PR #2 merged
  with it). Public builds: Android 1.0.0-alpha.6 / 6, macOS build 8. Assets,
  manifest, report and checksums in `build/releases/alpha.6/` and on GitHub.
- Worker deployed with user approval: code version `29fb6983-…`, then
  `DOWNLOAD_ANDROID_URL` / `DOWNLOAD_MACOS_URL` secrets → active version
  `922c63d7-3b19-4a68-9d92-38adda9b4917`. `/download/android|macos` → 302 to the
  alpha.6 assets (both return 200); `/download/test/*` not counted.
- Owner exclusion: `node scripts/internet-report.mjs` prints the owner link; it sets an
  HttpOnly `nodi_owner` cookie for /download. Bots/CLI user agents are not counted.
  Verified live: owner link 200 + cookie, wrong link 404. Owner must open the link
  on each device/browser; never commit or publish it.
- Each future release must update both DOWNLOAD_*_URL secrets.
- Node 26 installed via Homebrew; Miniflare 26/26 PASS on it.

---

## Physical acceptance checkpoint (2026-11-23)

Branch `codex/internet-relay`, on top of `2337974`. No merge, release, tag,
version bump, deploy or Worker change.

### Physical-device result

- Scenarios 1–8 in `docs/INTERNET-ALPHA.md` PASSED, reported by the owner:
  Mac on Wi-Fi ↔ S21 FE on mobile data (Wi-Fi off, no VPN/manual address).
  Rooms both directions, files/text both ways, restart persistence,
  cancellation, wrong and expired codes.
- Scenarios 9–10 (test/public statistics) remain NOT RUN: they need
  `node scripts/internet-report.mjs`, and Node is not installed (the former
  `/private/tmp/node-v22.23.4-…` copy is gone). Miniflare tests also need Node.

### Builds used

`bash scripts/build-rooms.sh` on 2026-11-23 (no SDKROOT; script picked
MacOSX26.sdk). Android APKs are byte-identical to 2026-11-06
(test `32f009cd…a6db`, public `10ec9c06…aeff`), same debug cert `c14152d8…b7`,
1.0.0-alpha.5 / 5. macOS ZIPs rebuilt: test `b3c0cd24…4e70`
(`NodiTestInstall=true`), public `3bf77afc…a19` (`false`); codesign strict PASS;
no secret paths in archives.

### Same-day checks and cleanups

- `go vet`, `go test -race -count=1 ./...`: PASS. Android
  `assembleDebug testDebugUnitTest`: PASS. Interop forced rerun: 9 PASS,
  2 public tests skipped (no admin token supplied).
- Root `.gitignore` rule `nodi` → `/nodi`; new files under `cmd/nodi/` and
  `dev/nodi/` no longer need `git add -f`. Root `nodi` binary deleted.
- SDK auto-selection moved into `scripts/run-macos.sh` (used by build-rooms).
- README, `docs/INTERNET-RELAY.md`, server README enrollment section updated
  to reflect the deployed relay, rooms and self-enrollment.

### Remaining

- Scenarios 9–10 after installing Node ≥22.
- Optional `/v1/rooms/join` quota (see proposal below) requires a Worker deploy.
- No GitHub release; download counters stay 503 until configured.

---

## Review checkpoint — ready for physical acceptance (2026-11-06)

Branch `codex/internet-relay`; reviewed local commits above `d8fd3de`:
- `180de45`: only `scripts/build-rooms.sh` and `docs/INTERNET-ALPHA.md`;
  selects the existing MacOSX26.sdk only when SDKROOT is unset/empty, preserves
  explicit overrides and warns if the preferred SDK is absent. Shell syntax PASS.
- `4c4fbf9`: only `server/cloudflare/README.md`; 10-second incoming polling
  agrees with `RunReceiver`. No secrets or unrelated changes in either diff.
- This checkpoint adds review evidence here and documents Node/PATH and local
  socket requirements in the Cloudflare README. Older checkpoints remain below.

### Fresh local verification

- `go vet ./...` and `go test -race -count=1 ./...`: PASS.
- `npm test`: 26/26 PASS; `npm run check`: PASS (dry-run only).
- `bash android/gradlew -p android assembleDebug testDebugUnitTest`: PASS
  (Gradle tasks up-to-date). `git diff --check`: PASS.
- Initial sandbox attempts could not bind loopback sockets or write Gradle's
  existing user cache. Repeating with those permissions succeeded; these were
  environment failures, not test regressions.
- Existing Node 22.23.4 in `/private/tmp/node-v22.23.4-darwin-arm64/bin`
  was used; no runtime installation or shell-profile access was needed.
- No public interoperability test was rerun locally and no owner device was
  registered, rotated, installed or cleared. Prior 11/11 evidence remains historical.

### Existing artifacts rechecked (not rebuilt or replaced)

| File in build/ | SHA-256 |
| --- | --- |
| nodi-android-rooms-test.apk | `32f009cddd2c2b86d3688080dd6b45865834ab2cdfd2e8ecb9d9d124aceea6db` |
| nodi-android-rooms.apk | `10ec9c0654c936d4ff4a0f8f436e4606601a648a21f9eb1be15b9a8f5116aeff` |
| nodi-macos-rooms-test.zip | `e00d984a6de457bd49e947f91819fec8f31ee50999f112b7d34f5fbb30da5bb4` |
| nodi-macos-rooms.zip | `f2992412fc0f9b383cf1028c5cb9fd463e2b29a3eca43969577b1e03ea5e05d8` |

- APK DEX `BuildConfig.NODI_TEST_INSTALL`: test `true`, public `false`.
  Both APKs are 17,057,492 bytes, version `1.0.0-alpha.5`, code `5`.
  Both APK signatures verify with certificate SHA-256
  `c14152d85e91dc9f7c7ea177f12f5c0c62f95afc7a313830ed3027638ce198b7`.
- ZIP Info.plist `NodiTestInstall`: test `true`, public `false`.
  Existing macOS version fields are `1.0.0` / build `7`, matching the tracked
  `macos/Info.plist`; they are not Android's alpha.5/code 5. No version was changed.
- All four archives have no `.dev.vars`, `internet-activation` or `private-report`
  paths. Decompressed contents were scanned for admin assignments, literal bearer
  credentials and PEM private-key markers without printing values. The only
  pattern hit was a concatenated string in both Go cores, also reproduced by a
  fresh source build; it is not evidence of an embedded credential. No credential
  was identified. This pattern scan does not prove absence of arbitrary encoded
  secrets; private activation/environment files were not read for comparison.

### Manual acceptance and optional hardening

All scenarios 1–10 in `docs/INTERNET-ALPHA.md` remain **NOT RUN**. A person must
run them on the physical Mac on Wi-Fi and S21 FE on mobile data. Automated
checks do not establish physical-device acceptance.

Proposal only: a separate `/v1/rooms/join` quota is not required to make random
brute force of an 80-bit, five-minute code impractical: about 600 guesses per
identity give roughly 5e-22 success probability against one code (fixed-window
boundaries can allow an additional burst). Host approval is still required.
A lower quota can reduce abusive failed joins and resource use, but multiple
self-enrolled identities can bypass a per-device-only defense. If desired later,
add a separate per-device join counter/window, e.g. 10 attempts/minute, checked
before room lookup, returning 429 and counting unsuccessful attempts too; retain
the global 120/device/minute limit. Add quota/reset tests. Do not lock the room
itself on failures, which would enable denial of service. No limit, Worker source
or deployed Worker was changed in this review.

No merge, release, tag, force push, paid-plan change or main modification.

---

## Current checkpoint — rooms deployed and verified (2026-11-06)

Base `ceb5c1c`; branch `codex/internet-relay`. No merge, release, tag, version
bump, paid-plan change or force push. `main` was not modified.

### Implemented

- EC certificate proof-of-possession self-enrollment with one-minute, one-use
  challenges. Revoked identities cannot re-enroll; explicit admin restore remains.
- Encrypted 16-character (80-bit) room codes: host creates, guest joins, host
  approves the expected fingerprint. Only the code digest and AES-256-GCM blob
  reach the server. Approval establishes mutual grants; the shared transfer token
  stays inside the encrypted blob. Inner pinned TLS remains unchanged.
- Go, Android and macOS room clients/UI; Android KeyStore and macOS Keychain
  storage; room cancellation; saved pairing after room success.
- Opt-in usage (`active`/`file`/`text`) and feedback endpoints; test builds send
  no metrics. Owner-only `scripts/internet-report.mjs` writes
  `build/private-report/index.html` (0600/0700). Admin token never in clients/HTML.
- Download redirect counters (`/download/android`, `/download/macos`, test
  variants) return 503 until `DOWNLOAD_*_URL` is configured; no release created.
- Storage format 2 migration, bounded records, `legacy-v1-backup` retained.
- Test/public build separation is explicit in `scripts/build-rooms.sh`; the
  Android property and macOS Info.plist field are set per variant.

### Deployed and verified

- Worker: https://nodi-relay.igor-shura2011faik.workers.dev
- Active version `9f3d2050-d8f4-4dc8-b20b-a1a984c379de`, created
  2026-11-06T16:07:14Z (confirmed with `wrangler deployments list`).
- Full Kotlin/Go interop: **11/11 PASS**, including both public tests
  `publicRoomEnrollmentAndBidirectionalTransfer` and
  `publicRelayKotlinGoFilesAndTextBothDirections`. This covers self-enrollment,
  both room ownership directions, files/text both ways, byte comparison, and
  temporary device cleanup.
- Server Miniflare: **26/26 PASS**, 40.4 s. `npm run check` dry-run PASS
  (22.69 KiB, gzip 6.42 KiB); no deploy from this checkpoint.
- Go: `go vet ./...` PASS; `go test -race -count=1 ./...` PASS
  (`internal/internet`, `internal/relay`, `internal/transfer`).
- Android `assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL.
- macOS builds with `SDKROOT=.../MacOSX26.sdk`: both variants and ad-hoc
  signature verification PASS. Default SDK 27.0 is incompatible with Swift 6.3.3
  in this environment; use the documented SDKROOT.
- Build artifacts verified:
  - Android test `BuildConfig.NODI_TEST_INSTALL=true`; public `false`.
    Both 17,057,492 bytes, debug cert SHA-256
    `c14152d85e91dc9f7c7ea177f12f5c0c62f95afc7a313830ed3027638ce198b7`.
  - macOS test `NodiTestInstall=true`; public `false`; both codesign strict PASS.
- New edge tests: room code cannot be reused after approval/cancellation; third
  device gets 404; room joins share the 120/device/minute quota; deleting a room
  removes its persisted encrypted blob; Android clears `roomThread` in
  `onDestroy`.
- Owner devices are marked test on the server; private report exists locally.
  Do not print or commit activation files or tokens.

### Remaining

- Physical Mac (Wi-Fi) ↔ S21 FE (mobile data) manual run: room creation in both
  directions, file/text both ways, restart persistence, cancellation, wrong and
  expired codes, and test/public statistics behavior. The checklist and result
  table are in `docs/INTERNET-ALPHA.md`; all ten scenarios are still NOT RUN.
- No GitHub release exists, so download counters stay 503. Configuration commands
  are documented in `server/cloudflare/README.md`; do not publish without approval.
- No new merge/tag/release/version bump. PR #2/base history is unchanged.
- Historical checkpoints below are preserved as history; statements about missing
  rooms/client integration are superseded by this checkpoint.

### Commits in this checkpoint

- `0f4edf5` server rooms, self-enrollment, insights and tests
- `012244d` Go room enrollment and relay lifecycle
- `e3b99ca` Android rooms, secure enrollment and feedback UI
- `584cecc` macOS room UI, Keychain enrollment and feedback
- `16f39c3` public room interoperability coverage
- `7466999` reproducible rooms build script
- `dd22f29` owner-only private report script
- `3dd1e65` rooms/build/manual-test documentation

---

## Latest checkpoint — Android/macOS Internet integration

Updated: 2026-11-04. Branch `codex/internet-relay`; base `ceb5c1c`.
User requested completing integration autonomously. No merge, release, tag,
version bump, paid-plan change or force push.

### Implemented

- Go and production Kotlin WebSocket clients connect through the deployed
  Cloudflare relay. Direct TCP is tried first (4-second timeout); only TCP
  connection failure triggers relay. TLS/authentication failures do not retry.
- Existing pinned TLS and pairing token checks run inside WSS. Relay does not
  receive plaintext files/text. Per-device credentials are separate from admin.
- Receiver polling every 10 seconds, local paired-device allowlist, mutual grants,
  session cancellation, bounded buffers and consumption ACKs. Internet alpha
  limits files to 16 MiB; LAN file limit remains unchanged.
- Internet activation UI on both clients; Android supports scanning activation QR.
  Android encrypts credentials using AndroidKeyStore, excludes them from backup;
  Mac uses Keychain. Codes are bound to the existing device certificate pin.
- Mobile-data-only Android receiver can start without a Wi-Fi IPv4 address.
- Fixed Go duplex deadlock: inbound delivery no longer blocks reading the ACK
  that releases outgoing TLS writes. Regression test exercises incoming data
  arriving before ACK while the application writes before reading.

### Validation

- `go vet ./...` and `go test -race ./...`: PASS.
- `go test -race -count=5 ./internal/internet`: PASS (both stream tests).
- Android `assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL (12s).
- Mac build with MacOSX26.5 SDK: PASS, plist lint and ad-hoc signature verification
  PASS. Built with NODI_NO_OPEN=1, so existing user app was not interrupted.
- Public Kotlin/Go test after duplex fix: PASS (53s). Uses production polling
  clients, forced LAN failure, 180,000-byte files and Unicode text both ways,
  byte-for-byte comparison and temporary device cleanup.
- Full interop suite including a second public run: 10/10 PASS, BUILD SUCCESSFUL
  (54s). Two consecutive public runs passed after the duplex fix.
- Earlier public runs exposed the deadlock; one also failed before transport
  setup with NoRouteToHost to the public API. Do not count these as passing runs.
- Physical Android/macOS UI, KeyStore/Keychain activation and mobile-data↔Wi-Fi
  behavior are NOT yet verified. These require installing the new builds.

### User artifacts and activation (local only)

- `build/nodi-android-internet.apk`: 17,057,492 bytes, 2026-11-04 20:04:23 EET.
  SHA-256: `91f5d0a83ef9589ce8b8d3768c66b79eb41d2fd9075bdfc348d6cedd7015cc98`.
- `build/Nodi.app` and `build/nodi-macos-internet.zip`: zip 6,262,894 bytes,
  SHA-256: `2279fba5a444d035663087de0ca3cf88ad4ceeec109b31a19c66d72014f9f0c4`.
- APK keeps 1.0.0-alpha.5 / code 5 and existing debug signing key. Install over the
  existing app (do not uninstall/clear data, which would change device identity).
- Real Mac and paired S21 FE registrations already created on Cloudflare.
  DO NOT register/rotate them again. Secrets are only in ignored
  `build/internet-activation/{mac.txt,android.txt,android-qr.png}`, files 0600,
  directory 0700. Never print/publish these codes or include them in Git/builds.
- Activation still needs to be imported through each app's Internet UI: paste
  mac.txt on Mac; scan android-qr.png on phone. Keep receiving enabled on both.
- Instructions and manual test checklist: `docs/INTERNET-ALPHA.md`.
- Root .gitignore rule `nodi` intentionally unchanged; new Kotlin files must be
  explicitly force-added. No admin key belongs in any client.

Historical checkpoints below describe their own point in time; statements that
client integration/provisioning is absent are superseded by this checkpoint.

---

## Latest checkpoint — Cloudflare deployed and remotely verified

Updated: 2026-11-03. Branch `codex/internet-relay`. Deployed source commit
`c75730f` (the later documentation commit does not change Worker code).
User authorized Cloudflare deployment and explicitly confirmed creating,
locally storing, and uploading the ADMIN_TOKEN after an approval review block.

- Live endpoint: https://nodi-relay.igor-shura2011faik.workers.dev
- Worker: nodi-relay; SQLite Durable Object binding HUB / RelayHub.
- Active version: `f4428c60-d4c7-40e7-86a7-3fd67e232a93`, deployed
  2026-11-03T19:56:20.380Z following secret configuration. Initial source upload
  was version `b712c6f6-d520-4e2f-98e6-e900ffce0389`.
- Wrangler OAuth login completed with the user's browser consent. The account
  initially had no workers.dev subdomain; opening its Workers dashboard created
  igor-shura2011faik.workers.dev. Dashboard showed 100,000 requests/day allowance.
  No paid plan was enabled or payment submitted. OAuth subscriptions endpoint
  returned 403; account-settings returned default_usage_model=standard, which
  alone is not a billing-plan proof.
- ADMIN_TOKEN is set as a Worker secret. Local recovery copy is
  server/cloudflare/.dev.vars.relay-admin, mode 0600 and git-ignored (verified).
  Never print, commit, upload elsewhere, or include this key in app builds.
  No LOCAL_TEST variable was deployed. Secret values were not printed.
- Public HTTPS initially failed TLS handshakes on the new hostname; it later
  succeeded without disabling certificate verification or changing TLS policy.
- Remote smoke test PASS: /health=200, unauthenticated /v1/incoming=401,
  registration of two synthetic test devices, mutual grants, role-bound tickets,
  public WSS rendezvous, inner pinned TLS 1.3, exact 150,000-byte random payload,
  and reverse reply. Both temporary device registrations deleted (2/2).
- Smoke driver is /private/tmp/nodi-relay-smoke.mjs; it uses existing dev
  dependencies and public test-only TLS fixtures. This was a transport test,
  NOT real Nodi Android/Go application interoperability or physical-device test.
- No Android/macOS integration or enrollment UI yet. Next milestone is secure
  per-device enrollment and client WebSocket transport with automatic routing.
  Keep existing device pins/tokens; never embed ADMIN_TOKEN in clients.
- No GitHub push/PR/merge/tag/release in this deployment step. PR #2 merge status
  has not been rechecked. Local commits remain ahead of origin.

Previous no-deployment/no-secret statements below are historical and superseded
by this checkpoint. Server performance and public abuse controls still need
further validation before broad rollout.

---

## Latest checkpoint — relay availability and boundary fixes

Updated: 2026-11-03. Branch `codex/internet-relay`; base for this change
`1c083a4`. User asked to continue; current milestone remains server-side.

- Moved bounded JSON body reading outside blockConcurrencyWhile. Admission
  authenticates and charges quota first; final mutation reauthenticates against
  current persisted state. This preserves revocation during an in-flight upload.
- Added persisted admin quota: 120 authenticated requests per 60-second window.
  Anonymous abuse still requires edge controls; this is not a global DDoS limit.
- Check available byte budget before increasing counters or forwarding. The
  previous code already rejected an excessive frame BEFORE forwarding; the
  earlier note about exceeding the cap referred to accounting, not leaked data.
- Tests verify another device remains responsive during a stalled upload,
  revocation during upload, exactly 64 MiB accepted with the next byte rejected,
  exactly 4096 frames accepted with the next rejected, and admin request 121.
- Actual local results: `npm test` 19/19 PASS, 0 fail, 38.9 s; `npm run check`
  PASS (Wrangler dry-run, 11.96 KiB / gzip 3.70 KiB); JS syntax checks and
  `git diff --check` PASS. Node remains in /private/tmp/node-v22.23.4-darwin-arm64.
- No dependency, Go, Android, macOS, root .gitignore or version changes.
- No deployment/login/secret creation, PR, merge, tag or release. These tests
  are local; the earlier remote CI results below apply to earlier commits.
- Still outstanding: app integration/secure enrollment, deployed WSS validation,
  physical cross-network testing, deployment interruption/load tests, and the
  digest-comparison/LOCAL_TEST review questions. Device/global session limits
  are not yet tested at every exact boundary.

The prior checkpoint below is historical. Its slow-body/global-lock, missing
admin quota and byte-accounting notes are superseded by the fixes above.

---

## Current checkpoint — Internet relay (private alpha server)

Updated: 2026-11-03. Branch `codex/internet-relay`, based on PR #2 HEAD
`26c67eb`. PR #2 is still unmerged and `main` was not modified. No deployment,
app integration, PR, merge, tag or release was made.

### Implemented

- `internal/relay` + `cmd/nodi-relay`: loopback-only Go rendezvous prototype
  with its own open TCP protocol; unchanged in this checkpoint except review.
- `server/cloudflare`: Worker + SQLite-backed Durable Object relay with HTTPS/WSS
  enforcement, operator-provisioned device credentials, mutual peer grants,
  private polling presence, single-use role-bound tickets, revocation, bounded
  duplex framing, flow control, quotas and DO-alarm expiry.
- Five-second JSON body deadline and safe outbound WebSocket sends that close the
  session instead of throwing when the peer socket has just gone away.
- Tests use Miniflare/workerd, plus a TLS 1.3 pinned-certificate transport test.
- `.github/workflows/relay.yml`: Node 22, npm cache on
  `server/cloudflare/package-lock.json`, `npm ci`, `npm test`, `npm run check`.

### Verified locally (actual command results)

- `npm ci` in `server/cloudflare`: added 42 packages, 0 vulnerabilities.
- `npm test`: 15 tests, 15 pass. Three consecutive final runs after all edits:
  15 pass / 0 fail each, about 36.9–37.0 s per run. The unclaimed-ticket test
  intentionally takes about 31 s.
- `npm run check`: `wrangler 4.157.0 deploy --dry-run` passed; 10.73 KiB upload
  (3.40 KiB gzip); no deploy.
- Node v22.23.4 / npm 10.9.9. `compatibility_date` 2026-08-06 is accepted by
  Miniflare 4.20260730.0 and Wrangler 4.157.0.
- `node --check src/worker.js` and `node --check test/relay.test.js`: pass.
- `go vet ./...`: pass. `go test -race -count=1 ./...`: pass
  (`internal/relay` 1.730 s, `internal/transfer` 3.797 s).
- `git diff --check`: pass.
- GitHub Actions on pushed head `2ad779c` (push at 2026-11-03T15:30:08Z):
  Cloudflare relay checks success (run 41664531408; `npm ci`, `npm test` 15/15,
  `npm run check` dry-run), Go checks success (run 41664530921; macOS/Ubuntu,
  Go stable/1.22.x, vet, race tests, CLI integration), and Kotlin-Go interop
  success (run 41664530955). CI results are now verified, not merely local YAML
  inspection.
- README numbers checked against `src/worker.js`: 64 devices, 16 sessions,
  2 sessions/device, 120 requests/device/minute, 45 s presence, 30 s
  rendezvous, 10 min paired lifetime, 64 KiB frame, 64 MiB tunnel bytes,
  4096 frames/session, 1024-byte JSON body, 5 s body deadline. Device/global
  session/frame/byte ceilings are code-confirmed; most are not exercised at
  their exact boundary by tests.

### Fixes in this checkpoint

- The reported failure of “inner TLS rejects an incorrect certificate pin”
  did not reproduce in 10 targeted runs. The test bridge could still call
  WebSocket `send()`/`close()` after teardown. It now guards `readyState`,
  handles socket errors, and asserts the receiver got zero application bytes
  after pin rejection. The test still requires a `pin mismatch` error.
- Added tests for a stalled body (rejected after ~5 s) and a truncated body
  (rejected immediately); neither registers the device. A hard client-side
  abort cannot be observed as an HTTP response through Miniflare, so that
  transport case is covered by truncation plus the timeout.
- README corrected: exact `LOCAL_TEST` hosts (`localhost`/`127.0.0.1`), and the
  5 s body deadline is now documented. The test-coverage sentence was updated.
- Added `/.wrangler/` to the root `.gitignore`. A root `.wrangler/` directory
  appeared once and was removed manually; running `npm test` and `npm run check`
  from `server/cloudflare` did not recreate it in controlled checks, so its
  origin remains unknown. The nested `server/cloudflare/.wrangler/` was already
  ignored by that package's `.gitignore`.
- Server-side authenticated presence and single-use ticket issuance now exist.
  This supersedes the old “next: authenticated presence/ticket issuance” note
  for the Cloudflare server; the Go prototype still lacks them.

### Security review notes

- Tickets are distinct per role/session, checked against the authenticated
  device, consumed before the 101 response and persisted before it is visible.
  Revocation, unpairing, cancellation and expiry delete sessions and close
  sockets. No mutual-authorization bypass was found.
- Admin token is never logged or returned. Tokens are compared as SHA-256
  digests, not with a constant-time routine; a leaked digest prefix is not
  enough to authenticate, but this remains an open review question.
- HTTPS is required; `LOCAL_TEST` only permits `localhost`/`127.0.0.1`. It must
  never be set on a deployed Worker.
- Open availability questions: the 5 s body read runs inside
  `blockConcurrencyWhile`, so an authenticated slow-body request can stall the
  single Durable Object for up to 5 s. Admin requests have no per-token rate
  limit and depend on edge controls. The 64 MiB check runs after adding a frame,
  so teardown can occur up to one frame past the advertised cap.

### Not verified

- No Cloudflare deployment, account configuration, secret, real HTTPS/WSS or
  domain; no paid hosting was purchased.
- No real Android/macOS client integration or secure enrollment UI; no traffic
  through this relay from physical devices on different networks or mobile data.
- No Android/Go TLS interoperability test against this WebSocket relay; the TLS
  test is a transport test. The Go prototype has a different protocol.
- No load, soak, restart/deployment-interruption, free-plan quota or edge abuse
  testing. No independent security audit. Presence is polling-based, not a
  connectivity guarantee. Do not present this as production-ready.

The prior handoff below is preserved as historical context; its early “not
pushed” statements predate PR #2, which another agent published successfully.

---

## Nodi handoff — manual address / VPN

Updated: 2026-11-03. Base main: 5293cf3 (Alpha 5).
Working branch: codex/manual-vpn-address. Final checkpoint: `git log -1` on this branch.
User requested manual address/VPN support and progress reports. Implementation and
local verification complete; no push, PR or new release made for this feature.
Published Alpha 5 and its tag must remain unchanged.

### Implemented

- Android and Mac: Address / VPN dialog for already-paired devices; save numeric
  IPv4:port override and reset with Automatic. Display this receiver's endpoints.
- Persist override per certificate fingerprint. Override takes precedence over
  mDNS for file AND text sends. Initial pairing still uses existing QR/Wi-Fi flow.
- Preserve peer token and exact certificate pin. Saving an address never grants
  trust, sends content or probes a server. Manual label does not imply online.
- Android receiver address list includes VPN and other active IPv4 interfaces.
- Strict matching Kotlin and Swift validation; current receivers bind IPv4,
  so DNS/IPv6 support is intentionally deferred.
- Added @main NodiApplication because the Mac app now has two Swift source files.
- New guide docs/VPN-ADDRESS.md; README and Unreleased changelog updated.

### Verification — all final local checks passed

- `bash android/gradlew -p android assembleDebug testDebugUnitTest`: PASS.
  Android unit tests remain template tests, not physical share/VPN UI validation.
- `bash android/gradlew -p tests/interop test --no-daemon`: PASS, 9 tests:
  2 endpoint validation tests + 7 real Kotlin/Go TLS interoperability tests.
  New manual-route test sends file/text with preserved token/pin, rejects wrong pin.
- `SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk bash scripts/check-endpoint-macos.sh`:
  PASS, 21 Swift parser cases.
- `NODI_NO_OPEN=1 SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk bash scripts/run-macos.sh`:
  PASS, optimized compile, plist validation, deep/strict ad-hoc signature verification.
- `git diff --check` and shell syntax: PASS.
- Added Swift parser step to macOS Go CI; remote CI has not run for this branch.
- Earlier Mac build failures (multi-file entry point / edited source during compile)
  were resolved; final source was built without concurrent edits.

### Local outputs

- Android: android/app/build/outputs/apk/debug/app-debug.apk
- Mac: build/Nodi.app
- These are development builds after Alpha 5, with unchanged Alpha 5 version fields.
  Do not upload as Alpha 5 replacement artifacts. Bump versions for a future release.

### Next steps / manual acceptance

1. Pair Android/Mac using existing flow, connect VPN on both, restart receivers.
2. Put devices on different Wi-Fi networks; set each peer's VPN IPv4:current-port.
3. Send file and text BOTH directions; compare received bytes/text.
4. Test restart persistence, Automatic reset, stale port, unreachable endpoint,
   invalid input, and attempting to point at a device with a different certificate.
5. Validate UI on both physical platforms, including Android VPN permission/routing.
6. If publication requested, push branch, run CI, open PR; choose future alpha
   version/build numbers and rebuild artifacts from its final commit.

### Boundaries

No relay, no NAT traversal, no guaranteed Internet connectivity without an existing
VPN/routable TCP. VPN provider infrastructure may still be involved. IPv4 only.
Dynamic receiver ports can change on restart. Address display is refreshed when
receiver starts; connect VPN first. This task did not install/configure a VPN.
Physical dual-network testing not done. Strix remains incomplete from earlier work.

### Inherited issue found (not changed)

Root .gitignore has unanchored `nodi`, hiding new files inside dev/nodi packages as
well as the root binary. New NodiEndpoint.kt and EndpointTest.kt are explicitly
staged with `git add -f`; check they are in the checkpoint commit. Prefer changing
this rule to `/nodi` after user confirmation. Prior instruction requires confirming
corrections to the other agent's work; no change to that rule was made here.

### Key files

- android/.../MainActivity.kt: manualEndpoint, editEndpoint, renderNearby,
  fillSelectedDevice. Preferences: nodi_endpoints, fingerprint key.
- android/.../NodiEndpoint.kt; NodiReceiveService.kt: localAddresses.
- macos/Nodi.swift: manualEndpoint, endpoint(for:), editEndpoint, sendFile/sendText.
  Preferences: nodi.endpoint.<fingerprint>.
- macos/NodiEndpoint.swift and tests/endpoint/main.swift.
- tests/interop/.../EndpointTest.kt, GoInteropTest.kt.
- CI (Go 1.22.x ubuntu, Go stable macos/ubuntu, interop) passed for PR #2 (checks 2) — verified 2026-11-03.
- Not verified / manual acceptance pending: two devices on different networks via VPN both directions; receiver restart with port change; address persistence after app restart; unreachable endpoint; different-certificate device rejected; physical Android/macOS UI validation. Autotest is loopback only, not end-to-end VPN. Source update, no signed/notarized release.
