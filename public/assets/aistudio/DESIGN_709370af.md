# LyricBar V2 — Design System

**Status:** Approved design direction  
**Scope:** Visual system and interaction language for V2  
**Primary platform:** Android  
**Implementation stack:** Preserve the existing Kotlin / Jetpack Compose / Material 3 architecture unless the source audit proves otherwise and the change is approved.

---

## 1. Product and visual identity

LyricBar V2 is a premium, cinematic lyrics companion with a custom visual identity. It should feel expressive while keeping lyrics readable and controls dependable.

### 1.1 Visual direction

- Custom hybrid design; do not copy another app's complete interface.
- Premium and cinematic, especially on Now Playing.
- Medium frosted-glass intensity: visible translucency and blur, but not glass on every surface by default.
- Thin translucent borders and soft, restrained shadows provide depth.
- Glow is derived from the active theme and used selectively.
- Keep hierarchy clear. Avoid excessive cards, competing highlights, decorative effects and dashboard-like clutter.

### 1.2 MeeTube reference boundary

Reference: `https://github.com/yashoncode/metube`

Use MeeTube only as a visual reference for:

- Frosted/translucent surface treatment.
- Background blur and diffusion.
- Restrained glow and lighting.

Do **not** copy its navigation, screen composition, component arrangement, branding, icons, typography system or product behaviour. LyricBar keeps its own information architecture and identity.

### 1.3 Visual priorities

1. Lyrics and playback state remain immediately readable.
2. Controls remain easy to reach and respond immediately.
3. Surface hierarchy communicates what is interactive and what is informational.
4. Blur, glow and motion support the content rather than compete with it.
5. The app remains visually coherent in both light and dark themes.

---

## 2. Theme and colour architecture

### 2.1 Theme behaviour

- Default theme mode: **Follow system**.
- Support the existing Android Material You / wallpaper-derived dynamic colour behaviour.
- Preserve the existing curated palettes and their identifiers.
- Let users preview a curated palette before applying it.
- Redesign both light and dark appearances as a matched system.
- Preserve existing theme controls, persistence and selection behaviour.

### 2.2 Colour source rules

Use semantic Material 3 colour roles throughout the UI, including where available:

- `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`
- `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`
- `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`
- `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`
- `surfaceContainer*` roles supported by the project's Material 3 version
- `outline`, `outlineVariant`, `scrim`, and semantic error roles

**Do not hard-code an accent identity.** No fixed rose, pink, purple, violet, cyan or other brand accent in component styling. Selected states, glows, borders and glass tints must derive from the active theme's semantic roles.

The existing code decides the precedence between an explicitly selected curated palette and wallpaper dynamic colour. Audit that logic first and preserve it. If the current precedence is inconsistent or unclear, report it and ask before changing it. Do not silently invent a new precedence rule.

When dynamic colour is disabled or unavailable, retain the app's existing fallback palette. Do not silently replace it with a newly invented palette.

### 2.3 Palette preview

- Preview must be visually representative of the selected palette in the current light/dark mode.
- Previewing is not the same as applying. Do not persist a palette until the user's existing apply/confirm action is completed.
- Cancelling or leaving the preview must leave the active palette unchanged.
- Do not create new palette options as part of the redesign unless separately approved.

### 2.4 Artwork is not the theme source

Album artwork may inform the Now Playing background composition, but it must not silently replace Android wallpaper dynamic colour or the curated palette. Album-art colour extraction/adaptive accent generation is out of scope unless it already exists and is explicitly retained.

---

## 3. Background and glass system

### 3.1 Background composition

**Now Playing:**

- Blend the current album artwork into a cinematic background with a gradient derived from the active theme.
- Keep the foreground album-art tile compact.
- Use blur, dimming, tonal overlays or contrast layers as needed to protect foreground readability.
- If artwork is missing, loading or invalid, use a restrained theme-derived fallback background. Do not show a broken image or a blank noisy surface.

**Lyrics, Settings, History, Search and About:**

- Use calmer, lower-contrast backgrounds.
- Keep the same active theme, but reduce atmospheric movement and visual intensity.
- Do not make every screen look like Now Playing.

### 3.2 Glass surfaces

Use component-specific treatment rather than one universal glass style:

