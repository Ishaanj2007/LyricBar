# LyricBar V2 — Screen and Flow Specifications

**Approved scope:** Four existing main destinations, Floating Lyrics overlay, Search/Import, About/Updater.  
**Navigation rule:** Preserve the current four destinations and route structure discovered in the source audit. Do not assume or invent route names.

---

## 1. Shared application shell

### 1.1 Main destinations

- Preserve the four existing main destinations and their current order/route semantics.
- Use a floating frosted-glass bottom navigation pill.
- Keep the bottom navigation visible on the main screens.
- Use minimal top app bars with a screen title and contextual actions.
- Keep system insets correct and reserve enough content space so the navigation pill does not cover the last item.

### 1.2 Adaptive shell

- Phone-first design with adaptive behaviour for larger windows.
- Support portrait and landscape wherever practical.
- In landscape, preserve a vertical content flow while adapting sizing and spacing; do not force an unrelated multi-column dashboard.
- Use constraints and available window dimensions rather than hard-coded screen sizes.
- The shell must work with system font scaling, display-size changes, gesture navigation and three-button navigation.

### 1.3 Global transitions

Use shared-element/fluid transitions only where the relationship between source and destination is clear and implementation is stable. Otherwise use a lightweight native transition. Transitions must not delay playback actions or lyric state updates.

---

## 2. Now Playing

### 2.1 Purpose

The cinematic home for the currently active track. It should focus on artwork, track identity, Live Lyrics and playback—not become a dashboard.

### 2.2 Content hierarchy

1. Minimal top app bar and contextual actions already present in the current design.
2. Cinematic background composed from current album artwork and a gradient derived from the active theme.
3. Compact foreground album-art tile.
4. Song title.
5. Artist.
6. Playback source badge.
7. Large frosted Live Lyrics card containing current and next lyric.
8. Existing playback controls in their existing arrangement.
9. Compact expandable timing-delay chip.
10. Floating bottom navigation pill.

The layout may scroll if the content does not fit. Do not compress the lyrics or playback controls merely to force all content above the fold.

### 2.3 Background and artwork

- Current artwork supplies the cinematic background image layer; theme gradient and contrast treatment keep foreground content readable.
- The foreground artwork remains compact rather than dominating the screen.
- Preserve artwork aspect ratio and handle missing/loading/error states.
- The background should not be a source of dynamic theme accent colour.
- On narrow screens and landscape, the artwork/background treatment must adapt without fixed dimensions.

### 2.4 Track information

Show title, artist and source badge in that order after the artwork. Handle long metadata without colliding with controls. Preserve current metadata/provider source behaviour.

### 2.5 Live Lyrics card

- Large frosted card.
- Current lyric is the primary line; next lyric is secondary and quieter.
- Use the active theme for subtle surface tint, outline and optional emphasis.
- Keep current/next text readable over changing album artwork.
- Handle no lyrics, loading, no active line, long lines and missing track metadata.
- Use the existing playback/lyric state source; do not create a parallel ticker or timebase.

### 2.6 Playback controls

Keep the existing arrangement and all existing actions. Update styling, state indication and feedback only. Ensure touch actions remain immediate. Do not add playback controls or rearrange them without approval.

### 2.7 Timing delay

Collapsed state is a compact chip. Tapping it reveals the existing timing adjustment controls. Reuse the same pattern on Lyrics. Preserve the current adjustment increments, underlying timing semantics and persistence.

### 2.8 Responsive rules

- Compact artwork scales relative to available layout space, with a sensible upper bound based on parent constraints—not a fixed screen width.
- The Live Lyrics card may grow with text and font scale.
- Allow vertical scrolling when needed.
- Prevent bottom navigation from obscuring controls or the end of content.
- Test long titles, long artists, large font settings, small width and landscape.

---

## 3. Lyrics

### 3.1 Purpose

A focused, readable view for following the current song's lyrics and controlling lyric-related actions.

### 3.2 Main layout

1. Minimal top app bar with title and contextual actions.
2. Search icon in the header.
3. Separate frosted control panel for lyric actions and timing controls.
4. Scrollable lyric list.
5. Floating bottom navigation pill.

**No mini-player on this screen.** Do not add one as a convenience or as an unrequested shared component.

### 3.3 Lyrics list and active state

- Use a continuous scrollable list based on the existing lyric data.
- Highlight the active lyric with a soft translucent background derived from the active theme.
- Emphasise the active line and dim surrounding lines without making them unreadable.
- Do not add a large frosted card around every row.
- Preserve current timestamps, parsing and lyric alignment semantics.
- Keep long-line behaviour unchanged, including existing single-line/multiline options.

### 3.4 Auto-follow versus manual scrolling

Automatic follow must keep the active lyric visible during playback, but it must not continuously fight a user's manual scroll gesture.

