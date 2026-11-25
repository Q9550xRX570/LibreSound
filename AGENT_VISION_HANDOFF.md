# LibreOpus — Architectural Vision & Agent Handoff Brief

> **Target Audience:** Antigravity agents picking up **LibreOpus** (`LibreSound` test repo) on another workstation.
> **Workspace Priority (Test Repo):** This repository is a live test & mobile vibe-coding workspace. Focus on the **working Android app, features, and fast verification** — do not spend turns polishing `README.md`, store copy, or ancillary documentation unless explicitly requested.
> **Build & Quota Rule (CI-First — Mandatory):** Do **NOT** run APK assembly tasks (`assemble*Release`, `assemble*Preview`, `assemble*Debug`) inside the agent VM unless the user explicitly asks for a local APK or you are debugging an R8/packaging failure. Run fast unit tests (and `lint` when resources/manifests/Android APIs change), then `git commit` and `git push` to `main`. **GitHub Actions** (`.github/workflows/android.yml`) builds and uploads `LibreOpus-Preview-APKs` automatically on every push.
> **Language Note:** The user communicates primarily in **Turkish** (`tr`), and the app maintains full bilingual parity (`values/` English + `values-tr/` Turkish).

---

## 1. Product Vision & Brand Identity

**LibreOpus** (`com.libreopus.app`, domain `libreopus.com` verified available via Verisign RDAP 404 at creation time) was created to fill the Android / F-Droid gap for a **lightweight**, **100% FOSS (`GPL-3.0-or-later`)**, **Material You (Material 3 Expressive)** audio studio with quiet, understated UX (no repetitive marketing hype around "lossless" or `.opus` in the UI, while still saving trimmed `.opus` files without re-encoding under the hood).

Supported local editing formats: `.opus`, `.ogg`, `.mp3`, `.m4a`/`.aac`, `.flac`, `.wav` (8/16/24/32-bit int & float), `.amr`, `.webm`/`.mka`, and video audio extraction (`.mp4`, `.mkv`, `.webm`). Export formats: `.opus`, `.ogg`, `.flac`, `.m4a`, `.wav`.

---

## 2. Two-Edition Product Flavors (`lite` & `full`) + `preview` BuildType

Defined in `app/build.gradle.kts` under `flavorDimensions += "engine"`:

1. **`lite` Edition (`HAS_YTDLP = false`):**
   - **2.89 MB (3,033,501 bytes)** unsigned F-Droid release APK (**2.90 MB** `litePreview`).
   - **100% offline:** ZERO `INTERNET` permission, ZERO storage permissions, ZERO Python/FFmpeg overhead.
   - Pure-Kotlin DSP + RFC container codecs + Android 10+ `MediaCodec`.
2. **`full` Edition (`HAS_YTDLP = true`):**
   - Adds **only** `io.github.junkfood02.youtubedl-android:library:0.18.1` (GPLv3 — **no** `youtubedl-android:ffmpeg`, reusing `:engine:lite` so it stays lean!).
   - ABI-split APKs enabled for `full` (`arm64-v8a` **20.38 MB**, `armeabi-v7a` **18.92 MB**, `universal` **60.13 MB**), while `lite`-only builds emit a single **2.97 MB** F-Droid APK.
   - Adds `INTERNET` permission + `android:extractNativeLibs="true"` + `ACTION_SEND` (`text/plain`) link sharing in `app/src/full/AndroidManifest.xml`.
   - Adds the **Media Downloader** tab (`DownloadScreen.kt` + `DownloadViewModel.kt` + `CookieSupport.kt`):
     - **Cookie Support (`cookies.txt`):** `CookieManagerSheet` accepts Netscape `cookies.txt` (preserving `#HttpOnly_` entries) or Cookie-Editor JSON (auto-converted via dependency-free `SimpleJsonParser`), stored privately in `filesDir/ytdlp/cookies.txt` and toggled via DataStore.
     - **Track Preview (`--dump-json`):** Cancellable `fetchPreview` showing title, uploader, duration, best audio ext, and approx size before downloading.
     - **Hardened `yt-dlp` Execution:** Audio preference selector (`AUTO_BEST`, `OPUS_WEBM`, `M4A_AAC`), `--write-info-json` automatic metadata extraction (`InfoJsonMetadataParser`) passed straight into `EditorViewModel`, WebP-to-JPEG cover normalization, `--retries`, `--socket-timeout`, `--force-ipv4`, `--geo-bypass`, custom `--proxy` & `--user-agent`, exact `addCommands` execution for `CUSTOM_SCRIPT`, `YtDlpErrorClassifier` with quick-fix actions, `MediaStore.Downloads` fallback for `.webm`/`.mp4`/`.mkv`, and in-app `yt-dlp` updater (`STABLE` / `NIGHTLY`) in `SettingsScreen`.
