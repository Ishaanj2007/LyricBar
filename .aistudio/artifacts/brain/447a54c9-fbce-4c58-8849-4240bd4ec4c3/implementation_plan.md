# Apple Music-Style Floating Lyrics Overlay Engine

A high-performance, 60 FPS floating lyrics overlay rendered directly over third-party applications with Apple Music-style word-level synchronized highlighting, zero-allocation rendering, functional Left/Center/Right text alignment, and multi-line wrapping with smooth vertical scrolling.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed during clarification and will govern the floating overlay's visual behavior and text presentation:

- **Confirmed Decision 1: Default Text Alignment**: **Center** alignment is the default presentation style, with active user options for **Left**, **Center**, and **Right** in Settings. Alignment affects internal text layout only and does not displace the floating overlay's window position.
- **Confirmed Decision 2: Word Highlighting Style**: **Crisp step fill per word**, highlighting each sung word in bright opaque white while keeping un-sung words at readable inactive translucency (45% opacity), synchronized directly with media playback timestamps.
- **Confirmed Decision 3: Multi-Line Wrapping & Overflow Handling**: Long lyric lines wrap automatically to fit within the overlay's configured width (treated as a strict maximum layout boundary). When wrapped lines exceed the overlay height, the viewport performs a **smooth vertical auto-scroll** to keep the active sung phrase in full view.
- **Confirmed Decision 4: Architecture & Visual Boundary**: 100% transparent overlay window background with white-only typography. No album artwork, music cards, or opaque backgrounds in the overlay. Native lightweight Canvas rendering for steady 60 FPS on Pixel 9a.

---

### 1. Overview & Core Concept

- **What It Does**: Renders floating, real-time synchronized song lyrics directly over other apps (e.g., Spotify, YouTube Music, Apple Music, browsers, games). Lyrics highlight word-by-word with TTML timestamps and line-by-line with LRC fallback.
- **Target Audience / Persona**: Music enthusiasts who desire an unobstructed, Apple Music-quality karaoke and lyrics reading experience across the Android operating system while multitasking.
- **Key Value**: Delivers jitter-free, low-latency lyric synchronization with zero background clutter, accurate text wrapping within customized bounds, and negligible CPU footprint.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. *Floating Overlay Activation*: User enables floating lyrics in Auralis or media playback begins. A clean, fully transparent overlay appears with centered white lyrics.
  2. *Live Playback Synchronization*: As the song plays, words transition crisply from inactive translucent white to bright white. When a lyric is long, it wraps neatly across 2 or more lines. If it exceeds overlay height, it smoothly scrolls vertically to center the current line.
  3. *Alignment Customization*: In the Settings screen under **Lyrics Alignment**, the user can toggle between **Left**, **Center**, and **Right**. The floating overlay immediately re-renders with the selected alignment, persisting the preference in DataStore.
  4. *Overlay Bounds Editing*: In drag/edit mode, mint outlines and resize handles appear. Adjusting the width or height instantly recalculates text wrapping and boundaries. In normal playback, all borders and handles vanish.

- **Visual Identity & Theme**:
  - *Overlay Window*: `Color.TRANSPARENT` background (`PixelFormat.TRANSLUCENT`). No panel borders, gradient cards, or art containers.
  - *Typography*: Bold, crisp white text (`#FFFFFF`) with subtle anti-aliased drop shadow for contrast against light app backgrounds. Inactive words at `rgba(255, 255, 255, 0.45)`.
  - *Alignment Options*:
    - **Center**: Symmetrically balanced text for traditional lyric display.
    - **Left**: Left-aligned editorial format.
    - **Right**: Right-aligned lyric positioning.
  - *Motion & Transitions*: Subtle upward translation and alpha fade between lyric lines; smooth continuous vertical scroll for multi-line lyrics exceeding the overlay height.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Native Android Canvas Rendering (`MinimalOverlayView`) vs. Compose Window Hierarchy**
  - *Chosen Approach*: Optimize the custom native `View` (`MinimalOverlayView`) using pre-calculated layout models and direct `Canvas` drawing rather than embedding a full ComposeView inside the `WindowManager` overlay.
  - *Why*: Eliminates Compose composition passes and snapshot state allocations on every 16ms animation frame. Native Canvas rendering maintains rock-solid 60 FPS on Pixel 9a with under 2% CPU usage.
  - *Alternatives Considered*: Jetpack Compose in WindowManager (tested; triggers excessive recomposition overhead on rapid playback position updates).