- Primary surfaces: medium translucency with visible but controlled background diffusion.
- Secondary surfaces: lighter translucency or tonal Material 3 surfaces.
- Dense content surfaces, especially lyric lists and settings rows: favour readability over maximum transparency.
- Navigation pill and important cards: clear surface separation with thin translucent outline and soft shadow.
- Text must retain adequate contrast in all supported themes and wallpaper conditions.

The app's existing frosted/transparent settings remain the control point for the floating overlay. Do not add a new global blur-intensity slider.

### 3.3 Blur and fallback behaviour

- Use only blur approaches supported by the project's minimum SDK and current Compose/dependency stack.
- Do not add a dependency solely to imitate a screenshot without first documenting why it is required and asking for approval.
- If true backdrop blur is unavailable, too expensive or visually unreliable, use a translucent tonal surface, a restrained scrim and layered depth as a graceful fallback.
- Avoid applying expensive blur to large, continuously animated regions.
- Blur must never reduce lyric, control or setting readability.

### 3.4 Glow

- Derive glow tint from the active theme's semantic accent roles.
- Use glow selectively: for example, the main playback state, active lyric emphasis or a meaningful selected state.
- Keep glow low intensity and local to the component.
- Do not put a glow around every card, button, icon or label.
- Glow should be removed or simplified when reduced motion / performance constraints require it.

### 3.5 Borders and shadows

- Prefer thin, translucent, low-contrast borders.
- Use soft shadows to distinguish layers, not to create heavy elevation everywhere.
- Maintain equivalent hierarchy in light mode; do not simply invert dark-mode colours.

---

## 4. Typography system

### 4.1 Main app

- Use a modern geometric sans-serif direction for main application UI.
- First inspect fonts already bundled or available through the existing project. Do not add font files or dependencies without approval.
- Establish a clear hierarchy for screen titles, section labels, primary content, supporting labels and metadata.
- Do not use typography size alone to communicate state; combine size/weight with colour, shape or semantics.

### 4.2 Floating lyrics overlay

- Use the Android system font by default.
- Preserve all existing user-selectable overlay font choices.
- Do not force the new main-app font onto the overlay.
- Preserve the current long-line behaviour and its existing user settings.
- Active lyric weight may adapt to the selected animation style, but this must remain legible and predictable.

### 4.3 Scaling and line handling

- Preserve the existing font-size preference and support Android font scaling.
- Apply responsive constraints so user-selected text remains readable in the available space.
- Do not silently clamp, shrink or override user-selected sizes to force a fixed layout.
- Avoid clipping, overlap and unexpected truncation. Use reflow or scrolling where the screen layout allows it.
- Preserve existing single-line/multiline and long-line behaviour unless a separate functional change is approved.

---

## 5. Layout, spacing and responsiveness

### 5.1 Core rule

**Do not design for one screenshot or one device size.** All layouts must adapt to the actual available window space.

- Do not hard-code screen widths or screen heights.
- Do not give cards, panels or controls fixed dimensions merely to match a reference screenshot.
- Use semantic spacing, shape and typography tokens; use constraints and available-space-aware layouts for component sizing.
- Fixed design tokens such as icon dimensions, corner treatments or minimum touch targets are acceptable where they serve a defined purpose. They must not become fixed screen-layout assumptions.

### 5.2 Device and orientation support

- Phones are the primary target.
- Adapt to tablets and foldables where practical; do not stretch a phone layout indiscriminately across a large window.
- Support adaptive layouts wherever practical across portrait and landscape.
- In landscape, retain a vertical content flow with adaptive sizing/reflow; do not force an unrelated two-pane dashboard layout.
- Keep the current minimum SDK unchanged.

### 5.3 Compose implementation guidance

After auditing the existing code, choose the safest fitting techniques. Possible tools include:

- `WindowSizeClass` or an equivalent window-size abstraction where appropriate.
- `BoxWithConstraints` or constraint-aware composition for genuinely adaptive components.
- `weight`, `fillMaxWidth`, `wrapContentHeight`, `requiredSizeIn` only when justified, and flexible arrangements.
- `LazyColumn` / lazy containers for long lists and large histories.
- `WindowInsets` and edge-to-edge APIs appropriate to the existing Android/Compose version.

Do not apply these mechanically. Avoid nesting multiple independently scrolling containers without a clear reason. Avoid fixed height cards that clip when font scale or display size changes.

