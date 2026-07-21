# Handoff 0035 — Frontend Engineer → Product Lead

**Task:** implement the modern visual layer per `docs/ux/visual-direction.md`,
on top of the shaped-and-controlled game (main `08719edd`).
**Branch:** `feat/visual-layer` (PR: see below). **Commits:** `ff89404`,
`0f14a2e`, `7f82ef2` (+ a follow-up trim, see Commit range below).

## What I built

The four headline deliverables plus the supporting tokens, all against
`docs/ux/visual-direction.md` and the screen specs. Nothing in the spec was
redesigned; the two elements the spec itself could not feed (next-piece,
score-pop) were confirmed out of scope with the UX Designer before building.

### 1. Procedural background / environment (§3) — replaces the black void
`app/.../gl/Background.kt`. One full-screen quad drawn first each frame:
- vertical gradient `color-bg-deep` `#05050C` (top/bottom) → `color-bg-core`
  `#0E1730` (centre), smoothstepped so it eases rather than banding;
- two fixed soft radial glows (upper-left `color-bg-glow-a`, lower-right
  `color-bg-glow-b`), cool hues only, 4–8% peak, drifting on a ~100s sine;
- the same R2 dither the gel shader uses, against near-black banding.

**Budget:** this is the only O(screen-pixels) addition and the one to watch on
device. Kept cheap by construction — a handful of ALU ops, **no `sqrt`** (radial
falloff runs on squared distance). Replaces `glClearColor(0,0,0,1)`
(GameRenderer.kt:328), which now only shows for the one pre-layout frame.

### 2. Band-clear juice (§7.1 + §7.2) — the payoff moment
Two GPU-side additions layered onto the existing (good) ignition→dissolve, both
firing on a real `Phase.Clearing` onset — the same signal the clear counter logs,
so they cannot drift from "a band actually ignited":
- **Luminance beat** (`gl/ClearFlash.kt`): a screen-wide additive warm-white
  quad (`color-glow-hot`, ~8% peak) on a 120ms triangle envelope synced to the
  ignition flash. Not bloom, not a post-pass — one additive triangle.
- **Ember burst** (`gl/EmberBurst.kt` + `gl/EmberTrajectory.kt`): a pre-sized
  pool (256) of small additive amber quads spawned across each clearing band's
  width, on a purely analytic arc (`p = origin + v·t + ½g·t²`), faded over
  ~350ms. No physics, no lattice. Ring-buffer sized once at startup, not per
  clear. Additive blending is enabled only for these draws and the global
  "blend off" invariant is restored.

The **score-pop "+N" (§7.3) is deferred** — `SimState.score` is hardwired to 0
(D8), so there is no delta to pop. It lands with D8.

### 3. Real game HUD (§6) — Android View layer, zero GPU cost
`app/.../hud/GameHud.kt`. Score (top-left, `type-title`), level chip
(top-centre, `color-surface`), pause icon (top-right, 48dp, drawn). Wired to the
real `SimState.score`/`level` via the renderer's ~4Hz stats publish. Padded
inside the safe-area insets. Next-piece slot is reserved in the layout but not
rendered (no contract data — see Deviations).

The **frame-time readout is demoted** to a hidden, debug-only instrument: hidden
by default so the client never sees it, revealed on a debuggable build by the
first press of the existing volume-key measurement affordances (shade dial /
benchmark). Release builds can never surface it.

### 4. Designed game-over screen — replaces the bare TextView
`app/.../hud/GameOverView.kt`. Dimmed frozen stack + environment behind
`color-overlay-scrim` (82%), a `SCORE` caption, the run's score
(`type-display`), and a prominent amber **PLAY AGAIN** pill (`radius-pill`, one
tap). Fades in with `motion-base`; reduced-motion drops the translate.

### 5. Seven-hue identity (§5) + motion tokens (§8)
Palette was already 7 hues on main (nothing to do). Motion tokens live in
`hud/Motion.kt` (pop/celebrate back-out curves + durations, with reduced-motion
collapse), used by the HUD and game-over.

## Deviations from the spec, and why (all pre-agreed with the UX Designer)

- **Next-piece preview omitted.** `SimState` does not publish the upcoming piece
  (`PieceSequence.peek()` is internal to `:core-sim`). Building a fake or empty
  slot would read as the exact "tech demo" gap the client complained about. The
  HUD reserves the top-right column for it; it needs an Architect contract
  addition (`SimState.nextArchetype`, + shape if the preview is a silhouette).
- **Score-pop "+N" deferred to D8** (no score delta exists yet).
- **Game-over NEW BEST badge + best row omitted.** No local persistence (D8), so
  every run is `game-over.md`'s "first-ever run" state, which by definition shows
  the score with no badge and no best row. Built only what that state ships; D8
  adds both together with `motion-celebrate`.
- **Game-over "Title" button omitted** — no Title screen is built (menus
  deferred), so it would strand the player. Play Again is the whole screen.
- **Score reads 0 everywhere** until D8. The HUD/game-over present the real
  field (not a faked number); the frame is there and lights up when scoring lands.
- **§3 well-frame emissive inner edge: not done this round.** It needs a small
  WellFrame geometry change; I prioritised the four headline items. Low-risk
  follow-up — flagged, not attempted, so the walls read a little flat against the
  new indigo background in an empty well.
- **§9 D6 overflow-warning cue: not done this round** (not in the headline DoD).
  Buildable next as a shader `uOverflow` term.

## Measurement / budget status

**I cannot give a real performance number.** The build container has no GPU; the
emulator is software-rendered (SwiftShader/swangle), which proves *what* draws,
never fidelity or cost on the client's OLED. So nothing here is a perf claim.

