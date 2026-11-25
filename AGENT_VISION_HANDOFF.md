# LibreOpus — Architectural Vision & Agent Handoff Brief

> **Target Audience:** Antigravity agents picking up **LibreOpus** on another workstation.
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
   - **60.02 MB** universal 4-ABI release APK (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).
   - Adds `INTERNET` permission + `android:extractNativeLibs="true"` + `ACTION_SEND` (`text/plain`) link sharing in `app/src/full/AndroidManifest.xml`.
   - Adds the **Media Downloader** tab (`DownloadScreen.kt` + `DownloadViewModel.kt`) with 4 ready-made `yt-dlp` presets:
     - **En İyi Kalite Ses İndir (`BEST_AUDIO`):** `-f bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio --no-playlist --no-mtime --restrict-filenames` → saves to `Music/LibreOpus` + one-tap **Open in Editor**.
     - **Sadece Resim İndir (`IMAGE_ONLY`):** `--skip-download --write-thumbnail --no-playlist --no-mtime --restrict-filenames` → saves to `Pictures/LibreOpus` + preview + **Use as Track Cover** / **Share**.
     - **En İyi Ses + Kapak Resmi (`AUDIO_AND_IMAGE`):** downloads both and opens the audio in the editor with the downloaded cover attached to `AudioMetadata`.
     - **Özel `yt-dlp` Betiği (`CUSTOM_SCRIPT`):** editable raw `yt-dlp` CLI flags (`PresetCommandBuilder.tokenizeArgs`).
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
5. **Lint & Manifest Rules (`abortOnError = true`):** Always set `android:exported="true"` explicitly on `MainActivity` in `app/src/full/AndroidManifest.xml`, use `formatted="false"` on literal `%` strings, `LocalResources.current` over `LocalContext.current.resources`, `#` comments in ProGuard rules, and `jniLibs { useLegacyPackaging = true }` so `youtubedl-android` extracts its native Python binaries cleanly.

---

## 5. Verified Baseline (v1.0.0)

- **Unit Tests:** All passed — `:core:dsp:test` (`16` tests across `DspProcessorsTest`, `EditTimelineTest`, `OggContainerTest`, `OpusContainerTest`, `WavAndFlacAndMp3Test`), `:app:testLiteDebugUnitTest` (`4` tests), `:app:testFullDebugUnitTest` (`4` tests).
- **Builds & Lint:** `assembleLiteRelease` (`2.89 MB`), `assembleLitePreview` (`2.90 MB`), `assembleFullRelease` (`60.02 MB`), `assembleFullPreview` (`60.03 MB`), `lintLiteDebug` (`0` errors), `lintFullDebug` (`0` errors).
- **Runtime Smoke Tests:**
  - `liteDebug` installed & launched on user's physical Android phone (`192.168.1.105:34687`).
  - `fullPreview` (`com.libreopus.app.preview`) cold-launched on `Pixel_8` AVD (`APP_PID=2275`, `0` `AndroidRuntime:E` crashes).

---

## 6. Recommended Next Steps

1. Collect user feedback from testing `lite-preview` and `full-ytdlp-preview` APKs on their physical phone (including `yt-dlp` preset behavior across real URLs).
2. Consider ABI-split APKs (`arm64-v8a` ~20 MB vs universal 60 MB) for direct GitHub Releases while keeping universal APKs for F-Droid reproducibility.
3. Add interactive `MixOverlayDialog`, split-export both halves, real-time DSP preview, and `:engine:ffmpeg` flavor when requested.
4. Fill `fdroid/com.libreopus.app.yml` with the tagged GitHub commit, `Binaries:`, and `AllowedAPKSigningKeys` once published to GitHub.
