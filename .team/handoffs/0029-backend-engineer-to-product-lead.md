# 0029 — Backend Engineer → Product Lead

Date: 2026-07-21 · Branch: `feat/tetromino-pieces` · Commits: `90d7c2a`, `9c13e84`

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

1. **PERF — a real decision, flagged with numbers.** A tetromino is ~4× the
   material of a single block. Measured on the host: a near-full 20×44 well is
   ~1 700 particles at lattice 4 (~0.78 ms/frame, ≈9 ms at the 12× device
   derating — in budget) but ~3 100 at lattice 5 (~1.56 ms, ≈**19 ms — OVER**
   the 16.67 ms budget). This is exactly what the ADR 0009 startup tier
   selection exists for: I made the benchmark measure tetromino density, so the
   reference device now selects **lattice 4** and lattice 5 is reserved for
   devices that benchmark faster. Nothing to do unless you or the Architect want
   a different call (smaller well, or shipping L5). The real-device measurement
   (open blocker) will confirm.
2. **Well width is the Frontend's, but it interacts with me.** They're going
   ~20 wide / up to ~48 tall for ~9 columns. I'll scale `bandColumns` and
   retune `clearThreshold` (a live dial) on the wider/taller field in front of
   the client — `bandHeight` roughly doubles. Not blocking.
3. **Palette 6→7** is UX/Frontend's; they took Emerald #6 for the 7th hue.
4. **PR / landing.** CI (`make test && make build`) builds `:app` too, and
   `:app` is **red on `feat/tetromino-pieces` in isolation** — it references the
   removed `hardDrop`/`hardDropVelocity` and the not-yet-landed `drop`. The two
   halves are green only **combined**: the Frontend merges my branch into
   `feat/drop-controls`, fixes four `:app` files (all mechanical —
   `slamActivePiece(30f)` reproduces the old 30-unit slam so the compression/
   haptic tuning does NOT move), and you land the combined result. **The
   Frontend is blocked** on a worktree-binding env issue and has escalated to
   you — unblocking them is the path to a green combined PR. My PR is opened for
   review/audit and is `:core-sim`-green; its `:app` red is by design and handed
   over per the above, not a defect to chase.

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
