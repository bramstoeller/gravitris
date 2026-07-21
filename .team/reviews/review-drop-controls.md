# Review: feat/drop-controls (PR #23) — the combined shaped-and-controlled game

Verdict: approve
Range: `origin/main` `3ec22aa` .. `origin/feat/drop-controls` `cfaed15`

The two halves combined: the Backend's tetromino geometry + lifecycle (which I
approved as #24, and which carries the lattice-4 pin commit `dce2c7d`) merged into
the Frontend's slide/drop/rotate controls, plus the `:app` render integration that
makes tetrominoes actually render. I reviewed the **integration delta** — the
`:app` changes — since I have already reviewed both halves. It is clean and
**CI is green** (the combined `:app` + `:core-sim`, `build-and-test` pass), which
is the property #24 and the controls could not have alone. Clear to land.

## Blocking

None.

## Verified — the integration delta is correct

- **Buffer sizing generalized correctly; no under-sizing, no overflow.** `BodyMesh`
  now takes `maxParticles` (not `maxBodies`) and works in **cell** units:
  `particlesPerCell = lattice²`, `maxCells = maxParticles / particlesPerCell`,
  index pattern per cell. Crucially, `GameRenderer` now **consumes the core's own
  capacity** instead of re-deriving it: it builds a never-stepped worst-case
  `Simulation` and reads `maxParticles = worstCaseState.particleCapacity` and
  `bodyLattice`. The old shell formula `max(64, 2·ceil(wellArea/pieceExtent²))`
  assumed one cell per body and under-counted a four-cell tetromino 4× — exactly
  the overflow the Backend flagged (handoff 0029). The capacity check
  `session.state.particleCapacity <= maxParticles` holds because both sides read
  `particleCapacity` and every real well ≤ the worst-case well; a new
  `bodyLattice == worstCaseState.bodyLattice` check guards the one-tier index
  topology. `BodyMesh.init` also enforces a whole-cells invariant and keeps the
  65 536 `GL_UNSIGNED_SHORT` cap.
- **The render loop iterates cells — general case correct.**
  `indexCount = (particles / particlesPerCell) * indicesPerCell` draws every cell
  (four per tetromino), and the index buffer is built for `maxCells` cells as the
  per-cell pattern at `k · lattice²` offsets. This is the same per-cell pattern the
  core publishes as `triangleIndices` (now documented per-cell, #24 commit 3) and
  that `TopologyMatchesSolverTest` locks index-for-index — so producer and consumer
  agree, and a general stack of any shapes draws in one call.
- **Benchmark divisor divides matched scenes — the derating is honest.**
  `HOST_P50_MS` moved from the old single-block `0.4443` to `0.86` (the pinned
  tetromino scene: 24 tetrominoes at lattice 4, 1 536 particles, 7 296
  constraints), and `HOST_REF_PARTICLES = 1536` pins the scene the divisor was
  measured on. `Simulation.buildBenchmarkScene` is shared, so the device runs the
  identical 1 536-particle scene and `device / host` is a pure hardware-derating
  number; `SolverBenchmarkTest` fails if the scene drifts from 1 536, forcing a
  re-measure. `SPIKE_HOST_P50_MS` is kept but explicitly marked not-the-divisor and
  not comparable to the tetromino figure.
- **Palette 6→7 (Emerald) — additive and it closes a debt I raised.** A seventh hue
  (Emerald `#3BA12B`, H112, 47° clear of the reserved 15–65° glow band) is appended
  at index 6; `SURFACE_INDEX` 6→7, `SIZE` 7→8, `GRAIN` extended to eight slots — all
  consistent, and the existing six hues are unmoved (no CVD re-derivation). This
  resolves the exact 7-vs-6 collision I flagged in the #19 review: `pieceHue` is now
  the identity map for all seven archetypes, with `floorMod` kept only as a
  one-instruction out-of-range backstop. UX added the hue (`visual-direction.md`);
  the shell did not decide it unilaterally.
- **`slamActivePiece` migrations + control removal — consistent.** The hard-drop
  threshold family (`HARD_DROP_*`, `VELOCITY_WINDOW_NANOS`) and `VelocityWindow.kt`
  are deleted with the control redesign (release is the drop; no swipe-flick to
  recognise), and the `:app` range tests migrate to `slamActivePiece(30f)` for the
  impact-velocity probe — the 30-unit contract the Backend pinned. CI green confirms
  they pass.
- **Consistency across the combined branch.** `dce2c7d` (lattice-4 pin) is in this
  branch and `SimConfig.lattice = 4`, so game default, benchmark scene and mesh
  sizing all agree on the pinned tier. The well was widened to 20×48 for the
  shaped field (agreed with the Backend), and the mesh worst-case consumes those
  same `Tunables`.

## Notes (non-blocking)

- This PR is the **consumer** side of the tetromino/lifecycle contract I flagged as
  lacking a recorded sign-off in my #24 review — a green, rendering consumer
  implementation is strong evidence the `InputFrame{dragX,rotate,drop}` /
  `PiecePhase` / `particlesPerBody` / per-cell `triangleIndices` contract is right.
  Consider that comment closed by this branch.
- Trivial doc staleness: the `Tunables.WELL_WIDTH_WORLD` KDoc still works its
  example at lattice 5 ("~2.25 units wide … I-piece spans ~9.0") while the shipping
  pin is lattice 4 (I-piece ~9.6). The value (20) is correct for both; only the
  worked example is a tier behind. Cosmetic.

## What is good

- The buffer-sizing fix is the right shape: it deletes the shell's parallel
  capacity formula entirely and reads the core's published `particleCapacity`, so
  the two *cannot* drift — the class of bug that under-counted tetrominoes 4× is
  gone by construction, not patched. Same instinct as the band-count and
  triangleIndices contracts: consume the published value, don't re-derive it. And
  the benchmark change keeps the divisor honest by pinning it to the particle count
  it was timed on, with a test that breaks if they part.

---
*— **Code Reviewer***
