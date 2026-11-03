# LyricBar V2 — Component and Interaction Catalogue

**Purpose:** Define reusable component behaviour and visual responsibilities without assuming exact source-file names or forcing a fixed layout.

> These are design contracts, not instructions to create every component as a new composable. During the source audit, map each contract to existing composables and reuse/refactor only where justified.

---

## 1. Component principles

1. Components consume the active Material 3 theme. No fixed accent colours.
2. Components adapt to parent constraints and content; no device-specific fixed widths/heights.
3. Use semantic slots and state rather than duplicating UI for every screen.
4. Keep visual styling separate from playback, persistence and domain behaviour.
5. Prefer existing components and existing state holders when they already satisfy the contract.
6. Every interactive control needs a meaningful accessible label, state and action.
7. Apply glass effects selectively; not every component should be a translucent card.

## 2. Surface and background building blocks

### 2.1 Cinematic artwork background

**Purpose:** Now Playing atmospheric background.

**Inputs/data:** Current artwork (may be null/loading/invalid), active theme roles, playback/track state only where already available.

**Visual behaviour:**
- Artwork contributes a soft background image layer.
- A theme-derived gradient and contrast scrim protect foreground content.
- Image treatment remains restrained and does not compete with the compact foreground artwork.
- If artwork is missing, use a stable theme-derived fallback.
- Do not derive the app accent palette from artwork.

**Responsive behaviour:** Fill the available parent region; do not assume a device aspect ratio or screen height. Foreground content must remain independently laid out and scrollable where needed.

### 2.2 Calm themed background

**Purpose:** Lyrics, Settings, History, Search and About.

**Visual behaviour:** Lower contrast and less atmospheric intensity than Now Playing. Use the active theme and subtle tonal depth. Avoid heavy continuous animation.

### 2.3 Frosted surface

**Purpose:** Primary glass cards, navigation pill, focused panels and selected contextual surfaces.

**Visual behaviour:** Component-specific translucency, controlled background diffusion, thin theme-aware outline and soft shadow. Use semantic theme roles and preserve text contrast.

**Fallback:** If supported backdrop blur is unavailable or too expensive, use a translucent Material 3 surface with subtle tonal layering. Do not add a new dependency without approval.

### 2.4 Tonal surface

**Purpose:** Dense preference rows, lyrics list regions and secondary information.

**Visual behaviour:** More opaque than a frosted surface. Prioritise readability, hierarchy and clear grouping.

### 2.5 Theme-derived glow modifier/treatment

Use only for meaningful emphasis such as a primary playback state or active lyric. Glow tint comes from the active theme. It is not a universal decoration and must be safe to omit when performance or reduced-motion conditions require it.

---

## 3. Navigation components

### 3.1 Floating bottom navigation pill

**Content:** The existing four top-level destinations, with current icons/labels and routes mapped from the source audit.

**Visual behaviour:** Floating frosted-glass pill, thin outline, soft elevation and a clear active indicator derived from the active theme.

**Behaviour:** Preserve existing navigation route semantics, back-stack behaviour and selected-tab behaviour. Keep visible on main screens as approved.

**Responsive behaviour:** Adapt to available width and system insets. Do not impose a fixed width that clips labels on narrow phones or wastes excessive space on larger windows. Ensure content is not obscured behind it.

### 3.2 Minimal top app bar

Screen title plus contextual actions. Use each screen's actual needs; do not force the same action set on every destination. Respect cutouts and status-bar insets.

### 3.3 Shared transition wrapper/pattern

Use shared-element or fluid transitions only for visually related elements that can be mapped reliably. Fall back to a simple fade/slide when navigation state, lifecycle or performance makes a shared transition fragile. Do not block input or playback state updates during transition.

---

## 4. Playback and lyric components

### 4.1 Compact album artwork tile

**Purpose:** Foreground artwork on Now Playing.

- Use a compact, visually prominent presentation within the adaptive layout.
- Preserve image aspect ratio; avoid stretching.
- Provide loading and missing-artwork states.
- Do not hard-code the artwork's width/height to a screenshot. Constrain it relative to available space and keep it bounded so it does not crowd lyrics or playback controls.
- Ensure accessibility text is meaningful when artwork carries information; otherwise avoid redundant announcements.

### 4.2 Track information block

Order: song title, artist, playback source badge.

- Allow long titles/artist names to wrap or otherwise adapt according to existing product behaviour; never overlap actions.
- Preserve playback source identity and existing metadata source.
- Avoid unnecessary extra metadata in the Now Playing screen.

### 4.3 Playback source badge

Compact supporting label, not a large callout. Derive tonal treatment from the active theme. Keep label readable at larger font scale and avoid truncation that hides essential source identity.

### 4.4 Live Lyrics card

**Purpose:** Show current and next lyric on Now Playing.

