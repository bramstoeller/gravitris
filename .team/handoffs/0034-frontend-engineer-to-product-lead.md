# 0034 — Frontend Engineer → Product Lead

**Branch:** `feat/drop-controls` (PR #23). **Do not merge** — you land it once the
Code Reviewer clears PR #24's geometry.
**Commits this session:** `a0be5ed..ae01f68` (merge of the tetromino branch +
two integration commits, on top of the drop-controls work in handoff 0033).
**Status:** `make test` green (core-sim 84 + app + buildSrc + lint). Emulator
playthrough shows real four-cell tetrominoes.

This is the last integration step from handoff 0033: the shaped game is now
visible. PR #23 previously rendered single-cell blocks (it carried only the
Backend's contract commit); it now renders four-cell tetrominoes.

## What I did

### 1. Merged the Backend's tetromino geometry — commits 1+2+3

Merged `origin/feat/tetromino-pieces` up to **`dce2c7d`**, not just the geometry
commit `9c13e84` the dispatch named: commit 3 (`dce2c7d`) postdates the dispatch
and **pins the core `SimConfig.lattice` default to 4** (ADR 0014) and widens the
default well to 20×40. That is exactly the lattice-4 pin the dispatch wanted, and
PR #24 now tracks it — so taking it lets the app *consume* the pinned tier rather
than hard-coding 4. All `:core-sim`; no `:app` conflicts. (Merge commit `d5c6e99`.)

### 2. Sized the render buffers per-cell — the real code change

- **`BodyMesh`** now takes a **particle capacity**, not a body count, and iterates
  **cells**: `indexCount = (particleCount / bodyLattice²) × indicesPerCell`, the
  one per-cell index pattern reused per cell at a `cell·bodyLattice²` offset. A
  tetromino is four cells at the same stride a single block used to be (ADR 0015).
- **`GameRenderer`** no longer computes `maxBodies` from
  `wellArea / pieceExtent²` — that single-cell formula under-counts 4× and would
  trip the capacity assert on the first tetromino. It now builds a worst-case
  `Simulation` once and reads `SimState.particleCapacity` and `bodyLattice`
  directly, sizes the mesh off those, and **names no lattice** when building the
  session (it consumes the core's pinned default). Added a lattice-consistency
  assert alongside the capacity one.
- **Extrusion and per-cell UV/edge consumption are unchanged** — the "B2"
  rendering the Frontend and Backend agreed: cells are spaced 2r, so the ADR-0011
  silhouette skirts of adjacent cells meet and close the seam, and `particleEdge`
  is 0 on seam-facing edges so no rim-light draws a line down the middle of a
  piece. Confirmed on screen (below).

Commit `4dde8a5`.

### 3. Updated the render/toy tests for four-cell reality

Five tests encoded the old single-cell layout. All now pass:

- `SquishToyTest` first-tick asserts `particlesPerBody`, not `lattice²`.
- `SquishToy` construction guard relaxed from "well holds all 40 backstop bodies"
  to "well fits ≥1 piece" — 40 tetrominoes (4× material) don't fit a 10×20 well,
  and `TOY_MAX_BODIES=40` is deliberately a backstop the material-top reset
  reaches first, so demanding capacity for all 40 was wrong.
- `VertexFillTest` edge check now asserts the free-surface flag is a **subset of
  the cell boundaries, never the cell interior** (the outline, not each cell's
  ring).
- `RenderFootprintTest` extent check is now shape-agnostic and exact: drawn
  silhouette = particle-centre extent **+ 2·radius** (the material extent), so it
  needs no knowledge of which shape archetype 0 is.
- `SolverBenchmarkTest` asserts the **core's own scene count**, not the stale
  `960` literal.

Commit `ae01f68`.

## Verification — watched it, did not trust the green build

Software-emulator playthrough (SwiftShader — correctness only, no perf/appearance
claim), fresh APK:

- **Real tetrominoes.** Frame 001: a single violet **S-piece** at **72 triangles
  for 1 body** = 4 cells × 18 tri/cell at lattice 4. Frame 004: a magenta S/T and
  a blue L/S. Frame 999: a clean green **L/J-piece**. Every piece is a connected
  four-cell shape with **no internal seam lines** — the B2 extrusion closes the
  gaps. Not single squares.
- **The mechanic runs on the shaped pieces:** 8 spawns, **7 clears**, pieces
  sliding to columns, stacking, bands filling and releasing; 97 impacts. Seven
  hues seen across frames.

## Considered / decisions

- **Took commit 3, not just commit 2.** The dispatch said merge `9c13e84`; commit
  3 landed after and is what pins lattice 4 and what PR #24 tracks. Merging it
  routes the pin to its owner (the core default) so the app doesn't hard-code a
  tier — which is what "consume the config, don't hard-code a different lattice"
  asks for. Flag if you specifically wanted commit 2 only.
- Did **not** rename `LatticeTopology.buildIndices(maxBodies=…)`; the param is now
  a cell-instance count. Left it to avoid churning its tests' named args — the
  utility is unchanged, only the caller's unit is a cell now. Cosmetic.

## Open questions / for the Backend (raised directly, ref 03e864)

- **The benchmark divisor is stale.** `SolverBenchmark.HOST_P50_MS` is 0.4443 ms
  with a doc claiming "960 particles", but `buildBenchmarkScene` now yields
  `BENCHMARK_BODIES(24) × 64 = 1536` particles. The derating ratio would divide a
  1536-particle device time by a 960-particle host time. I left the `:app` test
  asserting the core scene (so it can't drift again) and did **not** touch
  `HOST_P50_MS` — the host measurement is the Backend's. They need to re-measure
  the divisor on the 24-tetromino scene, or set `BENCHMARK_BODIES≈15` to hold
  ~960 particles. Not blocking this branch.
- Lattice is pinned (no runtime tier selection). If a faster reference device
  should later select 5/6, that is a `:core-sim` change; the app already consumes
  whatever the core deals, so no app change is needed then.

---
*Opened by the **Frontend Engineer**.*
