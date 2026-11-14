<p align="center">
  <img src="Screenshots/lyricbarlogo.png" alt="LyricBar Logo" width="128" height="128" />
</p>

<h1 align="center">LyricBar</h1>

<p align="center">
  <b>A native Android app for synchronized floating lyrics, adaptive landscape layouts, and customizable ambient music immersion.</b>
</p>

<p align="center">
  <a href="https://github.com/Ishaanj2007/LyricBar/releases/latest"><img src="https://img.shields.io/badge/Release-v2.5.0-6C5CE7?style=flat-square&logo=android" alt="Latest Release" /></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024--35)-00B894?style=flat-square&logo=android" alt="Android Version" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin" alt="Kotlin" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20M3-4285F4?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-FDCB6E?style=flat-square" alt="License" /></a>
</p>

<p align="center">
  <a href="#screenshots-">Screenshots</a> •
  <a href="#whats-new-in-v250-">What's New</a> •
  <a href="#features-">Features</a> •
  <a href="#multi-provider-lyrics-architecture-">Lyrics Providers</a> •
  <a href="#floating-hud--customization-">Floating HUD</a> •
  <a href="#automatic-updates--security-">Updates</a> •
  <a href="#download-">Download</a> •
  <a href="#requirements-">Requirements</a> •
  <a href="#permissions-">Permissions</a> •
  <a href="#building-from-source-">Building</a> •
  <a href="#testing--verification-">Testing</a>
</p>

---

## Screenshots 📸

| Now Playing | Synchronized Lyrics | Overlay Controls | Playback History | Settings & Themes |
| :---: | :---: | :---: | :---: | :---: |
| <img src="Screenshots/Screenshot1_LyricBar.png" width="190" alt="Now Playing Screen" /> | <img src="Screenshots/Screenshot2_LyricBar.png" width="190" alt="Synchronized Lyrics View" /> | <img src="Screenshots/Screenshot3_LyricBar.png" width="190" alt="Overlay Controls" /> | <img src="Screenshots/Screenshot4_LyricBar.png" width="190" alt="Playback History Log" /> | <img src="Screenshots/Screenshot5_LyricBar.png" width="190" alt="Settings & Themes" /> |

---

## What's New in v2.5.0 🚀

- **Automatic 48-Hour Update Checks & Notifications** — Background GitHub Releases checks via Android WorkManager every 48 hours (plus overdue launch catch-up checks), ETag (`304 Not Modified`) caching, notification deduplication, and Android 13+ permission handling.
- **Adaptive Landscape & Tablet Redesign** — Two-column responsive layouts across **Now Playing**, **Lyrics**, and **Settings** paired with an adaptive side navigation rail (`AdaptiveNavRail`) and full display-cutout / gesture-bar inset support.
- **Orientation-Aware Floating HUD** — Independent persistent X/Y coordinates and dimensions for **Portrait** and **Landscape** modes with safe cutout clamping and an interactive Orientation Mode chip in Settings.
- **1-Second Hold-to-Reposition & Drag Rollback** — Hold anywhere on the HUD for `1000 ms` to enter reposition mode with subtle neutral visual feedback, or let go cleanly; interrupted drags automatically roll back to original bounds.
- **Clean HUD Backdrop & Custom Lyric Colours** — Two neutral backdrop styles (**Black** and **Grey**) with opacity, corner radius, and padding controls, plus independent **Active** and **Inactive** lyric colour presets, HSV/Hex picker, and improved `0.64f` inactive-to-active lyric contrast.

---

## Features ✨