Before implementing, inspect the current scroll/follow state logic and preserve existing semantics. If the current behaviour does not support a clear manual-scroll pause/resume rule, document the gap and propose a minimal behaviour for approval rather than silently inventing a new interaction. Any follow animation must be lightweight and avoid jumping on every small state update.

### 3.5 Controls and timing

Use a separate frosted control panel. Include only controls that already exist. Timing delay uses the same compact expandable chip pattern as Now Playing and the same underlying timing state.

### 3.6 Search entry

Search icon in the Lyrics header opens the dedicated full-screen search flow specified below.

### 3.7 Responsive rules

- Lyrics list uses the remaining available vertical space and scrolls.
- Control panel can wrap/reflow on narrow widths.
- Active lyric highlight adapts to multiline content and larger font scale.
- System insets and bottom navigation must not cover content.
- Do not force a mini-player or fixed-height lyric rows.

---

## 4. Settings

### 4.1 Purpose

An organised preferences surface. It should be easier to scan than a large collection of unrelated glass cards.

### 4.2 Main Settings page

- One main scrollable Settings page.
- Hybrid structure: category cards with simple preference rows inside.
- Sections expand/collapse on the main page.
- Advanced settings are collapsed by default.
- Preserve every existing setting and its behaviour.
- Use frosted treatment selectively; dense preference rows should prioritise clarity.

### 4.3 Theme section

Redesign existing theme controls visually while preserving their existing data and behaviour:

- Follow system is the default appearance mode.
- Existing light/dark/system mode behaviour remains available as currently implemented.
- Existing wallpaper dynamic-colour setting is preserved.
- Existing curated palettes remain available.
- Palette preview happens before apply; cancel leaves the active palette unchanged.
- No new palette names, custom colour picker or album-art colour extraction unless separately approved.
- Both light and dark previews must be represented accurately.

### 4.4 Animation section

- Simple list of existing animation options.
- Each option can be previewed individually.
- Preview does not permanently alter the saved animation selection unless the user explicitly applies it through the existing settings flow.
- Preserve animation identifiers, runtime semantics and existing speed/behaviour options.

### 4.5 Advanced settings

Collapsed by default. Preserve every existing advanced setting and its stored preference. Expanding the section must not reset values.

### 4.6 App information card and About

A compact app-information card appears in Settings and links to a dedicated About page. Populate app name/version and other metadata from the actual application at runtime rather than hard-coded labels.

### 4.7 Responsive rules

- Preference rows adapt to available width and font scale.
- Labels and descriptions wrap rather than overlap controls.
- Expanded sections remain scrollable and preserve state appropriately.
- On wider windows, use additional space carefully; do not invent a new navigation structure.

---

## 5. History

### 5.1 Purpose

A compact, useful view of previously played tracks.

### 5.2 Layout

- Hybrid structure: recent list followed by grouped history.
- Group labels: Today, Yesterday, Earlier.
- Compact rows contain thumbnail, track title and artist.
- Keep the existing track interaction behaviour exactly as implemented today.
- Do not invent a new tap action or change whether a track loads, opens lyrics or navigates elsewhere.

### 5.3 Grouping

Use existing history timestamps and correct local-date boundaries. Omit empty groups. Handle time-zone/date changes safely and do not modify stored timestamps just for display grouping.

### 5.4 Management

- Search history.
- Delete an individual track.
- Clear all history with an explicit confirmation dialog.
- Preserve existing storage/database behaviour. Reuse current repository/DAO pathways; no database schema change solely for visual redesign.

### 5.5 Empty state

Show a blurred music-themed background with a concise explanation and a useful next step. Keep contrast high enough for text and controls. Do not fabricate sample history entries.

### 5.6 Responsive rules

History rows must adapt to long titles and artist names, larger fonts and narrow widths. Use scrollable lists for content. Keep delete actions discoverable without making every row visually crowded.

---

## 6. Floating lyrics overlay

### 6.1 Purpose

The always-available lyric presentation. Preserve the user's existing overlay behaviour and customisation.

### 6.2 Appearance

- Dynamic transparent-to-glass appearance according to the existing overlay settings.
- White lyric text with a subtle active-theme-derived glow.
- Blur only when frosted mode is enabled.
- Rounded corners and customisable width.
- Preserve current position, resizing and repositioning capabilities.
- Controls appear when tapped.
- Preserve existing animation choices and polish their visual implementation.

### 6.3 Typography and content

- System font by default.
- Preserve all existing font choices, font size, opacity, single-line/multiline and long-line settings.
- Preserve the current behaviour for long lyric lines.
- Active lyric weight adapts to selected animation style only where this does not damage readability or alter animation semantics.

### 6.4 Interaction and lock state