The frame-time readout is kept (now hidden) precisely so cost is measured on the
client's device: volume-up walks the shade tiers, and the new effects are inside
the same frame the readout times. **Nothing was cut** — I had no on-device
overrun to react to. If the background pass or embers overrun on device, the
cut-list order stands (drop the glow drift, then the embers, then lower the shade
default). This is the one open on-device action, per §10.

## Verification

- `make test` green (JVM unit + buildSrc guards + lint, debug and release).
- New pure-logic tests: `EmberTrajectoryTest`, `ClearFlashEnvelopeTest` (the
  analytic motion and beat envelope — the only rendering maths testable without a
  GPU).
- `make playthrough`: 8 clears / 8 spawns, mechanic runs and renders.
- Targeted emulator captures of each money shot (see below) — **software-rendered,
  appearance-direction only, not the device look.**

## Screenshots (software emulator — direction, not fidelity)

See PR description / attached. Background+HUD, multi-hue stack, a clear
(ignition/embers/dissolve), and the game-over screen.

## Post-review fix (glows)

The UX Designer's screenshot review caught that the §3 radial glows did not
read. Measured it: glow centre vs same-height background differed by 0.0003 —
invisible. Two causes, both fixed: I was adding the near-black token colours
`#0E1730`/`#241B3D` (adding a near-black colour cannot brighten), and the disc
radius covered the whole screen (a broad wash, not a disc). Now cool teal/violet
tints at ~4-5% white luminance with a localized radius; re-measured +0.015 at the
centre and verified on the emulator. **`tokens.md`'s `color-bg-glow-a/-b` row is
now stale** — it still names the near-black colours at 4-8% opacity, which render
nothing; it needs updating to the readable tint values. Left for the UX Designer
(their doc) — flagged to them directly.

## GPU-budget fix — drift sin/cos hoisted to the CPU (commit `4c1ce61`)

The Code Reviewer flagged that `Background.kt`'s fragment computed four
`sin`/`cos` per pixel for the glow drift (was lines 212–213), but those offsets
depend only on `uTime` — they are **frame-constant**, identical for every pixel
in the surface. On the one sustained O(screen-pixels) pass (~2.6M px on the
Fairphone) that is up to four wasted transcendentals per pixel if the driver
does not hoist them, which we must not gamble on.

Fix (pure cost move, no look change): the two drift `vec2`s are now computed
once per frame on the CPU in `Background.draw()` (`kotlin.math.sin`/`cos`, the
same `DRIFT_RATE`/`DRIFT_AMP`/phase constants, now Kotlin `const`s) and passed
as `uDriftA`/`uDriftB` uniforms; the fragment reads them directly. The `uTime`
uniform is gone from this program. **Zero transcendentals in the background
fragment.** The `disc` falloff still runs on squared distance (no `sqrt`), so
the whole full-screen pass is now transcendental-free.

Verified identical: `make test` green (JVM unit + lint, debug + release); fresh
`make screenshot` on the swangle software emulator compared against
`0035-visual-layer/01-background-hud.png` — the indigo gradient, the warm core,
the low-right violet glow and upper-left cool tint, the grain and dither all
read the same. (Different falling-piece position and clock are the only
differences, both expected.) Still software-rendered — no perf number attaches;
the hidden frame-time readout on the client's device remains how the real cost
is priced. The instrumentation was untouched.

## Two subtle §3/§7 elements — built or cut? (for the UX Designer)

Both are **built and wired**, just subtle-or-transient in a still — neither is
cut:

- **Background's two soft radial glows (§3):** built. `Background.kt` draws them
  every frame — `GLOW_A` teal upper-left `vec2(0.28, 0.74)`, `GLOW_B` violet
  low-right `vec2(0.74, 0.24)` (fragment `main()`, the two `color += GLOW_* *
  disc(...)` lines). They are the elements the Post-review glow fix above made
  actually read; visible in both `01-background-hud.png` and the fresh capture as
  the low-right violet lift. Subtle by design (~4–5% white luminance), not absent.
- **Screen-wide ignition luminance beat on a clear (§7.1):** built. `ClearFlash.kt`
  is a full-screen additive warm-white quad on a 120 ms triangle envelope
  (`clearFlashEnvelope`). It is created in `GameRenderer` (`clearFlash.create()`),
  **armed on every real `Phase.Clearing` entry** (`clearFlashStartNanos =
  frameStart`), and drawn each frame (`clearFlash.draw(clearFlashIntensity(...))`).
  It is a ~120 ms transient synced to the ignition, so a still almost never
  catches it — that is why it cannot be confirmed from the static shots — but it
  is present and firing. Not cut.

## For the next agent / open questions

- **On-device measurement of the background pass + embers is owed** before the
  release tier is trusted (§10). It is the client's device or nothing.
- **Next-piece preview** is blocked on an Architect/Backend contract addition.
- **Well-frame emissive edge** and **D6 overflow cue** are the two spec items I
  deliberately left; both are small, isolated follow-ups.
- **`tokens.md` glow-colour row** is stale after the glow fix (see Post-review
  fix) — the UX Designer owns the canonical values.
- **Grain repeats per tetromino cell** (UX Designer's observation): the gel
  material samples `vBodyUv`, and a tetromino is one body of four cells, so if
  grain restarts per cell then `particleU/V` is populated cell-local despite the
  contract naming it "body-local" — a core contract-vs-implementation gap for the
  Backend Engineer to confirm. Not my code (I did not touch the gel shader); not
  blocking (grain is the tertiary identity cue; hue and the seam are continuous
  and correct).
- Sign-off: the UX Designer reviews the screenshots against the spec and the
  "commercial modern game" bar before this lands; the Product Lead verifies the
  look. Do not merge until both sign — I have not merged.
