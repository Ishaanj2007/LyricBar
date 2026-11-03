# Implementation Plan — LyricBar V2 Settings Screen UI/UX Refinement

## Title & Summary
**LyricBar V2 — Cinematic Settings Screen Architecture & Visual Polish**  
Bring the LyricBar Settings screen into the same approved edge-to-edge cinematic design system as Now Playing and Lyrics. This plan delivers a full-screen blurred artwork backdrop, collapsible frosted-glass accordion sections, visual 2-column curated palette swatch cards, lighter translucent protective scrims, and complete preservation of all DataStore preferences.

---

## User Review & Critical Decisions

The user confirmed the following design and interaction decisions for the Settings screen:
1. **Section Layout**: Collapsible accordion cards for each section, allowing users to focus on one category at a time and preventing an overwhelmingly long continuous list.
2. **Curated Color Palettes**: Visual 2-column color swatch cards displaying the actual theme primary, container, and surface accents with clear selected indicators.
3. **Background Scrim**: Lighter translucent scrim showing more vibrant artwork colors while maintaining crisp readability across all text controls and switches.

---

## 1. Overview & Core Concept

Settings is the control center of LyricBar. Currently, it uses a plain, separate dark theme canvas with flat dividers and unsegmented lists.

This implementation:
- Integrates the shared full-screen `CinematicArtworkBackground` across Now Playing, Lyrics, and Settings in `MainActivity.kt`.
- Uses `LocalHazeState` so every glass accordion section samples live blur from the blurred album art backdrop.
- Organizes all 8 existing preference categories into expandable, tactile frosted glass accordion cards.
- Refines all form controls (switches, sliders, chips, buttons) with 48dp minimum touch targets, smooth spring feedback, and high-contrast labels.
- Preserves every existing preference, DataStore flow, permission check, and callback without alteration.

---

## 2. User Experience & Visual Design

### 2.1 Full-Screen Shared Ambient Canvas
- The Settings screen connects directly to the shared `CinematicArtworkBackground` in `MainActivity.kt`.
- Extends seamlessly behind the status bar and bottom floating navigation capsule.
- A lighter translucent scrim allows colorful artwork tones to illuminate the background while keeping all labels and values easily readable.
- Zero duplicate artwork decoding or memory overhead; background remains solid and stable during preference changes.

### 2.2 Collapsible Accordion Sections
Each major category is housed in a dedicated `FrostedCard` (18dp corner radius, subtle gradient outline, live backdrop blur):
- **Clickable Header**: Leading category icon, category title, brief summary of active setting (e.g., `"Dark • Obsidian Mint"`, `"Overlay Active"`), and an animated expand/collapse chevron.
- **Smooth Expansion**: Animated using `Modifier.animateContentSize(animationSpec = tween(220))`.
- **Default State**: Most commonly accessed sections (Floating Overlay & Timeline, Theme & Material Style) expanded by default; secondary sections cleanly collapsed.

### 2.3 Visual 2-Column Palette Swatches
- Under **Theme & Material Style**, curated presets (Obsidian Mint, Midnight Azure, Sunset Amber, Cyber Violet) are rendered in an attractive 2-column grid.
- Each swatch card displays:
  - Preset name and subtitle.
  - Color preview pills showcasing the primary accent, container tone, and background color.
  - A prominent checkmark badge and glowing border when selected.
- Selecting a palette immediately updates the global theme and persists to DataStore.

### 2.4 Control Elements & Interaction Refinement
- **Switches**: Styled with M3 colors, prominent thumb contrast, and 48dp interactive area.
- **Sliders**: Labeled with current real-time values (e.g., `"85%"`, `"320 dp"`, `"+120 px"`), smooth dragging, and haptic feedback.
- **Action Buttons**: Destructive actions (e.g., Clear Cache, Reset All Settings) require clear confirmation dialogs before executing.
- **Overlay Permissions**: Floating HUD controls check real Android system permissions (`Settings.canDrawOverlays`), guiding users to system settings if permissions are revoked.

---

## 3. Key Product Decisions & Trade-Offs

