# 0029 — Backend Engineer → Product Lead

Date: 2026-07-21 · Branch: `feat/tetromino-pieces` · Commits: `90d7c2a`, `9c13e84`, review-pass (lattice-4 pin + doc alignment)

## What I did

Real tetromino pieces and the new drop-control lifecycle, in `:core-sim`. Two
commits on `feat/tetromino-pieces` off `main`, 84 `:core-sim` tests green.

**Commit 1 `90d7c2a` — lifecycle + contract** (already merged into the
Frontend's `feat/drop-controls`):

- `InputFrame` → `{ dragX, rotate, drop }`; hard-drop machinery removed
  (Architect-signed, `docs/contracts.md` §2/§3/§6).
- Positioning → falling piece lifecycle (ADR 0016): a spawned piece is parked at
  the top with gravity suppressed (a per-particle `gravityScale`, so it stays a
  dynamic soft body — slides, does not fall) for a tick-counted window
  (`MechanicTuning.positioningTicks`, 50), then falls under real gravity, rotate
  only, until it settles and locks. `applyInput` gates the dumb intents by phase.
- Additive `SimState`: `activePiecePhase`, `positioningTicksRemaining`,
  `positioningWindowTicks`, `particlesPerBody`.
- `slamActivePiece(speed)` keeps the old 30-unit impact-velocity probe for the
  solver tests, without a player gesture.

**Commit 2 `9c13e84` — geometry** (ADR 0015):

- Seven shapes as ONE soft body of four `L×L` cells; `particlesPerBody` L²→4L²,
  constant across shapes, so swap-remove and the single reused index buffer are
  untouched. Cells welded across the 2r seam; seams padded to a constant with
  inert self-referential constraints. `particleEdge` on each shape's true
  outline. Spawn/placement generalised; benchmark and test piles rebuilt to
  drop-from-top. Physics scale unchanged → approved feel preserved.

**Review pass** (after the pin decision):

- `SimConfig.lattice` default 5→**4** (ADR 0014 pin); well defaults 10×20→20×40
  for the shaped game. All test configs are explicit, so this is game-default
  only — 84 tests still green.
- `slamActivePiece` doc hardened: **test/probe only, must not be called from game
  code** (Architect's nit). Stated in the doc rather than `@VisibleForTesting`,
  because that annotation is in `androidx.annotation` and `:core-sim` is
  Android-free (ADR 0002/0008). The `[6,30]` clamp is called out as a fixed
  contract the Frontend's range tests depend on.
- `SimState.triangleIndices` doc corrected to per-CELL reuse (was per-body);
  benchmark/ADR 0015 tier wording softened to the measurement only, deferring the
  pin story to ADR 0014.

## Agreements recorded

- Full contract co-design with the Frontend in
  `.team/conversations/2026-07-21-backend-engineer.md`. They chose **B2**
  rendering (per-cell pattern reused, silhouette closes the 2r seam, zero change
  to their extrusion) after measuring their own code; my `spacing = 2r` layout
  is exactly its precondition.
- ADRs **0015** (geometry) and **0016** (lifecycle + rotation), both
  Architect-approved (`.team/reviews/review-tetromino-adrs-0015-0016.md`).
  Numbered 0015/0016 to dodge the `chore/architecture` 0012–0014 collision.

## What I did NOT do / needs you

1. **PERF/TIER — DECIDED (was open; now resolved).** A tetromino is ~4× the
   material of a single block. Measured on the host: a near-full 20×44 well is
   ~1 700 particles at lattice 4 (~0.78 ms/frame, ≈9 ms at the 12× device
   derating — in budget) but ~3 100 at lattice 5 (~1.56 ms, ≈**19 ms — OVER**
   the 16.67 ms budget). **Decision (you + Architect): pin the lattice at 4, no
   runtime tier selection** — recorded in the Architect's **ADR 0014**
   (supersedes ADR 0009's tier part, repairs ADR 0013's dangling refs; on the
   Architect's PR #22). Pinning also closes the `pieceExtent`-varies-per-lattice
   leak by construction. In this branch I set `SimConfig.lattice` **default to 4**
   and sized the well defaults (20×40) for the shaped game; the geometry stays
   lattice-agnostic (4/5/6 all correct) so tests still exercise 5 and a future
   faster reference device can re-pin at build time. The real-device measurement
   (open blocker) still confirms the 12× derating assumption. **Note:** ADR 0014
   itself lands via PR #22, so my in-code "ADR 0014" refs dangle until that
   merges; `decisions.md` on this branch adds only 0015/0016 (I dropped my
   0012–0014 placeholder — the Architect indexes those on PR #22, so expect a
   small `decisions.md` merge reconcile).
2. **Well width is the Frontend's, but it interacts with me.** They're going
   ~20 wide / up to ~48 tall for ~9 columns. I'll scale `bandColumns` and
   retune `clearThreshold` (a live dial) on the wider/taller field in front of
   the client — `bandHeight` roughly doubles. Not blocking.
3. **Palette 6→7** is UX/Frontend's; they took Emerald #6 for the 7th hue.
4. **PR / landing.** CI (`make test && make build`) builds `:app` too, and
   `:app` is **red on `feat/tetromino-pieces` in isolation** — it references the
   removed `hardDrop`/`hardDropVelocity` and the not-yet-landed `drop`. The two
   halves are green only **combined**: the Frontend merges my branch into
   `feat/drop-controls`, and you land the combined result. The Frontend already
   built and greened their `:app` side on `feat/drop-controls` (PR #23, commit 1
   merged) but that branch does **not** yet have commit 2 (geometry) — so
   whoever finishes `feat/drop-controls` must pull commit 2 in. **The Frontend
   was blocked** on a worktree-binding env issue (escalated to you; you said a
   fresh FE agent is finishing from `feat/drop-controls`). My PR (#24) is opened
   for review/audit and is `:core-sim`-green; its `:app` red is by design and
   handed over, not a defect to chase.

   **Two `:app` changes commit 2 requires** (from the Frontend, recorded so they
   are not lost):
   - **Buffer sizing must consume `SimState.particlesPerBody` /
     `particleCapacity`**, not re-derive `lattice²`. `GameRenderer`'s `maxBodies`
     mirror and `BodyMesh(maxBodies, lattice)` size off a single-cell
     `pieceExtent²` today; against a 4×-denser tetromino that under-sizes the
     vertex buffer / trips the capacity assert in `rebuildSimulationIfWellChanged`
     on the first piece. This is the one non-mechanical `:app` change.
   - **The render loop iterates CELLS, not bodies**: `cellCount =
     particleCount / bodyLattice²`, draw `triangleIndices` per cell at offset
     `cell·bodyLattice²`. The Frontend measured that their `extrudeBoundary`
     needs **zero** change — it already walks per `bodyLattice²` block, which is
     now a cell. B2 confirmed working against commit 2.

   The four range/mechanical `:app` fixes are all `slamActivePiece(30f)`
   substitutions (same 30-unit slam, so compression/haptic tuning does NOT move)
   plus `input.hardDrop → input.drop`.

## Post-commit defect found + fix coordinated (benchmark divisor)

The Frontend caught this during integration and it is mine: rebuilding
`buildBenchmarkScene` for tetrominoes changed the reference scene from 960
single-block particles to **1536** (24 tetrominoes × 64 at lattice 4, 7296
constraints), but `:app`'s `SolverBenchmark.HOST_P50_MS` (0.4443 ms) and its
scene doc still describe the old 960-particle scene. No test failed — the `:app`
derating test only checks the arithmetic, not that the divisor's scene matches —
so it is a **semantic** defect: the client-reported host-vs-device derating ratio
would divide a 1536-particle device time by a 960-particle host time.

I re-measured the host p50 on the new scene (shipped protocol, this build host,
three runs): 0.86 / 0.91 / 0.86 ms → **HOST_P50_MS ≈ 0.86** (≈1.94× the old,
matching the ~2× constraint increase). `SolverBenchmark.kt` is `:app`
(Frontend-owned), so I handed them the number and the exact doc corrections to
apply on `feat/drop-controls` (message to the FE agent). The `:core-sim` scene
itself is correct and green; this is purely the `:app` divisor/doc catching up.
Recommended they add a test that the on-host derating lands in a sane band so a
future scene change without a re-measure trips a test.

## For QA — tests whose behaviour genuinely changed with tetrominoes

- Test piles were rescaled: wider wells (10→18, 6/5.5→14/16, 12→20) and ~1/4 the
  body counts, since a tetromino is ~4 blocks of material. `TestScenes.pile` now
  drops pieces from the top one at a time (dealer off) — robust to piece size.
- `DeformationTest` squash/bears-load now measure a **single cell** (a tetromino
  spans several); a cell squashes a real but smaller ~9% (vs a lone block's 15%)
  because seam neighbours share the load — per-material response unchanged,
  aggregate differs (Architect confirmed the wording).
- `SolverBehaviourTest` rotate-wedge: rotating a *settled* tetromino reorients
  and resettles (real motion, unlike a square's no-op turn) — neighbours still
  not disturbed; given more settle frames.
- Topology/benchmark counts updated to 4-cell reality (4L² particles,
  4×per-cell + seams constraints).
- New `TetrominoShapeTest` (8 tests) is the direct shape coverage.

## Uneasy about

- The perf/tier call (item 1) deserves the real-device number before we commit
  to L5 anywhere.
- Rotating a *settled* soft-body tetromino is energetic (reorientation). In the
  game rotation happens while falling, so this is a stress-case only — but worth
  a QA eye.
