# Backlog

Prioritized. Status is one of: todo, in-progress, review, done.
The Product Lead owns this file.

| # | Item | Role(s) | Branch | Status |
| - | ---- | ------- | ------ | ------ |
| 1 | Foundation: reproducible Gradle build, two modules, one-command run/test, installable debug APK | devops | `chore/build-foundation` | **changes requested** — fresh clone cannot build (cold-cache verification metadata) |
| 2 | Physics core: particles, constraints, broadphase, contacts, fixed tick + 8 substeps, JVM stability & determinism tests | backend | `feat/core-sim` | done — PR #2 |
| 3 | Renderer + input: GL ES 3.0 context, dynamic mesh upload, flat triangles, drag/tap/swipe, impact haptics | frontend | `feat/app-shell` | done — PR #3, includes CHK-1 bypass fix |
| 4 | Visual language against the confirmed shader varying set | ux | `chore/ux-varyings` | todo |
| 5 | **MILESTONE 1 — "Squish Toy"**: one piece falls, squashes, settles. Plus on-device benchmark to close the performance unknown | backend+frontend | `feat/squish-toy` | **done** — landed on main via the game tip |
| 6 | The mechanic: piece sequence, lock detection, coverage bands, clear rule, stack drop and re-settle | backend | `feat/mechanic` | **done** — landed on main 2026-07-20 |
| 7 | Procedural shading: gel/subsurface, grain, rim light, band glow. Profile the fragment shader | frontend | `feat/shading` | **done** — landed on main with the mechanic |
| 8 | Rules: losing condition + settle grace, difficulty ramp, scoring | backend | `feat/rules` | todo |
| 9 | Feel: landing silhouette, screen shake, impact propagation, clear-release | frontend | `feat/feel` | todo |
| 10 | Dev tuning panel — thresholds, compliance, grace window (needed *before* tuning) | frontend | `feat/tuning-panel` | todo |
| 11 | Accessibility (reduced motion, colourblind hues), settings, defensive persistence, startup calibration | frontend | `feat/completion` | todo |
| 12 | QA hardening, signed release APK, sideload instructions | qa+devops | `chore/release` | todo |

## Deferred — carried to a later iteration

| D6 | Overflow warning cue — no visual signal during the settle-grace window before game-over, so losing feels abrupt. Add a warning glow/pulse as the spawn band nears overflow. | Found by Tech Writer 2026-07-21 verifying the install doc. | 2026-07-21 |
| D7 | Distinct piece shapes — every piece is the same square lattice; variety is colour-only. A falling-block game wants several shapes (the brief's "pieces"). Needs core piece-shape support + renderer + palette rework. | Found 2026-07-21 (install-doc caveat, code-confirmed). | 2026-07-21 |
| D8 | Game HUD — score/level display instead of the frame-time readout, and score persistence (personal best). Deferred with the mechanic. | 2026-07-21 |

<!-- older deferred items below -->

Not cancelled. Recorded so the reasoning survives and is not re-derived.

| # | Item | Why deferred | Raised |
| - | ---- | ------------ | ------ |
| D1 | Reaction-time accommodation — decouple fall speed from block mass so a player can keep the heavy sagging physics while reducing twitch demand. Not framed as a difficulty setting; reachable mid-game; no score penalty. | Client chose it, then reconsidered the same day to keep iteration one simple. Reduced motion and colourblind-safe hues are unaffected and remain in scope. | 2026-07-20, UX Designer |
| D2 | Google Play submission — store listing, screenshots, Data Safety declaration, and the account-type decision (personal accounts need 12 testers × 14 consecutive days; organization accounts are exempt but need a D-U-N-S number). | Client deferred the store; we deliver a signed sideloadable APK instead. Nothing forecloses publishing later. | 2026-07-20, client |
| D5 | Rename `initialPieceMass` → `initialParticleMass`. It is read per-particle; the name says per-piece. Zero behaviour change, but it conflicts across every live branch, so it waits until the stack lands. | Requested by the Frontend Engineer, 2026-07-20 | 2026-07-20 |
| D4 | Rewrite pre-`25c23f8` commits so `git log` shows the role that did the work. Mapping is recorded in `.team/commit-attribution.md`. Needs a force-push to `main`, so it needs the client to lift the history guard deliberately, and it must happen when no agent is running. | Client asked for it 2026-07-20; deferred because agents were mid-flight on branches built from those commits. | 2026-07-20, client |
| D3 | Additional game mode (timed sprint). | Deferred at interview until after the milestone demo, so it is judged with real feel to go on. | 2026-07-20, client |
