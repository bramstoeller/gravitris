# Product brief — working title: **Squish**

Status: **awaiting Gate 1 approval**
Date: 2026-07-20
Author: Product Lead, from client interview (see `conversations/`)

## Problem

Falling-block puzzle games are a solved, saturated genre. Every entrant competes
on polish over a mechanic that has not meaningfully changed in forty years,
because the grid is load-bearing: pieces snap, rows fill, rows clear.

This product removes the grid. Blocks are heavy, deformable soft bodies that
squash, bulge, sag and settle under real physics. Nothing snaps. The stack is
alive — it compresses under its own weight as it grows, and every session
settles differently.

That single change turns a memorized-pattern game into a physical, tactile one,
and creates a mechanic that cannot be cloned by reskinning a Tetris tutorial.

## Users

Casual mobile players who already know falling-block games and want something
that feels new rather than nostalgic. The hook is sensory — weight, squish,
haptics — not depth of strategy. Sessions are short, one-handed, portrait.

No accounts, no social features, no multiplayer. A player installs it, plays it
on a commute, and beats their own score.

## The mechanic

**Soft-body squash and settle.** Each piece is a deformable body, not a rigid
shape. On landing it compresses, spreads and jiggles; the stack beneath it
compresses in turn. Pieces get heavier as the game progresses, so the tower
increasingly sags and shifts under its own load.

**Coverage-band clearing.** Because nothing is grid-aligned, a horizontal band
clears when it is *sufficiently filled by material* (target threshold ~90%,
to be tuned), not when a perfect row forms. This rewards packing and squeezing
material into gaps rather than tidy stacking, and follows naturally from
deformable bodies.

**Endless with rising difficulty.** One mode. Two difficulty dials — fall speed
and block mass — so difficulty emerges from the physics rather than being
bolted on. Play until the stack tops out. Score and personal best, stored
locally.

Losing condition, tuning of the threshold, and scoring formula are open design
questions for the architecture gate.

## Look and feel

**Tactile and organic**, produced **procedurally in shaders** — gel, rubber and
dough rendered via subsurface approximation, noise grain and rim lighting rather
than authored texture assets. Keeps the APK small and makes the art direction a
set of tunable parameters instead of a folder of PNGs.

Shading is not decoration here: it is what keeps individual blocks legible once
they have deformed together. Piece identity is carried by **hue**, since shape
deforms away.

**Band feedback:** material in a nearly-full band glows warmly from within. The
feedback lives on the blocks themselves — no HUD chrome, no percentage readout —
and teaches the clearing rule without a tutorial.

**Feel:** haptic pulse on impact scaled to mass and fall speed; impact
propagates visibly down the stack; screen shake scaled to mass; the clear reads
as a release of pressure, with the stack above dropping and re-settling. That
re-settle is the payoff moment and is given time to be watched.

## Controls

Direct manipulation, no on-screen buttons:

- **Drag anywhere** on screen to move the piece left/right (drag target is the
  whole screen, not the piece, so the thumb rests low and out of the way)
- **Tap** to rotate
- **Swipe down** to hard-drop
- A projected **landing silhouette** shows where the piece will settle

Rationale: buttons cost ~20% of a portrait screen, and this game needs vertical
space because a sagging soft-body stack spreads action across the whole well.
Direct manipulation also matches the fantasy of handling something heavy.

Accepted tradeoff: less precise than buttons. Mitigated by the landing
silhouette and by rotate-on-tap rather than a gesture.

## Platform and constraints

| Constraint | Decision |
| ---------- | -------- |
| Platform | Android only. No iOS, no desktop, no port path planned. |
| Minimum OS | Android 10 (API 29) |
| Performance target | 60fps on a 2020-era mid-range device |
| Stack | Native Kotlin, custom XPBD soft-body solver, OpenGL ES 3.0 |
| Orientation | Portrait only |
| Monetization | None. Free, no ads, no IAP, no tracking, no third-party SDKs |
| Network | None. The app makes no network requests. |
| Data | Local only — score and settings. No accounts, no PII, no analytics. |
| Distribution | Google Play. Client has **no** Play Console account yet. |

Stack rationale: no mainstream 2D engine ships adequate soft-body dynamics, so
the solver is custom regardless. Once that is true, an engine only supplies
rendering and packaging while costing 25–35MB of runtime and a CI-hostile build.
Native gives control of solver memory layout (soft-body physics is cache-bound),
a ~5MB APK, and a reproducible headless Gradle build. See forthcoming ADR.

## Accessibility

In scope from the start, not retrofitted:

- **Reduced motion** setting — disables screen shake and damps jiggle. This game
  is nothing but motion; this is a genuine accessibility need, not a nicety.
- **Colourblind-safe piece hues** — required, because hue is the primary piece
  identity cue once shape deforms.

## Success criteria

1. Installs and runs on a mid-range Android 10 device, holding 60fps with a full
   stack of soft bodies in play.
2. The squish reads as *heavy* — players describe the blocks as weighty and
   physical without being prompted.
3. A player understands coverage-band clearing within one session, without a
   tutorial screen.
4. Builds reproducibly from a clean container with one documented command, and
   produces a signed release bundle ready for Play upload.
5. Ships with no network permission and no data collection.

## Non-goals

Explicitly out of scope. Anything here requires a new client decision:

- iOS, desktop, web, or any cross-platform port
- Multiplayer, leaderboards, accounts, cloud save, social features
- Ads, in-app purchases, paid tiers, analytics or any third-party SDK
- Hand-designed levels, puzzle stages, or any authored content pipeline
- Additional game modes (sprint/timed) before the milestone demo
- Landscape orientation and tablet-optimized layouts
- Localization beyond English
- Authored texture assets (revisitable at the release gate if procedural
  shading proves insufficient)

## Sequencing

"Store-ready" is the release target; it is not the first thing built.

1. **Prototype** — solver + one mode, placeholder look, installable APK. Proves
   the physics feels good. This is the milestone demo.
2. **Game** — art direction, feedback, accessibility, scoring, settings.
3. **Release** — signing, store assets, metadata, and written instructions for
   the client to register a Play Console account and publish.

Rationale: deformable-body physics at 60fps on mid-range hardware is the
project's single largest risk. If the core feel fails, no amount of store polish
recovers it, so it is proven first.

## Open questions

Carried into the architecture gate:

1. Coverage threshold — what percentage actually feels fair? Requires tuning
   against a playable build.
2. Losing condition — with a sagging, shifting stack, what counts as "topped
   out"? A fixed line is unfair if the stack settles back down.
3. Solver budget — particle and constraint counts affordable at 60fps on the
   target device. Determines how deformable blocks can be.
4. Scoring formula — should packing efficiency be rewarded beyond the clear
   itself?
5. Play Console — client must register an account before release; signing key
   custody is the client's, and no credentials enter this container.