- **Multi-Provider Lyrics Engine** — Broad song coverage powered by four synchronized providers (`LRCLIB`, `lrc.red`, `KuGou`, `LyricsPlus`) with customizable fallback priority.
- **Synchronized Provider Selection** — Choose between `Automatic` and manual provider selection directly from either the **Lyrics Screen** or **Settings Screen**, sharing the same persistent preference.
- **Responsive Portrait & Landscape UI** — Streamlined **Now Playing**, **Lyrics**, **History**, and **Settings** screens that adapt seamlessly between compact phones (`FloatingBottomNavPill`) and wide/landscape screens (`AdaptiveNavRail` + two-column layouts).
- **Orientation-Aware Floating Lyrics Overlay** — Live synchronized lyrics rendered above any active music or media app (Spotify, YouTube Music, Apple Music, local players, etc.) with separate persistent **Portrait** and **Landscape** positioning profiles.
- **1-Second Hold-to-Reposition & 2D Resize** — Hold the HUD for 1 second to reposition it cleanly anywhere inside safe screen bounds (with automatic rollback on cancellation), short-tap to resync, or toggle 2D Drag & Resize handles in Settings.
- **Clean HUD Backdrop & Independent Lyric Colours** — Switch between a text-only HUD or crisp **Black** / **Grey** backdrops with independent **Active** and **Inactive** lyric colour customization, HSV/Hex colour picker, saved swatches, and contrast warnings.
- **Cinematic Ambient Artwork Glow** — Dynamic diffused ambient illumination engine derived from active album artwork palettes for in-app frosted-glass surfaces.
- **Intelligent Auto-Scroll with Reading Pause** — Smooth automatic lyric following that pauses instantly when the user scrolls manually and provides a one-tap resume affordance.
- **Automatic Media Detection** — Detects active playback state, track metadata, and album artwork via Android's `MediaSession` API with strict track-identity cache isolation.
- **Safe & Bounded Local LRC Import** — Bounded, memory-safe local `.lrc` import system with UTF-8/UTF-16/BOM auto-detection and file-size protection.
- **Multiple Animation Styles** — Apple-style Karaoke glow sweeps, Smooth Line Transitions, Fade, Blur Reveal, Typewriter, and Instant animations synchronized to playback timing.
- **Typography & Timing Precision** — Customize HUD text size, opacity, alignment, single-line mode, 12 bundled Google Fonts (`Inter`, `Poppins`, `Montserrat`, `Lora`, `Roboto Mono`, etc.), and per-track lyric offsets (`-0.5s`, `-0.1s`, `+0.1s`, `+0.5s`).
- **Curated & Dynamic Theming** — Follow System / Light / Dark modes, curated pastel palettes (*Rose Quartz*, *Lavender Mist*, *Peach Ember*, *Sage Serenity*), and Android 12+ Material You dynamic colors.
- **Playback History** — Chronological timeline grouping (*Today*, *Yesterday*, *Earlier This Week*, *Previous Month*) stored locally with cached artwork thumbnails.
- **Hardened & Automatic GitHub Updater** — Periodic 48-hour background checks with notification alerts, ETag caching, SHA-256 verification, package identity matching, downgrade prevention, and signing certificate lineage verification.
- **Battery & Memory Optimized** — Zero-allocation hardware Canvas draw loops locked to display refresh rate, LRU artwork memory caching, and lifecycle-aware synchronization.

---

## Multi-Provider Lyrics Architecture 🎵

LyricBar uses a modular, 4-tier lyrics provider architecture to maximize song coverage and line synchronization:

| Priority | Provider | Identification Method | Strengths |
| :---: | :--- | :--- | :--- |
| **1** | **LRCLIB** | Title, Artist, Album, Duration | Community-driven synchronized lyrics catalog with broad international coverage. |
| **2** | **lrc.red** | ISRC, Title, Artist | Exact recording identification via ISRC with synchronized line timing. |
| **3** | **KuGou** | Title, Artist | Extensive catalog with resilient fuzzy matching and Base64-decoded LRC timestamps. |
| **4** | **LyricsPlus** | Title, Artist, Album, Duration | Multi-mirror synchronized lyrics aggregator. |

### Provider Selection & Cache Isolation
- **Automatic Mode (`AUTO`):** Queries `LRCLIB → lrc.red → KuGou → LyricsPlus` in fallback priority order and stops at the first valid lyrics result.
- **Manual Mode:** When a specific provider (`LRCLIB`, `lrc.red`, `KuGou`, or `LyricsPlus`) is selected, LyricBar queries **only** that provider without falling back, presenting the missing-lyrics state if unavailable.
- **Dual-Screen Synchronization:** The provider selector is accessible from both the **Lyrics Screen** (frosted provider menu) and the **Settings Screen** (*Lyrics Provider* section), backed by a shared Jetpack DataStore preference.
- **Cache Isolation:** Room database caching isolates entries by provider (`<identityKey>::provider::<name>`). Manual provider selection never returns cached lyrics from a different provider, and switching providers preserves existing valid cache records without deleting them.
- **Transparent Source Attribution:** The active lyrics source is displayed subtly (`Source: LRCLIB`, `Source: lrc.red`, `Source: KuGou`, `Source: LyricsPlus`, or `Source: Local Import`).

---

## Floating HUD & Customization 🎛️

- **Orientation Profiles (`HudPositioningEngine`)**: Portrait and Landscape each persist independent `X`, `Y`, `Width`, and `Height` values in DataStore, clamped safely away from status bars, navigation bars, and display cutouts.
- **Touch Interaction (`HudHoldGestureController` & `HudDragTransactionCoordinator`)**:
  - **Short Tap**: Triggers lyric playback resync.
  - **1-Second Hold (`1000 ms`)**: Enters reposition mode with subtle neutral visual feedback; dragging updates the HUD smoothly and saves to the active orientation profile on release.
  - **Safety**: Cancels pending holds on touch slop, multi-pointer input, or control touches, and rolls back active drags to original coordinates if interrupted or cancelled.
- **Backdrop & Colour Hierarchy (`HudColorSystem`)**:
  - Optional backdrop with **Black** (`#0A0A0A`) or **Grey** (`#B8B8B8`) style, adjustable opacity (`0%–100%`), corner radius (`0–32 dp`), and horizontal/vertical padding.
  - Independent **Active Lyric** and **Inactive Lyric** colours via curated presets, full HSV/Hex custom picker, up to 8 saved custom swatches, contrast readability warnings, and a `0.64f` inactive opacity ratio so the currently sung line stays visually prominent.

---

## Automatic Updates & Security 🔄