| Decision | Chosen Approach | Trade-Off / Rationale |
| :--- | :--- | :--- |
| **Section Layout** | Collapsible Accordion Frosted Cards | Keeps high density manageable, reduces vertical scrolling fatigue, and provides a polished modular appearance. |
| **Palette Selection** | 2-Column Visual Swatch Cards | Much higher visual fidelity than a plain text list or tiny radio button, allowing users to see actual theme harmonies. |
| **Scrim Density** | Lighter Translucent Scrim | Satisfies user preference for rich artwork color illumination while maintaining high text contrast through card glass surfaces. |
| **Shared Background** | Extend `CinematicArtworkBackground` in `MainActivity` | Eliminates tab-switching flickers, duplicate memory usage, and preserves edge-to-edge window insets. |
| **State Persistence** | Pure DataStore backing via `UserPreferencesRepository` | Zero risk of preference loss or corruption; maintains instant reactivity across the entire app. |

---

## 4. Technical Architecture & Component Mapping

```
+-----------------------------------------------------------------------------------+
|                                  MainActivity                                     |
|  - Renders CinematicArtworkBackground when currentTab in [NOW_PLAYING, LYRICS,   |
|    SETTINGS]                                                                      |
|  - Insets: StatusBars + NavigationBars handled cleanly                            |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                 SettingsScreen                                    |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | Header: "Settings" Title + Active Track / Artwork Status Summary            |  |
|  +-----------------------------------------------------------------------------+  |
|                                         |                                         |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 1: Floating Overlay & Timeline (Switch, Pos Lock, Align, Size)    |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 2: Lyrics Animation Style (Style Picker, Speed, Blur Radius)      |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 3: Overlay Geometry & Insets (Width, Height, Spacing Sliders)     |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 4: Theme & Material Style (Dynamic Color, Mode, 2-Col Swatches)   |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 5: Typography (Font Family Selection with Previews)               |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 6: Automation & Cache (Auto-fetch, Cache Size, Clear Database)    |  |
|  +-----------------------------------------------------------------------------+  |
|  | Accordion 7: About & App Info (Version, Licenses, Reset Configuration)      |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                             FloatingBottomNavPill                                 |
|  - Exact same approved navigation pill anchored above gesture bar                 |
+-----------------------------------------------------------------------------------+
```

---

## 5. Step-by-Step Implementation Steps

### Step 1: Extend Shared Background in `MainActivity.kt`
- Update `MainActivity.kt` so `CinematicArtworkBackground` renders for `AppTab.NOW_PLAYING`, `AppTab.LYRICS`, and `AppTab.SETTINGS`.
- Allow edge-to-edge container sizing so `SettingsScreen` manages its own status bar and navigation bar insets.

### Step 2: Implement Accordion Frosted Card Structure (`SettingsScreen.kt`)
- Remove redundant internal `CalmThemedBackground`.
- Construct a reusable `SettingsAccordionCard` composable:
  - Header with icon, title, optional summary chip, and animated expand icon.
  - Expandable body with `Modifier.animateContentSize()`.
- Group all 8 existing sections into their respective accordion containers.

### Step 3: Implement Visual 2-Column Palette Swatches (`SettingsScreen.kt`)
- Build a 2-column grid displaying the curated presets: Obsidian Mint, Midnight Azure, Sunset Amber, Cyber Violet.
- Render each card with live color pills for primary accent, container, and surface colors.
- Wire selection directly to `playbackManager.preferencesRepository.setThemePreset(preset)`.

### Step 4: Refine Control Components & Spacing (`SettingsScreen.kt`)
- Standardize switch rows, slider controls, and chip selectors.
- Ensure all clickable rows have ripple indications and 48dp touch targets.
- Set bottom scroll clearance to `120dp` so the last settings section scrolls completely clear of the floating navigation pill.

### Step 5: Verification & Testing
- Compile via `compile_applet`. (Completed: Build succeeded)
- Verify preference persistence and state updates. (Completed)
- Run unit and Robolectric tests via `gradle :app:testDebugUnitTest`. (Completed: 26 passed)

---

## 6. Execution Status: COMPLETED ✅

- [x] Step 1: Extended shared background in `MainActivity.kt`
- [x] Step 2: Implemented `SettingsAccordionCard` structure in `SettingsScreen.kt`
- [x] Step 3: Implemented visual 2-column palette swatches with live color pills
- [x] Step 4: Refined control spacing, edge-to-edge insets, and 120dp bottom clearance
- [x] Step 5: Verification & full test suite passed (26 unit and Robolectric tests)
