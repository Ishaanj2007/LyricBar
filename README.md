
# LyricBar 

LyricBar is a native Android app that displays synchronized song lyrics in a customizable floating overlay while music plays in another app. It includes a lyrics screen, playback history, local lyric import, animation settings, and wallpaper-based dynamic colour support on compatible Android versions.

> **Project status:** Under active development. Features and behaviour may change while bugs are being fixed. Please test on your device before relying on timing-sensitive lyrics.

## A little note about the code 🤖

LyricBar is kinda **vibe-coded** — AI tools helped write and tweak parts of the code. So yeah, some bugs or weird AI moments might slip through  If something breaks, don't roast the app too hard. Open an issue with what happened and how to reproduce it, and I'll check it out and try to fix it ASAP.

AI helped write the whole code, but I'm still the dev maintaining the project. Bug reports, suggestions, and PRs are welcome!

## Features ✨

- **Floating Lyrics Overlay** — Display lyrics over other apps while music plays.
- **Media Detection** — Detect song metadata and playback state through Android's media-session integration.
- **Online Lyrics** — Fetch synchronized lyrics using LRCLIB, with Lyrics.ovh as a plain-lyrics fallback.
- **Local Lyrics Import** — Import lyric files using Android's document picker.
- **Multiple Animations** — Apple-style Karaoke, Smooth Line Transition, Fade In / Fade Out, Blur Reveal, Typewriter, and Instant.
- **Customizable Overlay** — Adjust text size, opacity, font, alignment, position, dimensions, animation, and single-line mode.
- **Lyrics Timing Adjustment** — Adjust lyric synchronization with per-track offset support.
- **Playback History** — Store playback history locally using Room.
- **Saved Preferences** — Keep app settings between sessions using DataStore.
- **Dynamic Colours** — Material 3 interface with wallpaper-based colours on supported Android versions and fallback theme presets.

> Precise word-by-word karaoke highlighting depends on the lyric source providing word-level timestamps. Standard LRC files usually provide line-level timing only.

## Requirements 📱

- Android Studio with a compatible Android Gradle Plugin and Kotlin toolchain.
- Android SDK matching the project's configured `compileSdk`.
- Minimum Android version: **Android 7.0 (API 24)**.
- Internet connection for online lyric lookup.

### Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- Room Database
- Jetpack DataStore
- Kotlin Coroutines
- Retrofit
- OkHttp

## Permissions 🔐

LyricBar requires certain permissions for its features:

- **Display over other apps:** Required for the floating lyrics overlay.
- **Notification listener access:** Used to detect active media sessions and retrieve playback information.
- **Internet access:** Required for online lyric lookup.
- **Notifications:** Used for the foreground overlay service on supported Android versions.
- **Vibration:** Used for interactions where applicable.

You may need to enable overlay permission and notification-listener access manually in Android Settings.

## Getting Started 🚀

### 1. Clone the repository

```bash
git clone https://github.com/ishaanj2007/LyricBar.git
```

### 2. Open the project

1. Open Android Studio.
2. Select **Open**.
3. Choose the cloned LyricBar project folder.
4. Allow Gradle to sync.
5. Install any Android SDK components requested by Android Studio.

### 3. Run the app

Connect an Android device with USB debugging enabled or start an emulator.

Select the `app` run configuration and click **Run**.

> The current project ZIP did not include `gradlew` or `gradlew.bat`. Build through Android Studio, or add a Gradle wrapper using a compatible Gradle installation before running the commands below.

### Command-line build

After adding the Gradle wrapper:

**Linux / macOS**
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

**Windows**
```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

## Testing 🧪

Unit tests are included for areas such as:

- LRC parsing
- Karaoke highlighting logic
- Smooth line transition speed
- Fade and blur reveal animation logic
- Animation consistency and performance-related logic

The tests need to be run in a compatible environment to confirm that they pass.

## Project Structure 📂

```text
.
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   ├── data/
│   │   │   ├── domain/
│   │   │   ├── service/
│   │   │   └── ui/
│   │   ├── res/
│   │   └── assets/
│   └── test/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── README.md
```

*The exact package names and folder structure may differ in the current source.*

## Privacy 🔒

- Song titles, artists, and playback information are used to identify lyrics and synchronize them with playback.
- Online lyric searches send the required song metadata to the selected lyric provider.

## License 📄

No license has been specified yet. If you want others to legally reuse, modify, or distribute this project, add a suitable `LICENSE` file.

Until then, the default copyright restrictions apply.

---

Made with love, and a bit of vibe coding
