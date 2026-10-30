# Playback-Synchronized Adaptive Horizontal Scrolling for Long Lyrics

## Summary
Implement adaptive horizontal scrolling for long lyric lines in LyricBar that exceed the floating overlay container width. Scrolling will synchronize with real-time media playback position, dynamically scaling scroll velocity to the interval between the current lyric timestamp and the next lyric timestamp.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were clarified and confirmed during Phase 1:

- **Single-Line Mode Scope (Confirmed)**: Adaptive horizontal scrolling will apply when **Single-Line Lyrics** mode is active, preventing text from wrapping vertically and smoothly scrolling long lyrics from left to right. Short lyrics remain stationary with their configured alignment.
- **Speed Multiplier Control (Confirmed)**: Settings will provide a continuous slider from **0.5× to 1.5×** (default 1.0×) in the "Single-Line Lyrics" configuration section, scaling the scroll velocity without altering lyric timestamps or postponing transitions.
- **Final Lyric Fallback (Confirmed)**: For the last lyric line (or when no subsequent timestamp exists), the interval will be dynamically calculated from the remaining track duration, capped at a **4.0-second ceiling** (with a 1.0-second minimum) to prevent division by zero or uncontrolled movement.

---

## 1. Overview & Core Concept

- **What It Does**: LyricBar displays real-time lyrics in a floating, resizable overlay. When lyrics are longer than the overlay width, this feature detects the overflow and scrolls the text horizontally across the screen in lockstep with the music.
- **Target Audience**: Users listening to music with the floating lyrics overlay enabled on their Android device who want unobstructed, complete lyric lines in single-line mode without awkward vertical text clipping.
- **Key Value**: Delivers a seamless, synchronized reading experience where long lines automatically fit the playback pace: faster lines scroll more quickly, slower lines scroll gently, and stationary short lines preserve custom centering or alignment.

---

## 2. User Experience & Visual Design

### User Flows
1. **Configuring in Settings**:
   - Navigate to **Settings** > **Overlay & Display** > **Single-Line Lyrics**.
   - Toggle **Adaptive Lyric Scrolling** (ON/OFF).
   - Adjust **Scroll Speed Multiplier** slider (0.5× – 1.5×, step 0.1×, default 1.0×) with real-time speed label feedback (`0.8× (Relaxed)`, `1.0× (Normal)`, `1.3× (Fast)`).
2. **Overlay Playback Experience**:
   - **Short Lyric**: Fits comfortably within the measured container width. No scrolling occurs; text remains centered or aligned per user preference.
   - **Long Lyric**: Text exceeds container width (`curLineWidth > availableWidth`). Canvas translates horizontally from `0` to `maxScrollX` proportionally across `(nextTimestamp - currentTimestamp)`.
   - **Transition**: At the moment the media session reaches `nextTimestamp`, the next line smoothly takes over immediately, regardless of scroll completion.
   - **Pause/Seek**: Pausing freezes the scroll position mid-flight; seeking forward/backward recalculates the exact horizontal position corresponding to the new timestamp.

### Visual Styling & Canvas Boundaries
- **Container Clipping**: Rendering is strictly clipped to the existing overlay container bounds (`canvas.clipRect(...)`), preventing text from bleeding out onto the Android desktop or resizing the overlay window.
- **Typography & Consistency**: Retains user-selected Google Fonts (`Poppins`, `Inter`, `Roboto Mono`, etc.), text size (6sp–24sp), text opacity, and shadow settings.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Pure Playback-Derived Progress vs. Independent Timer / Animator
- **Chosen Approach**: Derive scrolling progress purely as a mathematical function of current playback position (`posMs`) against the line interval `[startMs, nextMs]`, evaluated in the overlay's existing frame loop (`postOnAnimation`).
- **Why**: Completely eliminates drift, desynchronization during pause/resume, and lag during seeking. Independent `ValueAnimator`s or coroutine timers fail when audio stalls, seeks, or loops.
- **Alternatives Considered**: Android `ObjectAnimator` on scrollX — rejected because seeking or pausing requires complex animator cancellation, reversing, and time-stretching logic.