- **48-Hour Periodic Checks (`UpdateCheckScheduler`, `UpdateCheckWorker`)**: Powered by Android WorkManager (`NetworkType.CONNECTED`, exponential backoff, `ExistingPeriodicWorkPolicy.KEEP`), paired with overdue app-launch checks (`>= 48h` since last successful check).
- **Shared Update Pipeline (`UpdateCheckEngine`)**: Both manual and automatic checks share a `Mutex`-protected coordinator, ignore drafts/prereleases, select ABI-compatible APKs, and use GitHub API **ETags** (`If-None-Match` / `304 Not Modified`) to save bandwidth and API quota.
- **Deduplicated Notifications (`UpdateNotifier`)**: Posts on channel `lyricbar_app_updates` only once per newer version (persisting `lastNotifiedUpdateVersion` strictly after notification delivery succeeds) and opens the **Update Dialog** in Settings when tapped.
- **Manual Confirmation Only**: Updates are never downloaded or installed silently — all installations require explicit user confirmation and pass package-name, version-downgrade, signing-certificate, and SHA-256 verification. Can be toggled at any time in **Settings → About & Developer → Automatic update checks**.

---

## 📲 Download

👉 **[Download the Latest Release](https://github.com/Ishaanj2007/LyricBar/releases/latest)**

1. Download `LyricBar-2.5.0.apk` (or architecture-specific variant) from the latest release assets.
2. Open the APK on your Android device.
3. Allow installation from unknown sources if prompted.
4. Launch LyricBar and grant the required permissions (Overlay & Notification Listener).

*Automatic update checks run every 48 hours by default, and manual checks are always available under **Settings → About & Developer → Check for Updates**.*

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
| **UI Framework** | Jetpack Compose & Material 3 (BOM 2024.10.01) |
| **Frosted Glass Effects** | Haze 1.3.1 |
| **Local Persistence** | Room 2.6.1 & Jetpack DataStore 1.1.1 |
| **Background Scheduling** | AndroidX WorkManager 2.10.0 |
| **Networking** | OkHttp 4.12.0 & Retrofit 2.11.0 |
| **Concurrency** | Kotlin Coroutines 1.9.0 & StateFlow |
| **Architecture** | MVVM with Lifecycle-Aware Compose |

---

## Permissions 🔐

LyricBar requests only the permissions necessary for its core functionality:

- **Display over other apps (`SYSTEM_ALERT_WINDOW`):** Required to display the floating lyrics overlay above other apps.
- **Notification listener access:** Used to read media metadata and synchronize lyrics with the active media session.
- **Internet (`INTERNET`):** Used to fetch lyrics from online providers and check/download verified releases on GitHub.
- **Post Notifications (`POST_NOTIFICATIONS`, Android 13+):** Used for the foreground overlay notification and optional update-available notifications (if declined, in-app update checks still appear in Settings).
- **Foreground Service (`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`):** Keeps the floating overlay active while music is playing in the background.
- **Request Install Packages (`REQUEST_INSTALL_PACKAGES`):** Enables the in-app updater to pass verified APKs to the Android Package Installer.
- **Vibration (`VIBRATE`):** Haptic feedback for hold-to-reposition and control interactions.

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

# Run Unit Tests (283 tests across 31 suites)
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

LyricBar maintains automated test coverage across its data, parsing, HUD positioning/gesture/colour, synchronization, and updater layers:

| Test Scope | Count | Status | Notes |
| :--- | :---: | :---: | :--- |
| **Local JVM Unit Tests** | 283 | **Passing (100%)** | 31 test suites in `app/src/test/java/` covering multi-provider fallback (`LRCLIB`, `lrc.red`, `KuGou`, `LyricsPlus`), LRC import bounds, HUD orientation positioning, 1s hold-to-reposition & drag rollback, HUD colour/contrast system, Room migrations, and 48h WorkManager updater / notification deduplication. |
| **Executed Test Cases** | 283 | **283 Passed (0 failed)** | Verified via `:app:testDebugUnitTest`. |
| **Instrumented Tests** | 1 | **Configured & Compiles** | Located in `app/src/androidTest/java/` using AndroidX Test Runner and JUnit4. |
| **Total Test Methods** | 284 | — | 283 local unit tests + 1 instrumented test. |

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
│   ├── build.gradle.kts              # Application build config & dependencies (v2.5.0)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── LyricBarApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/             # Room DB, DataStore, Repositories & 48h WorkManager Updater
│       │   │   ├── domain/           # Lyrics Timing Engine, Parser & Multi-Provider Clients
│       │   │   ├── service/          # MediaListener, Overlay Service & HudPositioningEngine
│       │   │   └── ui/               # Responsive Compose Screens, MinimalOverlayView & Theme
│       │   └── res/                  # Vector Assets, Bundled Google Fonts & XML Configs
│       ├── test/                     # 283 Local Automated Unit Tests (31 test suites)
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

1. **Dynamic Theming on Android 11 & Earlier**: Android 12+ (API 31+) is required for system Material You wallpaper color extraction. On Android 7.0–11, curated pastel palettes (*Rose Quartz*, *Lavender Mist*, *Peach Ember*, *Sage Serenity*) provide theme customization.
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
