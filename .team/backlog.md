# Backlog

Prioritized. Status is one of: todo, in-progress, review, done.
The Product Lead owns this file.

| # | Item | Role(s) | Branch | Status |
| - | ---- | ------- | ------ | ------ |
| 1 | Foundation: reproducible Gradle build, two modules, one-command run/test, installable debug APK | devops | `chore/build-foundation` | **review** |
| 2 | Physics core: particles, constraints, broadphase, contacts, fixed tick + 8 substeps, JVM stability & determinism tests | backend | `feat/core-sim` | in-progress |
| 3 | Renderer + input: GL ES 3.0 context, dynamic mesh upload, flat triangles, drag/tap/swipe, impact haptics | frontend | `feat/app-shell` | in-progress |
| 4 | Visual language against the confirmed shader varying set | ux | `chore/ux-varyings` | todo |
| 5 | **MILESTONE 1 — "Squish Toy"**: one piece falls, squashes, settles. Plus on-device benchmark to close the performance unknown | backend+frontend | `feat/squish-toy` | todo — **client demo** |
| 6 | The mechanic: piece sequence, lock detection, coverage bands, clear rule, stack drop and re-settle | backend | `feat/mechanic` | todo |
| 7 | Procedural shading: gel/subsurface, grain, rim light, band glow. Profile the fragment shader | frontend | `feat/shading` | todo |
| 8 | Rules: losing condition + settle grace, difficulty ramp, scoring | backend | `feat/rules` | todo |
| 9 | Feel: landing silhouette, screen shake, impact propagation, clear-release | frontend | `feat/feel` | todo |
| 10 | Dev tuning panel — thresholds, compliance, grace window (needed *before* tuning) | frontend | `feat/tuning-panel` | todo |
| 11 | Accessibility (reduced motion, colourblind hues), settings, defensive persistence, startup calibration | frontend | `feat/completion` | todo |
| 12 | QA hardening, signed release APK, sideload instructions | qa+devops | `chore/release` | todo |

## Deferred — carried to a later iteration

Not cancelled. Recorded so the reasoning survives and is not re-derived.

| # | Item | Why deferred | Raised |
| - | ---- | ------------ | ------ |
| D1 | Reaction-time accommodation — decouple fall speed from block mass so a player can keep the heavy sagging physics while reducing twitch demand. Not framed as a difficulty setting; reachable mid-game; no score penalty. | Client chose it, then reconsidered the same day to keep iteration one simple. Reduced motion and colourblind-safe hues are unaffected and remain in scope. | 2026-07-20, UX Designer |
| D2 | Google Play submission — store listing, screenshots, Data Safety declaration, and the account-type decision (personal accounts need 12 testers × 14 consecutive days; organization accounts are exempt but need a D-U-N-S number). | Client deferred the store; we deliver a signed sideloadable APK instead. Nothing forecloses publishing later. | 2026-07-20, client |
| D3 | Additional game mode (timed sprint). | Deferred at interview until after the milestone demo, so it is judged with real feel to go on. | 2026-07-20, client |