### Decision 2: Timestamp-Proportional Velocity
- **Chosen Approach**: `intervalMs = (nextTimestampMs - currentLineStartMs)`. Speed = `maxScrollX / intervalMs`.
- **Why**: Guarantees that a line sung in 1.5 seconds finishes scrolling within 1.5 seconds, while a line sustained over 6 seconds scrolls calmly.
- **Alternatives Considered**: Fixed constant pixel-per-second scrolling — rejected because fast lines would be cut off before the next line begins, and slow lines would finish scrolling too early and sit blank.

### Decision 3: Preference Persistence & Defaults
- **Chosen Approach**: Persist `adaptive_lyric_scrolling` (Boolean, default `true`) and `lyric_scroll_speed_multiplier` (Float, default `1.0f`) in Jetpack DataStore via `UserPreferencesRepository`.
- **Why**: Follows the existing architectural patterns in LyricBar without new dependencies or database schema migrations.

---

## 4. Technical Architecture & Data Strategy

```
┌──────────────────────────────────────────────────────────────┐
│                    UserPreferencesRepository                 │
│  - adaptiveLyricScrolling: Boolean (default true)            │
│  - lyricScrollSpeedMultiplier: Float (0.5f - 1.5f)           │
└──────────────────────────────┬───────────────────────────────┘
                               │ StateFlow<UserPreferences>
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                       OverlayService                         │
│  - Combines active lyric line, current lyrics, preferences,  │
│    and playback track metadata                               │
│  - Extracts: currentLine, nextTimestampMs, trackDurationMs   │
└──────────────────────────────┬───────────────────────────────┘
                               │ updates view properties
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                     MinimalOverlayView                       │
│  - Measured layout: availableWidth, curLineWidth, maxScrollX │
│  - Clipping: canvas.clipRect(paddingLeft, 0, width-pad, H)   │
│  - Frame loop (postOnAnimation): computes posMs              │
└──────────────────────────────┬───────────────────────────────┘
                               │ delegates math
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                     LyricsTimingEngine                       │
│  fun calculateAdaptiveScrollProgress(                        │
│      currentLineStartMs: Long,                               │
│      nextTimestampMs: Long?,                                 │
│      trackDurationMs: Long?,                                 │
│      currentPositionMs: Long,                                │
│      speedMultiplier: Float                                  │
│  ): Float                                                    │
└──────────────────────────────────────────────────────────────┘
```

### Components to Update
1. **`LyricsTimingEngine.kt`**: Add `calculateAdaptiveScrollProgress(...)` with interval detection, 4.0s remaining-track fallback, and 0.5×–1.5× multiplier scaling.
2. **`UserPreferencesRepository.kt`**: Add DataStore keys and getter/setter helpers for `adaptiveLyricScrolling` and `lyricScrollSpeedMultiplier`.
3. **`MinimalOverlayView.kt`**:
   - Store `nextTimestampMs: Long?` and `trackDurationMs: Long?`.
   - Update `updateHorizontalScrollTarget(posMs)` to use `LyricsTimingEngine.calculateAdaptiveScrollProgress`.
   - Apply container clipping to ensure long text never leaks outside the overlay margins.
4. **`OverlayService.kt`**: Wire `nextTimestampMs` calculation by looking up the next line in `playbackManager.currentLyrics` with a strictly greater timestamp, plus `trackDurationMs`.
5. **`SettingsScreen.kt`**: Add the Adaptive Lyric Scrolling switch and continuous Speed Multiplier slider under Single-Line Lyrics.
6. **`KaraokeHighlightEngineTest.kt`**: Add unit tests verifying:
   - Stationary progress for short lyrics.
   - Slower scrolling for 4s interval vs 1s interval.
   - Multiplier scaling effect.
   - Final line fallback with remaining track time / 4s ceiling.
   - Pause/resume and seeking mathematical consistency.