3. **`preview` BuildType (alongside `debug` & F-Droid `release`):**
   - `initWith(release)`, `applicationIdSuffix = ".preview"`, `signingConfig = debug`. Produces R8-minified, directly installable APKs for pre-GitHub testing on phones while keeping `release` unsigned for F-Droid reproducible builds when no keystore is supplied.
4. **Future `:engine:ffmpeg` Expansion:**
   - `:core:audio-api` cleanly isolates `AudioEngine`, `AudioSource`, `DecodedAudio`, `PcmTimelineReader`, and `ExportConfig` so an FFmpeg-backed audio engine (source-compiled for F-Droid) can plug in later without touching UI or `:core:dsp`.

---

## 3. Non-Negotiable Architectural Invariants

1. **License (`GPL-3.0-or-later`):** Zero proprietary SDKs, zero analytics/ads, zero Google Play Services. Preserve `LICENSE`, `COPYING`, `app/src/main/res/raw/gplv3.txt`, and SPDX headers.
2. **`lite` Must Stay Strictly Offline:** Never add `INTERNET` or `youtubedl-android` to `main` or `lite`.
3. **F-Droid Reproducible Builds & Verified Developer Signature:**
   - Keep `dependenciesInfo { includeInApk = false; includeInBundle = false }` and `buildTypes.release.vcsInfo { include = false }` (`Metadata bloat: 0`).
   - Never commit `keystore.properties`, `local.properties`, `*.jks`, or `*.keystore`. Release signing reads `keystore.properties` or `LIBREOPUS_KEYSTORE_*` env vars.
4. **Material You + Material 3 Expressive (`1.5.0`) + Bilingual (`en` & `tr`):**
   - Keep `values/strings.xml` and `values-tr/strings.xml` synchronized and free of showy hype copy.

---

## 4. Toolchain & Solved Gotchas

1. **AGP `9.5.2` Built-in Kotlin:** Android modules MUST NOT apply `org.jetbrains.kotlin.android`. Only `:app` applies `org.jetbrains.kotlin.plugin.compose`.
2. **Gradle `9.7.1` on JDK 25:** Targets JVM 17 (`JavaVersion.VERSION_17` / `JvmTarget.JVM_17`).
3. **SDK & Compose BOM:** `compileSdk = 37`, `targetSdk = 36`, `minSdk = 29`, `composeBom = "2026.09.00"` + `material3 = "1.5.0"` (`2026.11.01` requires `compileSdk >= 37.1`).
4. **RFC 6716 Opus TOC:** Config `16` (`0x80`) is 2.5 ms (`120` samples); CELT FB 20 ms (`960` samples) is config `31` (`0xF8`).
5. **Lint & Manifest Rules (`abortOnError = true`):** Always set `android:exported="true"` explicitly on `MainActivity` in `app/src/full/AndroidManifest.xml`, use `formatted="false"` on literal `%` strings, `LocalResources.current` over `LocalContext.current.resources`, `#` comments in ProGuard rules, avoid API 33+ `InputStream.readNBytes`, and keep `jniLibs { useLegacyPackaging = true }` so `youtubedl-android` extracts its native Python binaries cleanly.

---

## 5. Verified Baseline (`LibreSound` Hardened `yt-dlp` Update)

- **Unit Tests (`38 / 38` passed):**
  - `:core:dsp:test` (`16` tests across `DspProcessorsTest`, `EditTimelineTest`, `OggContainerTest`, `OpusContainerTest`, `WavAndFlacAndMp3Test`)
  - `:app:testLiteDebugUnitTest` (`11` tests across `CookieAndMetadataParserTest`, `DownloaderPresetTest`, `FormattersTest`)
  - `:app:testFullDebugUnitTest` (`11` tests across `CookieAndMetadataParserTest`, `DownloaderPresetTest`, `FormattersTest`)
- **Builds & Lint:**
  - `lintLiteDebug` (`0` errors), `lintFullDebug` (`0` errors)
  - `assembleLiteRelease`: **2.97 MB** (`3,119,169` bytes), `0` `INTERNET` permission
  - `assembleFullPreview`: `arm64-v8a` **20.38 MB**, `armeabi-v7a` **18.92 MB**, `x86` **20.08 MB**, `x86_64` **20.40 MB**, `universal` **60.13 MB**
- **CI Workflow:** `.github/workflows/android.yml` runs tests, lint, and builds `litePreview` + `fullPreview` APK artifacts on push/dispatch.

---

## 6. Recommended Next Steps

1. Test `fullPreview` (`arm64-v8a`) on device with real URLs, Netscape/JSON `cookies.txt`, preview info, and in-app `yt-dlp` updater.
2. Add interactive `MixOverlayDialog`, split-export both halves, real-time DSP preview, and `:engine:ffmpeg` flavor when requested.
3. Fill `fdroid/com.libreopus.app.yml` with the tagged GitHub commit, `Binaries:`, and `AllowedAPKSigningKeys` when publishing publicly.
