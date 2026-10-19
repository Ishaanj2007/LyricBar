# Auralis — Minimal Floating Lyrics & Media Detection

A native Android application providing live, time-synchronized lyrics floating over active music players, featuring a truly transparent, distraction-free **Minimal Floating Lyrics** overlay, edge-to-edge system bars placement, Room database caching, and millisecond sync precision.

---

## 1. Minimal Floating Lyrics Overlay

The primary differentiator of Auralis is its **Minimal Lyrics Mode**:

- **True Transparency**: The overlay window (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`) and root drawing surface are 100% transparent (`PixelFormat.TRANSLUCENT`). No card, pill fill, rounded rectangle background, border, gradient, or blur is rendered.
- **Pure White Lyrics**: Only the currently active line of lyrics is displayed in crisp `#FFFFFF` text.
- **Omission of Distractions**: Album artwork thumbnails, music player logos, artist/title headers, decorative buttons, and borders are strictly removed from the overlay.
- **Readability Contrast**: An optional subtle glyph shadow (`textShadowEnabled`) provides contrast against bright or white background apps without adding an opaque surface box.
- **Granular Small-Unit Typography (6sp to 15sp)**: Granular 1sp-step typography grid ranging from 6sp to 24sp (with dedicated 6sp, 8sp, 10sp, 12sp, 14sp, 18sp presets and live preview) for subtle, compact floating lyrics.
- **Horizontal Marquee Scrolling**: When a lyric line exceeds the available overlay width, a smooth, rhythmic right-to-left marquee animation gently scrolls the text within a clean, clipped container area without clipping words or text distortion.
- **Smooth Upward Animation**: When the active lyric line changes, the outgoing line glides upward while fading out, and the incoming line smoothly rises into place from below via an interpolated transition synchronized with playback.
- **Interactive In-App Karaoke**: The main app Lyrics screen features streaming-service-style karaoke highlighting (active line glow, timestamp tags, past-line fading, and tap-to-scrub time seeking).
- **Material Theme Style (Wallpaper vs. Curated)**:
  - **Wallpaper Dynamic Colors (Material You)**: Automatically derives accent and surface color schemes from your device wallpaper on Android 12+.
  - **4 Curated Dark Palettes**: Instant switching between Obsidian Mint, Midnight Azure, Sunset Amber, and Cyber Violet.
- **Zero Touch Interference**: Under normal playback, `FLAG_NOT_TOUCHABLE` ensures all taps, gestures, and keyboard interactions pass directly through to background apps.
- **Reposition / Drag Mode**: Can be activated via persistent notification or in-app settings to drag the lyrics to any coordinates on screen in real time.

---

## 2. Architecture & Components

```
┌─────────────────────────────────────────────────────────────┐
│                      Android System                         │
│  ┌───────────────────────┐       ┌────────────────────────┐ │
│  │ NotificationListener  │       │  WindowManager Overlay │ │
│  │ MediaSessionManager   │       │  TYPE_APPLICATION_     │ │
│  └───────────┬───────────┘       │  OVERLAY (Edge-to-Edge)│ │
│              │                   └───────────▲────────────┘ │
└──────────────┼───────────────────────────────┼──────────────┘
               │ Active Session                │ Active Line
               ▼                               │ (Over system bars)
┌───────────────────────────────┐  ┌───────────┴────────────┐
│      MediaPlaybackManager     │  │     OverlayService     │
│  - Tracks active player       │  │  - Transparent Window  │
│  - Real-time position ticker  │  │  - Tap-through & Drag  │
│  - StateFlow<PlaybackState>   │  │  - Persistent Notif    │
└──────────────┬────────────────┘  └───────────▲────────────┘
               │ Track Key                     │
               ▼                               │
┌───────────────────────────────┐              │ Current Line
│       LyricsRepository        │──────────────┘
│  - Room Database (Cache)      │
│  - LRCLIB Provider (Synced)   │
│  - Lyrics.ovh (Plain fallback)│
│  - Local LRC/TXT Parser (SAF) │
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│      Main UI (Jetpack M3)     │
│  - Now Playing & Demo Mode    │
│  - Full Lyrics Scrolling View │
│  - Settings & Screen Presets  │
│  - 50-Track Playback History  │
│  - Permissions Onboarding     │
└───────────────────────────────┘
```

### Core Components
- **`MediaListenerService`**: `NotificationListenerService` that observes active `MediaController` sessions.
- **`OverlayService`**: Foreground service managing `MinimalOverlayView` with `WindowManager`.
- **`AuralisDatabase`**: SQLite database storing:
  - `lyrics` table: cached synced and plain lyrics.
  - `song_history` table: last 50 played tracks with timestamps.
- **`UserPreferencesRepository`**: Backed by Jetpack `DataStore` preferences.
- **`LrcParser`**: High-performance parser supporting `[mm:ss.xx]`, `[mm:ss.xxx]`, multi-timestamp lines, and `[offset:+/-ms]` tags.
- **`LrcLibProvider`**: Primary synchronized lyrics provider querying `https://lrclib.net/`.
- **`LyricsOvhProvider`**: Fallback provider for plain lyrics.

---

## 3. Required Permissions

1. **`SYSTEM_ALERT_WINDOW` ("Display Over Other Apps")**:
   Allows floating lyrics to be drawn over other applications and over the status/nav bar regions.
2. **`BIND_NOTIFICATION_LISTENER_SERVICE` ("Media & Notification Access")**:
   Enables Android's `MediaSessionManager` to read the active track title, artist, playback position, and playback state. Personal notifications are never read or transmitted.
3. **`INTERNET` & `ACCESS_NETWORK_STATE`**:
   Used to query lyrics providers (LRCLIB and Lyrics.ovh).
4. **`POST_NOTIFICATIONS`**:
   Required on Android 13+ to display the persistent foreground overlay controls notification.

---

## 4. Build & Verification Instructions

### Building with Gradle
```bash
# Compile debug APK
gradle :app:assembleDebug

# Run unit tests (LRC parsing, timing, Robolectric tests)
gradle :app:testDebugUnitTest
```

### Manual Testing Guide
1. Launch Auralis and grant the two required permissions in the onboarding screen.
2. Start playing music in Spotify, YouTube Music, or tap **Test Demo** on the Now Playing screen.
3. Confirm that clean white lyrics appear on screen without any card background, music logo, or album art.
4. Go to **Settings** and use the **Vertical Screen Position** slider to float lyrics directly over the top status bar area (Y = 0) or bottom navigation bar area (Y = 1900+).
5. Switch on **Reposition / Drag Mode** to drag the lyrics directly on the screen, then switch it off to restore full tap-through.
6. Use the **-500ms / +500ms** offset buttons to adjust timing; verify that per-track offsets persist.