- Large frosted surface.
- Current lyric is primary; next lyric is secondary and visually quieter.
- Follow current playback synchronisation state; do not create a second timing source.
- Handle missing lyrics, loading, no active lyric and long lines gracefully.
- Text remains legible over any artwork/background state.
- Card adapts vertically to text/font scale and can participate in the screen's scroll behaviour.

### 4.5 Lyrics list row

- Timestamp and lyric content use the existing data model.
- Active line gets a soft translucent theme-derived highlight.
- Surrounding lines are visually dimmed without becoming unreadable.
- Avoid a glow or large card around every row.
- Long text wraps/scrolls according to the existing lyric setting and current behaviour.
- Provide a clear active state for TalkBack that does not rely only on colour.

### 4.6 Active lyric highlight

A soft translucent layer behind the active line. It must adapt to light/dark palettes and preserve contrast. Animation style and active weight follow the user's existing animation selection; do not impose a new lyric animation globally.

### 4.7 Timing-delay chip

- Compact chip in collapsed state.
- Expands on tap to expose the existing delay adjustment actions/control.
- Reuse the same interaction pattern on Now Playing and Lyrics.
- Preserve actual timing values, increments, persistence and state ownership from the current app.
- Keep expanded controls within available width; wrap/reflow rather than clip.
- Expose current delay value and actions to accessibility services.

### 4.8 Playback controls

Preserve the existing control arrangement and playback actions. Redesign their styling only. The main action may receive stronger theme-derived emphasis, but its size and placement must remain adaptive. Touch feedback must be immediate; no decorative animation may delay the action.

---

## 5. Settings components

### 5.1 Settings category card

Groups related settings. Use frosted or tonal treatment according to content density. Cards expand/collapse on the main Settings page and expose expanded state accessibly.

### 5.2 Preference row

A simple row containing title, optional description/current value and the existing control/action. Use a tonal, readable surface or subtle divider; avoid wrapping every row in its own large card.

Preserve each setting's existing key, default, persistence and functional semantics.

### 5.3 Theme palette preview tile

- Shows a representative preview of the existing palette in the current theme mode.
- Clearly distinguish selected, previewed and applied states.
- Previewing does not commit or persist the palette.
- The final apply path must follow the app's existing theme selection model.
- Do not add palette names/options that do not already exist.

### 5.4 Animation option row and preview

Each existing animation option appears in a simple list with an individual preview. Preview must not permanently change the selected animation or playback state. Preserve actual animation identifiers, settings and runtime behaviour.

### 5.5 Advanced settings group

Collapsed by default. Preserve all existing advanced controls and settings. Expanded state should not unexpectedly reset when a user changes unrelated preferences unless current UX already defines that behaviour.

### 5.6 App information card

Compact card in Settings linking to a dedicated About page. Show only information already available in the app (name, version and relevant links). Do not hard-code version strings.

---

## 6. History components

### 6.1 Recent history region

A recent list at the top of History, followed by grouped history. Use current history data and existing sorting semantics.

### 6.2 History group header

Groups: **Today**, **Yesterday**, **Earlier**. Group boundaries must be calculated using the device's appropriate local date/time rules and existing stored timestamps. Handle empty groups by omitting them.

### 6.3 Compact history row

Thumbnail, track title and artist. Use flexible width and text wrapping/truncation appropriate to the existing interaction. Keep row height content-aware and accessible; do not force a fixed row height that clips large text.

### 6.4 History search

Searches existing playback history only. Preserve existing query/data behaviour if present; if new search capability is needed, keep it scoped to history and do not alter storage schema without approval.

### 6.5 Individual delete and clear-all actions

- Individual delete action applies to the selected history item only.
- Clear-all requires a confirmation step.
- Do not clear history as a side effect of navigation or UI state changes.
- Reuse existing repository/DAO paths and database semantics.

### 6.6 Empty history state

Use a blurred, music-themed background with a clear explanation and useful next step. Keep the background subdued and accessible. Do not invent history entries or sample data.

---

## 7. Search and import components

### 7.1 Lyrics search entry

Search icon in the Lyrics screen header. Opens the dedicated full-screen search experience.

### 7.2 Full-screen search

- Dedicated screen with search input and results.
- Suggestions and results only; no recent-search section.
- Preserve existing provider calls, query semantics, cancellation and error handling.
- Keep keyboard/IME insets correct; results remain visible above the keyboard.
- Search must not restart or interfere with active playback.

### 7.3 Search result row

Compact row displaying song title, artist and source. Use current result model fields. Keep rows responsive for long titles and larger text. Do not show invented ratings, artwork or metadata.

### 7.4 Native LRC file picker

Use the platform's existing/native document picker flow where available. Preserve supported file types and URI permission handling. Do not read file contents into an unbounded memory buffer.