### 5.4 Small screens and large text

- Adapt spacing and component arrangement first.
- Allow scrolling when the content cannot fit.
- Do not shrink text or touch targets just to fit everything above the fold.
- Test longer song titles, long artist names, multiple-line lyrics, larger font scales and larger display sizes.
- Ensure bottom navigation, controls and floating elements do not cover scrollable content or one another.

### 5.5 System insets

Respect status bar, navigation bar, display cutout, gesture navigation and on-screen keyboard insets. Content must not be hidden behind system UI. Verify edge-to-edge behaviour in both gesture and three-button navigation modes where test devices permit.

---

## 6. Navigation and transitions

- Preserve the existing four main destinations and navigation structure.
- Keep the bottom navigation visible on main screens.
- Use a floating frosted-glass pill treatment for bottom navigation.
- Keep top app bars minimal, with screen title and contextual actions.
- Use shared-element or fluid transitions only where they clarify continuity between related content.
- Use simpler transitions where shared-element work would add complexity or performance cost.
- Do not redesign navigation destinations or introduce new top-level tabs without approval.

---

## 7. Interaction language

| Element | Direction |
|---|---|
| Buttons | Native ripple plus subtle scale feedback |
| Cards | Subtle elevation and border change on interaction |
| Screen entry | Shared-element / fluid transitions where useful |
| Loading | Subtle animated progress indicators |
| Dialogs and sheets | Material/glass treatment chosen by importance and context |
| Haptics | Subtle feedback for meaningful actions only |
| Reduced motion | Respect Android's system setting and provide an app-level option where supported by the existing architecture |

Interaction feedback must not delay playback controls, lyric updates, scrolling, touch handling or navigation. Use existing haptic and animation infrastructure where possible.

---

## 8. Accessibility and performance

### 8.1 Accessibility

- Maintain sufficient text and control contrast in light and dark themes.
- Support Android font scaling and display-size changes.
- Provide meaningful semantics and content descriptions for icon-only actions.
- Keep touch targets comfortably usable and aligned with Android accessibility guidance.
- Ensure focus order and TalkBack descriptions are meaningful.
- Do not communicate state by colour alone.
- Respect system reduced-motion settings and provide an app-level reduced-motion preference where feasible without disrupting existing settings.

### 8.2 Performance

- Balance visual quality with battery, memory and rendering performance.
- Prefer lightweight, local animations over continuously invalidating large areas.
- Avoid unnecessary recomposition and expensive state reads in frequently updating lyric/player surfaces.
- Keep album-art decoding and background effects bounded to the visible UI needs.
- Use graceful fallbacks when blur, glow or shared-element transitions would be too expensive or unsupported.
- Do not animate text or surfaces in a way that interferes with lyric timing accuracy.

---

## 9. Existing feature preservation

The redesign must preserve every existing feature and preference. This includes, but is not limited to, the functionality discovered in the source audit:

- MediaSession metadata and playback-state synchronisation.
- Playback controls and existing control arrangement.
- Lyrics provider integrations and refresh behaviour.
- LRC parsing, manual import, preview/save flow and persistence.
- Lyrics timing offsets and current synchronisation behaviour.
- Floating overlay permission, position, width/size and drag behaviour.
- Existing overlay font, opacity, single-line, scrolling and animation preferences.
- Existing theme settings, dynamic-colour toggle, curated palettes and stored selection.
- Existing history storage and track interaction.
- Existing navigation routes and app lifecycle behaviour.

This list is not permission to assume every item exists exactly as written in the current branch. Audit first, document the real implementation and preserve the actual feature set. Do not remove a feature because it is difficult to fit into the new UI.

---

## 10. Explicitly out of scope unless separately approved

- Rewriting the playback engine or MediaSession architecture.
- Replacing the lyrics providers or LRC parser.
- Changing existing feature semantics.
- Removing or renaming stored preferences, DataStore keys, Room entities or migrations.
- Introducing a new theme engine or changing existing palette precedence.
- Adding new curated palettes or a custom colour picker.
- Album-art colour extraction as a new theme source.
- Changing the minimum SDK, target SDK, signing configuration or release identity.
- Adding dependencies solely for visual effects.
- Replacing navigation architecture.
- Broad refactoring unrelated to the approved redesign.

Any required exception must be explained with evidence and approved before implementation.
