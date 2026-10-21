# LyricBar 🎵

LyricBar is a native Android app that displays synchronized song lyrics in a customizable floating overlay while music plays in another app. It includes a lyrics screen, playback history, local lyric import, animation settings, Google lyrics file search, and wallpaper-based dynamic colour support on compatible Android versions.

> **Project status:** Under active development. Features and behaviour may change while bugs are being fixed. Please test on your device before relying on timing-sensitive lyrics.

---

## A Little Note About the Code 🤖

LyricBar is kinda **vibe-coded** — AI tools helped write and tweak parts of the code. So yeah, some bugs or weird AI moments might slip through.

If something breaks, don't roast the app too hard! Open an issue describing what happened and how to reproduce it, and I'll check it out and try to fix it ASAP.

AI helped write the code, but I'm still the developer maintaining the project. Bug reports, suggestions, and pull requests are welcome!

---

## Features ✨

- **Floating Lyrics Overlay** — Display lyrics over other apps while music plays.
- **Media Detection** — Detect song metadata and playback state through Android's media-session integration.
- **Online Lyrics** — Fetch synchronized lyrics using LRCLIB, with Lyrics.ovh as a plain-lyrics fallback.
- **Google Lyrics File Search** — Easily search Google for `.lrc`, `.ttml`, `.srt`, or `.txt` lyrics files based on active metadata.
- **Local Lyrics Import** — Import LRC and TTML lyric files using Android's document picker.
- **Multiple Animations** — Apple-style Karaoke, Smooth Line Transition, Fade In / Fade Out, Blur Reveal, Typewriter, and Instant.
- **Customizable Overlay** — Adjust text size, opacity, font, alignment, position, dimensions, animation, and single-line mode.
- **Lyrics Timing Adjustment** — Adjust lyric synchronization with per-track offset support.
- **Playback History** — Store playback history locally using Room.
- **Saved Preferences** — Keep app settings between sessions using DataStore.
- **Dynamic Colours** — Material 3 interface with wallpaper-based colours on supported Android versions and fallback theme presets.

> Precise word-by-word karaoke highlighting depends on the lyric source providing word-level timestamps. Standard LRC files usually provide line-level timing only.

---

## Requirements 📱

- Minimum Android version: **Android 7.0 (API 24)**.
- Target Android SDK: **Android 16 (API 36)**.
- JDK 17 or higher.
- Internet connection for online lyric lookups.

### Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- Room Database
- Jetpack DataStore
- Kotlin Coroutines & Flow
- Retrofit & OkHttp

---

## Permissions 🔐

LyricBar requires certain permissions for its features:

- **Display over other apps (`SYSTEM_ALERT_WINDOW`):** Required for the floating lyrics overlay.
- **Notification listener access (`BIND_NOTIFICATION_LISTENER_SERVICE`):** Used to detect active media sessions and retrieve playback information.
- **Internet access (`INTERNET`):** Required for online lyric lookup and web search.
- **Notifications (`POST_NOTIFICATIONS`):** Used for the foreground overlay service on supported Android versions.

You can enable overlay permission and notification-listener access in Android Settings or via the in-app Permissions screen.

---

## Building and Running 🛠️

The repository includes the Gradle wrapper (`gradlew` and `gradlew.bat`), so you do not need to install Gradle separately.

### Prerequisites

1. Install [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended).
2. Clone this repository:
   ```bash
   git clone https://github.com/ishaanj2007/LyricBar.git
   cd LyricBar
   ```

### Building via Command Line

- **On Linux / macOS:**
  ```bash
  ./gradlew assembleDebug
  ```

- **On Windows:**
  ```cmd
  gradlew.bat assembleDebug
  ```

### Running Tests

To run unit tests:
```bash
./gradlew test
```

### Building in Android Studio

1. Open Android Studio.
2. Select **Open** and choose the `LyricBar` project directory.
3. Allow Gradle to sync dependencies automatically.
4. Select a connected device or emulator and click **Run** (`Shift + F10`).

---

## Project Structure 📁

The project follows clean architecture principles separated by feature domains:

```
LyricBar/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/          # Room database, DAOs & entities
│   │   │   │   │   ├── model/          # Track & Lyric data structures
│   │   │   │   │   ├── preferences/    # DataStore preferences & font options
│   │   │   │   │   └── repository/     # CompositeLyricsRepository data source
│   │   │   │   ├── domain/
│   │   │   │   │   ├── lyricsengine/   # Timing engine & query builder
│   │   │   │   │   ├── parser/         # LRC & TTML parsers
│   │   │   │   │   └── provider/       # LRCLIB & Lyrics.ovh API providers
│   │   │   │   ├── service/            # OverlayService & MediaPlaybackManager
│   │   │   │   ├── ui/
│   │   │   │   │   ├── components/     # MinimalOverlayView & Compose widgets
│   │   │   │   │   ├── screens/        # Lyrics, Now Playing, Settings, History
│   │   │   │   │   └── theme/          # Material 3 colors, typography & fonts
│   │   │   │   ├── LyricBarApp.kt
│   │   │   │   └── MainActivity.kt
│   │   │   ├── res/                    # Drawables, layouts, strings.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/                       # Unit test suites
│   └── build.gradle.kts
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── LICENSE
└── README.md
```

---

## Privacy Policy 🔒

LyricBar respects your privacy:

- Song titles, artists, and playback information are used solely to identify lyrics and synchronize them with playback.
- Online lyric searches send required track metadata to public lyric APIs (LRCLIB, Lyrics.ovh) or Google Search when requested by the user.
- No personal data or listening history is collected, tracked, or transmitted to third-party analytics servers.

---

## License 📄

LyricBar is licensed under the [MIT License](LICENSE). Feel free to inspect, modify, and distribute the code in accordance with the license terms.
