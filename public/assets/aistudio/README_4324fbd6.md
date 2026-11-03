# LyricBar V2 — Approved Design System Pack

This folder contains the approved design and implementation specifications for the LyricBar V2 redesign.

## Files

| File | Purpose |
|---|---|
| `DESIGN.md` | Visual identity, theme rules, glass treatment, typography, responsiveness, accessibility and performance contracts. |
| `COMPONENTS.md` | Reusable component responsibilities, interactions, states and responsive behaviour. |
| `SCREEN_SPECS.md` | Screen-by-screen layouts and flows for Now Playing, Lyrics, Settings, History, overlay, search/import and About/updater. |
| `AI_STUDIO_MASTER_PROMPT.md` | AI Studio workflow and engineering guardrails. Requires a source audit only before any code edits. |

## How to use this pack

1. Copy these Markdown files into the root of the LyricBar project, or provide the extracted folder to Google AI Studio as project context.
2. Open `AI_STUDIO_MASTER_PROMPT.md` and provide it as the master instruction.
3. Require **Phase 0 — Source Audit Only** first. AI Studio must inspect the checked-out project, map real source files and report risks. It must not edit files during Phase 0.
4. Review the audit report and approve the implementation plan.
5. Implement one phase at a time. Review the diff and actual build/test results before proceeding.

## Approved decisions at a glance

- Custom hybrid, premium and cinematic visual identity.
- Medium frosted glass; reference MeeTube only for glass, blur and glow.
- Dynamic Material You colours plus existing curated palettes; preview before apply.
- Follow system by default; full light and dark theme redesign.
- Existing four main destinations and navigation structure; floating frosted bottom pill.
- Now Playing: compact artwork, cinematic artwork/theme background, current + next Live Lyrics card, existing controls, expandable timing chip.
- Lyrics: scrollable list, soft active-line highlight, separate frosted controls, same timing chip, no mini-player.
- Settings: category cards, simple rows, expandable sections, advanced collapsed by default, existing controls preserved.
- History: recent list plus Today / Yesterday / Earlier, search, individual delete and clear all.
- Floating overlay: existing transparent/frosted preference, white lyrics with subtle theme-derived glow, controls on tap, existing position/size/features preserved.
- Modern geometric main UI typography; system overlay font by default with existing font choices retained.
- Search via full-screen screen; native LRC picker with preview and explicit save.
- Updater in About, GitHub Releases, progress, trusted SHA-256 verification and Android installer handoff.
- Responsive layouts, accessibility and performance are mandatory.
- Preserve all existing features and preferences. Keep current minimum SDK.

## Important

This pack is a design/implementation specification, not evidence that any source code has been changed or tested. The current repository must be audited before implementation. No feature removal, preference migration, dependency change, SDK change, playback rewrite or unrelated refactor is authorised by this document.
