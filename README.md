<p align="center">
  <img src="Screenshots/lyricbarlogo.png" alt="LyricBar Logo" width="128" height="128" />
</p>

<h1 align="center">LyricBar</h1>

<p align="center">
  <b>A native Android app for synchronized floating lyrics, frosted-glass aesthetics, and ambient music immersion.</b>
</p>

<p align="center">
  <a href="https://github.com/Ishaanj2007/LyricBar/releases/latest"><img src="https://img.shields.io/badge/Release-v2.2.0-6C5CE7?style=flat-square&logo=android" alt="Latest Release" /></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024--35)-00B894?style=flat-square&logo=android" alt="Android Version" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin" alt="Kotlin" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20M3-4285F4?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-FDCB6E?style=flat-square" alt="License" /></a>
</p>

<p align="center">
  <a href="#screenshots-">Screenshots</a> •
  <a href="#features-">Features</a> •
  <a href="#multi-provider-lyrics-architecture-">Lyrics Providers</a> •
  <a href="#download-">Download</a> •
  <a href="#requirements-">Requirements</a> •
  <a href="#permissions-">Permissions</a> •
  <a href="#building-from-source-">Building</a> •
  <a href="#testing--verification-">Testing</a> •
  <a href="#known-limitations-">Limitations</a> •
  <a href="#contributing-">Contributing</a> •
  <a href="#license-">License</a>
</p>

---

## Screenshots 📸

| Now Playing | Synchronized Lyrics | Overlay Controls | Playback History | Settings & Themes |
| :---: | :---: | :---: | :---: | :---: |
| <img src="Screenshots/Screenshot1_LyricBar.png" width="190" alt="Now Playing Screen" /> | <img src="Screenshots/Screenshot2_LyricBar.png" width="190" alt="Synchronized Lyrics View" /> | <img src="Screenshots/Screenshot3_LyricBar.png" width="190" alt="Overlay Controls" /> | <img src="Screenshots/Screenshot4_LyricBar.png" width="190" alt="Playback History Log" /> | <img src="Screenshots/Screenshot5_LyricBar.png" width="190" alt="Settings & Themes" /> |

---

## Features ✨

- **Multi-Provider Lyrics Engine** — Broad song coverage powered by four synchronized providers (`LRCLIB`, `lrc.red`, `KuGou`, `LyricsPlus`) with customizable fallback priority.
- **Synchronized Provider Selection** — Choose between `Automatic` and manual provider selection directly from either the **Lyrics Screen** or **Settings Screen**, sharing the same persistent preference.
- **Redesigned Cinematic Lyrics Screen** — Streamlined song identity header with album art thumbnail, combined frosted-glass Provider & Lyrics Delay panel, and a floating interactive seek mini-player.
- **Floating Lyrics Overlay** — Live synchronized lyrics rendered above any active music or media app (Spotify, YouTube Music, Apple Music, local players, etc.).
- **Cinematic Ambient Artwork Glow** — Dynamic diffused ambient illumination engine derived from active album artwork palettes, blending naturally into the frosted glass backdrop without harsh rectangular backplates.
- **Main Screen Live Lyrics Motion** — Smooth active-lyric animations with progress sweep highlights and responsive viewport tracking.
- **Intelligent Auto-Scroll with Reading Pause** — Smooth automatic lyric following that pauses instantly when user scrolls manually and provides a clean one-tap resume affordance.
- **Automatic Media Detection** — Detects active playback state, track metadata, and album artwork via Android's `MediaSession` API with strict track-identity cache isolation.
- **Safe & Bounded Local LRC Import** — Bounded, memory-safe local `.lrc` import system with UTF-8/UTF-16/BOM auto-detection and file-size protection.
- **Multiple Animation Styles** — Smooth Karaoke glow sweeps, Line Transitions, Fade, Blur Reveal, and Typewriter animations synchronized to playback timing.
- **Customizable Overlay Engine** — Adjust font size (6sp–24sp), opacity, alignment, position, dimensions, local Google Fonts, and toggle single-line mode.
- **Real-Time Offset Adjustment** — Fine-tune lyric synchronization on the fly with per-track offset adjustments (`-0.5s`, `-0.1s`, `+0.1s`, `+0.5s`).
- **Dark-Only Ambient Design** — Subtle backdrop scrim that lets active album artwork glow through translucent frosted-glass surfaces.
- **Curated & Dynamic Theming** — Curated pastel palettes (Rose Quartz, Lavender Mist, Peach Ember, Sage Serenity), dark presets, and Android 12+ Material You dynamic colors.
- **Playback History** — Chronological timeline grouping (*Today*, *Yesterday*, *Earlier This Week*, *Previous Month*) stored locally with cached artwork thumbnails.
- **Hardened In-App GitHub Updater** — Built-in updater with cryptographic SHA-256 validation, package identity matching, downgrade prevention, and signing certificate lineage verification for safe installations from GitHub Releases.
- **Battery & Memory Optimized** — Hardware Canvas rendering locked to display refresh rate, LRU artwork memory caching, and zero-polling lifecycle awareness.