### 7.5 LRC preview and confirmation

- Preview parsed lyrics before persistence.
- Show useful parse/format errors.
- Saving happens only after explicit user confirmation.
- Cancel/back leaves existing saved/manual lyrics unchanged.
- Preserve current parser, source association, database path and import persistence behaviour.

### 7.6 Refresh action

Refresh icon with a subtle in-progress state. Disable or safely coalesce duplicate refresh actions according to existing repository behaviour. Show existing success/error feedback patterns without inventing a new provider state machine.

---

## 8. Floating overlay components

### 8.1 Overlay container

- Default visual mode follows the existing transparent/frosted setting.
- Transparent mode remains visually transparent; frosted mode uses supported blur/translucency.
- Rounded corners and customisable width.
- Preserve existing position and resizing capabilities.
- Do not create a new blur-intensity setting.

### 8.2 Overlay lyric text

- White lyric text with subtle active-theme-derived glow.
- System font by default; preserve current font options.
- Preserve current long-line, single-line and multiline behaviour.
- Respect existing font-size and opacity settings.
- Use the selected existing animation style and polish its presentation; do not change its semantics.

### 8.3 Overlay control reveal

Controls appear when the user taps the overlay, following existing interaction/lock behaviour. They must not permanently cover lyrics. Preserve existing controls and lock/unlock semantics. Controls should disappear according to existing timeout/interaction rules if those already exist; do not invent new timeout behaviour without approval.

### 8.4 Overlay blur

Blur only when the existing frosted mode is enabled. Respect Android version and performance capability. If blur cannot be provided, preserve the expected frosted appearance with a safe translucent fallback and do not crash or silently change user preferences.

### 8.5 Overlay stability requirements

Must preserve MediaSession sync, play/pause and session removal handling, drag/touch handling, permissions, position/sizing, timing offsets and animation settings. Visual work must not alter playback state transitions.

---

## 9. About and updater components

### 9.1 About page

Dedicated page linked from the Settings app-information card. Display app metadata from the actual project at runtime; never hard-code version names/numbers in UI copy.

### 9.2 Update availability indicator

Small indicator when a newer compatible release is available. Avoid a large blocking modal. Respect existing app lifecycle and check-frequency rules.

### 9.3 Release notes card

Expandable card on About. Render release notes safely and handle missing/empty notes. Do not treat release text as executable content.

### 9.4 Download progress

Show progress, percentage and file size where reliable values are available. Handle unknown content length honestly rather than showing fabricated percentages. Prevent duplicate downloads and support safe retry/resume according to implementation capability.

### 9.5 Verification and installer handoff

- Verify the downloaded APK using a trusted expected SHA-256 digest from release metadata.
- A checksum mismatch blocks installation and exposes a clear error.
- Validate package identity/version and preserve expected signing-certificate continuity where the Android APIs allow.
- Hand off to Android's package installer using the correct platform flow and user-visible consent.
- Never silently install, bypass installer UX or claim installation succeeded before Android reports the result.

### 9.6 Update checking and failure states

- Check on app launch with sensible frequency limits and no repeated network calls on every recomposition/resume.
- Expose manual retry from About if supported by existing architecture.
- On failure, show useful error details, retry and a GitHub release link.
- Handle offline state, rate limits, missing release assets, interrupted download, insufficient storage, checksum failure and installer cancellation.
- Do not make the updater block use of the current installed app.

---

## 10. Dialogs, sheets, loading and feedback

### 10.1 Dialogs and bottom sheets

Choose treatment by importance and context. Use a clearer, more opaque Material 3 surface for destructive confirmation and critical errors; use frosted treatment for low-risk contextual actions where legibility allows. Keep actions explicit and accessible.

### 10.2 Loading indicators

Use subtle, lightweight progress indicators. Avoid long-running shimmer across large areas unless there is a clear need. Preserve informative loading/error states in search, artwork, lyrics and updater flows.

### 10.3 Haptics

Subtle haptics for meaningful user actions only. Respect platform capability and existing settings. Do not add repeated haptics to every lyric tick or animation frame.

### 10.4 Reduced motion

Respect Android's system animation/reduced-motion preference. Where supported, provide an app-level option. Reduce or replace large movement, scale, blur and glow animations while retaining clear state changes and feedback.

---

## 11. Responsive component checklist

Every component must be reviewed for:

- Narrow phone width and larger phone width.
- Portrait and landscape.
- Larger font scale and display size.
- Long song title, artist and lyric strings.
- Missing/loading artwork and empty states.
- Status/navigation/IME insets.
- TalkBack semantics, touch target and focus order.
- Dynamic light and dark palettes.
- Reduced-motion and performance fallback.

Never fix a component by shrinking important text below a usable size or clipping content. Reflow, wrap or scroll instead.