- **Decision 2: Static Multi-Line Wrapping Engine with Vertical Auto-Scroll vs. Horizontal Marquee**
  - *Chosen Approach*: Replace the horizontal marquee with an intelligent multi-line word wrap engine (`StaticLayout` / pre-measured line breaker) that strictly respects overlay width. When multiple lines exceed the container height, smoothly scroll vertically to keep the active line visible.
  - *Why*: Solves user issue of text extending off-screen; readers naturally read multi-line lyrics top-to-bottom without waiting for marquee scroll cycles.
  - *Alternatives Considered*: Horizontal marquee (causes clipped text, reading delays, and conflicts with word highlighting).

- **Decision 3: Dedicated "Lyrics Alignment" Setting (Left / Centre / Right)**
  - *Chosen Approach*: Add an explicit three-way segment control labeled **Lyrics Alignment** (Left, Centre, Right) in `SettingsScreen.kt`, backed by `PreferencesKeys.OVERLAY_TEXT_ALIGN` in `UserPreferencesRepository.kt`.
  - *Why*: Fixes right-side text bias; ensures text alignment applies internally within the overlay layout width without shifting the overlay window's position on screen.

- **Decision 4: Zero-Allocation Animation Loop & Clock Synchronization**
  - *Chosen Approach*: Bind lyric progress directly to `MediaPlaybackManager.calculatedPositionMs` using a lightweight Choreographer or localized invalidation loop that halts completely when playback is paused, the overlay is hidden, or screen is off. Pre-allocate all `TextPaint`, `Rect`, and `Point` structures in memory.
  - *Why*: Prevents garbage collection pauses, frame stutter, and unnecessary battery drain.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│               MediaPlaybackManager                     │
│  - Playback State Flow (calculatedPositionMs, isPlaying)│
│  - Active Lyric Line Flow (word timings, line timing)   │
└──────────────────────────┬─────────────────────────────┘
                           │ State observation
                           ▼
┌────────────────────────────────────────────────────────┐
│                 OverlayService                         │
│  - WindowManager Application Overlay Management         │
│  - Observe Preferences (Alignment, Size, Font, Shadow) │
│  - Pause/Resume clock hooks on screen off/paused       │
└──────────────────────────┬─────────────────────────────┘
                           │ Layout & timestamp dispatch
                           ▼
┌────────────────────────────────────────────────────────┐
│             MinimalOverlayView (Native View)           │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Text Layout & Wrap Engine                        │  │
│  │ - Maximum width: Overlay Width - Padding         │  │
│  │ - Multi-line word wrapping                       │  │
│  │ - Left / Center / Right alignment math           │  │
│  └────────────────────────┬─────────────────────────┘  │
│                           │                            │
│  ┌────────────────────────▼─────────────────────────┐  │
│  │ 60 FPS Direct Canvas Renderer                    │  │
│  │ - Active word: Crisp white fill (#FFFFFF)        │  │
│  │ - Inactive words: 45% opacity translucent white  │  │
│  │ - Smooth vertical auto-scroll for active line    │  │
│  │ - Zero heap allocation during onDraw             │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

#### Component & Implementation Details

1. **`UserPreferencesRepository.kt`**:
   - Verify `OVERLAY_TEXT_ALIGN` defaults to `"CENTER"`.
   - Ensure clean persistence and flow emission of `overlayTextAlign` ("LEFT", "CENTER", "RIGHT").

2. **`SettingsScreen.kt`**:
   - Update alignment selector section to prominently display **Lyrics Alignment** with options **Left**, **Centre**, and **Right**.
   - Ensure buttons immediately trigger `setOverlayTextAlign` with instant responsive UI feedback.

3. **`MinimalOverlayView.kt`**:
   - **Multi-Line Text Layout Engine**:
     - Measure word widths and split lines into wrapped sub-lines whenever `totalWidth > availableWidth`.
     - Layout each line respecting `LEFT`, `CENTER`, and `RIGHT` alignment.
   - **Word Highlighting**:
     - Highlight sung words with crisp bright white and un-sung words with translucent white.
     - Support TTML word timestamps with smooth line progression.
   - **Vertical Viewport Management**:
     - Compute line Y-positions. If total height exceeds container, smoothly animate `scrollY` to center the currently active sung line.
   - **Performance & Zero-Allocation**:
     - Avoid creating objects in `onDraw`. Cache `StaticLayout` and wrapped line structures, rebuilding only when text, font size, alignment, or overlay width changes.
     - Invalidate only when playback is actively progressing.

4. **`OverlayService.kt`**:
   - Connect lifecycle events (screen off receiver, playback pause) to pause overlay animation cycles.
   - Immediately update layout and view properties on alignment or bounds change.

5. **Testing & Validation**:
   - Verify unit tests for TTML parsing and timing calculations.
   - Verify alignment changes update instantly and persist across restarts.
   - Verify multi-line wrapping with long lyric lines (no clipping or off-screen bleeding).
   - Verify 60 FPS smooth rendering and low CPU consumption.