---

## Multi-Provider Lyrics Architecture 🎵

LyricBar v2.2.0 introduces a modular, 4-tier lyrics provider architecture to maximize song coverage and line synchronization:

| Priority | Provider | Identification Method | Strengths |
| :---: | :--- | :--- | :--- |
| **1** | **LRCLIB** | Title, Artist, Album, Duration | Community-driven synchronized lyrics catalog with broad international coverage. |
| **2** | **lrc.red** | ISRC, Title, Artist | Exact recording identification via ISRC with synchronized line timing. |
| **3** | **KuGou** | Title, Artist | Extensive catalog with resilient fuzzy matching and Base64-decoded LRC timestamps. |
| **4** | **LyricsPlus** | Title, Artist, Album, Duration | Multi-mirror synchronized lyrics aggregator. |

### Provider Selection & Cache Isolation
- **Automatic Mode (`AUTO`):** Queries `LRCLIB → lrc.red → KuGou → LyricsPlus` in fallback priority order and stops at the first valid lyrics result.
- **Manual Mode:** When a specific provider (`LRCLIB`, `lrc.red`, `KuGou`, or `LyricsPlus`) is selected, LyricBar queries **only** that provider without falling back, presenting the missing-lyrics state if unavailable.
- **Dual-Screen Synchronization:** The provider selector is accessible from both the **Lyrics Screen** (compact floating frosted menu) and the **Settings Screen** (*Lyrics Provider* section), backed by a shared Jetpack DataStore preference.
- **Cache Isolation:** Room database caching isolates entries by provider (`<identityKey>::provider::<name>`). Manual provider selection never returns cached lyrics from a different provider, and switching providers preserves existing valid cache records without deleting them.
- **Transparent Source Attribution:** The active lyrics source is displayed subtly (`Source: LRCLIB`, `Source: lrc.red`, `Source: KuGou`, `Source: LyricsPlus`, or `Source: Local Import`).

---

## 📲 Download