Preserve existing overlay tap, control reveal, drag, resize, lock/unlock and reposition behaviour. Do not invent new hide timers or gestures. Inspect current lock state implementation and verify lock actions actually change the expected interaction state.

### 6.5 Functional invariants

- MediaSession metadata and playback state remain authoritative.
- Pause/resume/stop/session removal continue to be handled as currently designed.
- Lyrics remain synchronised with playback and timing offset.
- Dragging and resizing do not break lyric updates or touch routing.
- Permission denial/revocation is handled gracefully.
- No visual effect may delay state changes or freeze the lyric ticker.

### 6.6 Responsive rules

The overlay must adapt to available display width and user-selected size. Do not hard-code a single overlay width or screen position. Respect display cutouts, orientation and system gesture areas while preserving current positioning rules.

---

## 7. Lyrics Search

### 7.1 Entry and screen

The Lyrics header search icon opens a dedicated full-screen search experience.

### 7.2 UI

- Search input at the top with appropriate IME behaviour.
- Suggestions and results only; do not add recent-search history.
- Compact results display song title, artist and source.
- Use the active theme and calmer glass/tonal surfaces.
- Empty, loading, no-result, offline and error states must be represented.

### 7.3 Behaviour

Preserve existing lyrics providers, query model, request cancellation, result mapping and source semantics. Search must not interfere with playback or silently change the active song/lyrics before the user chooses a result.

---

## 8. Manual LRC import flow

1. User taps Import from the existing Lyrics controls.
2. Open the native Android file picker for supported LRC files.
3. Read and parse the selected file using the existing parser and safe URI access.
4. Show a preview of parsed lyrics and useful parse errors.
5. User explicitly confirms Save/Apply.
6. Only then persist using the existing import persistence path.
7. On cancel, parse failure or save failure, preserve the previously active/saved lyrics.

Do not change supported formats, parser semantics or database structure as part of the visual work. If the existing parser has limitations, report them separately.

---

## 9. Refresh flow

- Refresh icon uses a subtle progress state while the existing fetch operation runs.
- Prevent confusing duplicate actions by reusing existing in-flight request/state handling.
- Preserve provider fallback and error semantics.
- Do not show fabricated progress percentages when the provider offers no measurable progress.

---

## 10. About and in-app updater

### 10.1 About page

Dedicated About page linked from the compact app-information card in Settings. Use runtime app metadata. Do not hard-code version numbers or release descriptions.

### 10.2 Update indicator

Small indicator when a newer compatible update is available. Do not block app startup or cover playback with a modal.

### 10.3 Release notes

Expandable release notes card on About. Handle empty/malformed notes. Display release content as text, not executable markup.

### 10.4 Update check policy

Check on app launch with sensible frequency limits. The check must not occur on every recomposition or every lifecycle callback. Respect offline state and GitHub API rate limits. Manual retry should be available in About.

### 10.5 Download experience

Show a progress bar with percentage and file size when the server provides reliable values. Clearly indicate unknown progress if total size is unavailable. Handle interrupted downloads, storage errors and retries without corrupting a previously installed version.

### 10.6 Verification and installer handoff

- Verify SHA-256 against a trusted expected digest from release metadata.
- Checksum mismatch blocks the install action.
- Validate package identity and version; preserve signing-certificate continuity where supported.
- Handoff to Android's package installer and let Android obtain required user consent.
- Never silently install or bypass Android's installer.

### 10.7 Failure states

Show a useful error, retry action and GitHub release link. Cover offline state, API rate limiting, missing APK asset, incomplete download, storage failure, checksum mismatch and installer cancellation. The currently installed app remains usable throughout.

---

## 11. Global interaction and motion

- Buttons: ripple plus subtle scale.
- Cards: subtle elevation and border change.
- Screen entry: shared-element transitions where useful; simpler fallback otherwise.
- Loading: subtle animated progress indicators.
- Dialogs/sheets: treatment based on importance; destructive confirmations prioritise clarity and contrast.
- Haptics: subtle feedback for meaningful actions only.
- Reduced motion: respect Android's system preference and an app-level option where supported.

Do not animate in a way that delays playback controls, lyric synchronisation, scrolling or touch handling.

---

## 12. Cross-screen acceptance checklist

Before a screen is considered complete, verify:

- Both light and dark theme.
- Dynamic wallpaper palette and each existing curated palette where testable.
- Small and large phone widths.
- Portrait and landscape.
- Larger font scale and display size.
- Long and missing metadata/artwork.
- System bars, cutouts, IME and bottom navigation insets.
- TalkBack labels, state and focus order.
- Reduced-motion behaviour.
- Existing screen navigation and route behaviour.
- Playback and lyric synchronisation unaffected.
- No clipped or overlapping controls.
- No hard-coded screen dimensions or device-specific layout assumptions.
