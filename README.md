# LibreOpus

**Lightweight, 100% FOSS (GPLv3), Material You Audio Studio for Android.**

[Türkçe](#türkçe) | [English](#english)

---

## English

### Highlights
- **Wide Format Support:** Open & edit `.opus`, `.ogg`, `.mp3`, `.m4a`/`.aac`, `.flac`, `.wav` (8/16/24/32-bit int & float), `.amr`, `.webm`/`.mka`, and extract audio directly from videos (`.mp4`, `.mkv`, `.webm`). Export to `.opus`, `.ogg`, `.flac`, `.m4a`, or `.wav`.
- **Smart Direct Save for `.opus`:** When only trimming or updating tags on `.opus` files, LibreOpus can save directly without re-encoding.
- **Disk-Backed Non-Destructive Timeline:** Piece-table PCM timeline keeps RAM usage minimal even on hour-long recordings, with unlimited Undo/Redo history.
- **Pure-Kotlin Studio DSP:** Gain, Peak & ITU-R BS.1770 LUFS Normalization, 4-curve Fade In/Out, 7-Band Parametric/Graphic EQ with presets, Butterworth High/Low-Pass Filters, Noise Gate, Smart Silence Trimmer, Independent Tempo & Pitch (WSOLA + Cubic Hermite), Reverse, Channel Operations, Append & Mix/Overlay.
- **Two Editions:**
  - **`lite`:** Ultra-lightweight (~2.9 MB), 100% offline, zero `INTERNET` permission.
  - **`full`:** Adds `yt-dlp` (`youtubedl-android` library, no heavy FFmpeg bloat) with ready-made presets (*Best Audio*, *Image/Cover Only*, *Best Audio + Cover*, *Custom Script*) that open directly in the editor.
- **Material You / Material 3 Expressive:** Dynamic wallpaper colors, spring motion physics, AMOLED black dark mode, pinch-to-zoom 60–120 FPS waveform canvas.

### Building & Verified Developer Signature
```bash
# F-Droid reproducible release APK (lite offline or full)
./gradlew :app:assembleLiteRelease
./gradlew :app:assembleFullRelease

# Direct-installable preview APKs (R8-minified, debug-signed)
./gradlew :app:assembleLitePreview :app:assembleFullPreview
```

---

## Türkçe

### Öne Çıkanlar
- **Geniş Format Desteği:** `.opus`, `.ogg`, `.mp3`, `.m4a`, `.flac`, `.wav`, `.amr`, `.webm` açma ve videolardan ses ayıklama; `.opus`, `.ogg`, `.flac`, `.m4a`, `.wav` dışa aktarma.
- **Stüdyo DSP Efektleri:** Kazanç, Peak/LUFS normalizasyon, 4 eğrili Fade, 7-bant EQ, Butterworth filtreler, Gürültü Kapısı, Sessizlik Kırpma, bağımsız Tempo/Perde (WSOLA), Ters Çevir, Kanal İşlemleri, Birleştirme & Üst Üste Miksaj.
- **İki Sürüm:**
  - **`lite`:** Ultra hafif (~2.9 MB), tamamen çevrimdışı ve sıfır ağ izinli ses editörü.
  - **`full`:** Ek FFmpeg şişirmesi olmadan yalnızca `yt-dlp` ile *En İyi Kalite Ses İndir*, *Sadece Resim İndir*, *Ses + Kapak İndir* ve *Özel Betik* hazır betiklerini ekler.
- **Material You (Material 3 Expressive) & GPLv3:** Reklamsız, izleyicisiz ve F-Droid Reproducible Builds uyumlu.