👉 **[Download the Latest Release](https://github.com/Ishaanj2007/LyricBar/releases/latest)**

1. Download `LyricBar-2.2.0.apk` (or architecture-specific variant) from the latest release assets.
2. Open the APK on your Android device.
3. Allow installation from unknown sources if prompted.
4. Launch LyricBar and grant the required permissions (Overlay & Notification Listener).

*Future updates can be checked, downloaded, and verified directly from **Settings → Check for Updates**.*

---

## Requirements 📱

- **Minimum Version:** Android 7.0 (API level 24)
- **Target Version:** Android 15 (API level 35)
- **Permissions:** Notification Listener & Display Over Other Apps
- **Connectivity:** Internet connection for online lyric lookup and updates

### Tech Stack

| Component | Library / Framework |
| :--- | :--- |
| **Language** | Kotlin 2.0.21 |
| **UI Framework** | Jetpack Compose & Material 3 |
| **Blur & Glassmorphism** | Haze 1.3.1 |
| **Local Persistence** | Room 2.6.1 & Jetpack DataStore 1.1.1 |
| **Networking** | OkHttp 4.12.0 & Retrofit 2.11.0 |
| **Concurrency** | Kotlin Coroutines & StateFlow |
| **Architecture** | MVVM with Lifecycle-Aware Compose |

---

## Permissions 🔐

LyricBar requests only the permissions necessary for its core functionality:

- **Display over other apps (`SYSTEM_ALERT_WINDOW`):** Required to display the floating lyrics overlay above other apps.
- **Notification listener access:** Used to read media metadata and synchronize lyrics with the active media session.
- **Internet (`INTERNET`):** Used to fetch lyrics from LRCLIB and check for releases on GitHub.
- **Foreground Service (`FOREGROUND_SERVICE`):** Keeps the floating overlay active while music is playing in the background.
- **Request Install Packages (`REQUEST_INSTALL_PACKAGES`):** Enables the in-app updater to pass verified APKs to the Android Package Installer.
- **Vibration (`VIBRATE`):** Haptic feedback for control interactions.

*Note: Overlay permission and Notification Listener access must be granted manually in Android Settings upon first launch.*

---

## Building from Source 🚀

### 1. Clone the repository

```bash
git clone https://github.com/Ishaanj2007/LyricBar.git
cd LyricBar
```

### 2. Build via Gradle

The repository includes an automatic debug keystore restoration hook in `build.gradle.kts` so fresh clones can assemble debug builds immediately without developer-specific machine setup.

**Linux / macOS**
```bash
# Ensure Unix wrapper has execution permissions
chmod +x gradlew

# Build Debug APK
./gradlew :app:assembleDebug

# Run Unit Tests (231 tests)
./gradlew :app:testDebugUnitTest

# Compile Android Instrumented Tests
./gradlew :app:compileDebugAndroidTestKotlin
```

**Windows**
```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:compileDebugAndroidTestKotlin
```

---

## Testing & Verification 🧪

LyricBar maintains strict automated test coverage across its core data, parsing, timing, and security layers:

| Test Scope | Count | Status | Notes |
| :--- | :---: | :---: | :--- |
| **Local JVM Unit Tests** | 231 | **Passing (100%)** | 26 test suites in `app/src/test/java/` covering LRCLIB, lrc.red, KuGou, LyricsPlus, multi-provider fallback, provider preference isolation, LRC import bounds, HUD timing, Track Identity, Database migrations, and Updater verification. |
| **Executed Test Cases** | 231 | **231 Passed (0 failed)** | Verified via `./gradlew :app:testDebugUnitTest`. |
| **Instrumented Tests** | 1 | **Configured & Compiles** | Located in `app/src/androidTest/java/` using AndroidX Test Runner and JUnit4. |
| **Total Test Methods** | 232 | — | 231 local unit tests + 1 instrumented test. |

---

## Project Structure 📂

```text
.
├── .github/
│   ├── workflows/
│   │   └── ci.yml                    # Automated GitHub Actions CI workflow
│   └── ISSUE_TEMPLATE/
├── Screenshots/
│   ├── Screenshot1_LyricBar.png      # Now Playing Screen
│   ├── Screenshot2_LyricBar.png      # Synchronized Lyrics View
│   ├── Screenshot3_LyricBar.png      # Floating Overlay Controls
│   ├── Screenshot4_LyricBar.png      # Playback History Log
│   ├── Screenshot5_LyricBar.png      # Settings & Material Themes
│   └── lyricbarlogo.png              # App Brand Logo
├── app/
│   ├── build.gradle.kts              # Application build config & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── LyricBarApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/             # Room DB, DataStore, Repositories & Updater
│       │   │   ├── domain/           # Lyrics Timing Engine, Parser & Providers
│       │   │   ├── service/          # MediaListener & Overlay Foreground Service
│       │   │   └── ui/               # Compose Screens, Glass Components & Theme
│       │   └── res/                  # Vector Assets, Local Fonts & XML Providers
│       ├── test/                     # 207 Local Automated Unit Tests (24 test suites)
│       └── androidTest/              # Instrumented Android Tests
├── gradle/
│   ├── libs.versions.toml            # Version Catalog
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts                  # Root buildscript with clean-clone key restoration
├── settings.gradle.kts
├── debug.keystore.base64             # Base64-encoded debug keystore for clean-clone reproducibility
├── LICENSE
└── README.md
```

---

## Known Limitations ⚠️

1. **Dynamic Theming on Android 11 & Earlier**: Android 12+ (API 31+) is required for system Material You wallpaper color extraction. On Android 7.0–11, curated pastel palettes (Rose Quartz, Lavender Mist, Peach Ember, Sage Serenity) and dark presets provide rich customization.
2. **System Overlay Restrictions**: On certain Android vendor skins (MIUI/HyperOS, ColorOS, EMUI), background pop-up permissions and aggressive battery optimizations may require manual configuration in system settings.
3. **Protected Streams**: Media apps that do not publish standard Android `MediaSession` metadata or playback positions cannot provide timing data for synchronization.

---

## Contributing 🤝

Contributions are welcome! Please follow these guidelines:

1. Fork the repository and create a feature branch (`git checkout -b feature/amazing-feature`).
2. Make your changes and ensure all tests pass:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:compileDebugAndroidTestKotlin
   ```
3. Commit your changes with clear, descriptive commit messages.
4. Push to your branch and open a Pull Request.

---

## License 📄

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Maintained by <a href="https://github.com/Ishaanj2007">Ishaan</a> • Open source and built with Jetpack Compose.
</p>
