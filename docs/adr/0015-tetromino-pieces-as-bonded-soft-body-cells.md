# ADR 0015 — Tetromino pieces as four bonded soft-body cells

Date: 2026-07-21 · Status: accepted · Author: Backend Engineer
Supersedes the Stage-3 note that "every piece is the same square lattice; the
archetype varies only colour" (ADR 0001 tail, `Simulation.ARCHETYPE_COUNT`).

## Context

The client played the wired game and reported it "super easy and boring",
naming the cause: every piece is a single featureless square. They want the
seven classic tetrominoes (I, O, T, S, Z, J, L) — each four small cells joined
into one connected, rotatable, *deformable* piece — so filling a row becomes a
spatial fitting puzzle. The client-approved squash/settle feel must survive:
a tetromino should deform and pile like today's blocks, just with shape.

The solver (ADR 0001/0003) is a generic XPBD loop over `distanceCount`,
`areaCount`, `particleCount` — it does not care about piece shape. But two
things in `:core-sim` are built on **fixed body-major strides**, and they are
the parts most expensive to get wrong:

- `SoftBodyWorld.removeBody` swap-removes a body by copying three
  contiguous fixed-size ranges (`particlesPerBody`, `distancePerBody`,
  `areaPerBody`) and rebasing constraint indices by a single shift. A clear
  runs this, and `DeterminismTest` holds it bit-identical.
- The renderer (`:app`) reuses **one** body-local triangle index pattern for
  every body at vertex offset `body * particlesPerBody`
  (`LatticeTopology`, `TopologyMatchesSolverTest`).

## Decision

**A tetromino is ONE soft body of four `L×L` lattice cells** (`L =
config.lattice`, the existing quality tier). One `bodyArchetype`, one hue, one
`activePieceBody`. The body-local particle layout is: cell `c ∈ 0..3` owns
indices `[c·L², (c+1)·L²)`; within a cell, particle `(row, col) = c·L² +
row·L + col`, row 0 at the bottom — the **same** per-cell layout as today's
single piece, so a cell *is* today's block.

**`particlesPerBody = 4·L²`, uniform across all seven shapes.** The shape lives
in the four cells' *rest positions*, not in the particle count or the internal
topology. This is the load-bearing choice: every fixed-stride assumption above
survives unchanged — only the constant `particlesPerBody` (and the derived
`distancePerBody`/`areaPerBody`) grows. Swap-remove is untouched.

### Cells are contiguous and bonded across the seam

Adjacent cells are placed exactly one lattice `spacing` apart at the facing
edge (cell pitch `= L·spacing`), so a bonded pair is a **continuous lattice** —
the seam carries the same rest lengths as interior material and reads as solid,
not as a hinge. Each seam adds, across the two facing edges: `L` structural +
`2(L-1)` shear distance constraints, and `2(L-1)` area constraints on the seam
cells (volume preservation, so the joint does not pinch under load). The seam
constraints are the only thing that makes the four cells one body.

### Constant stride via inert padding for the 4th seam

Cell-adjacency graphs: six shapes are **trees** (3 seams); **O** alone has a
cycle (4 seams). To keep `distancePerBody`/`areaPerBody` constant — the whole
point of the single-body decision — every body reserves **four** seams' worth
of constraint slots. A tree fills three and pads the fourth with **inert**
constraints: a distance constraint with `a == b` and an area constraint with
`a == b == c`. These are provable no-ops through the solver's *existing*
guards — `solveDistance` skips `d < EPS` before any division (XpbdSolver), and
`solveArea` skips `wsum < EPS`; the compression pass adds a zero rest area,
which is ignored. A test asserts a tree tetromino is bit-identical with the
padding present versus a hand-built unpadded equivalent: the padding must never
move a particle.

Rejected: making the fourth seam a *duplicate* of a real one (real, not inert).
It doubles one seam's stiffness and would make O and, say, T subtly different in
a way nobody chose.

### Physics scale is unchanged; the well widens instead

A cell keeps today's exact scale (`PIECE_WIDTH`, `spacing`, `particleRadius`,
`gravity`, both compliances). A tetromino is therefore ~4× the material of
today's piece; the I is four cells (~9 world units) long. To keep the shapes
playable and to make them read as "four *small* squares" on screen, the **well
widens** — world→screen scale is free, so a wider well simply draws everything
smaller. `maxBodies` is re-derived against tetromino area (`≈ 4·pieceExtent²`),
not cell area. Well geometry stays Frontend-owned (ADR 0010).

Rejected: shrinking the cell to half scale so a tetromino ≈ today's piece. That
rescales `spacing`/`radius`/`gravity`/compliance and re-opens every tuned feel
number — the "tune it twice" trap ADR 0001 warns against. Widening the well
preserves the approved feel by construction.

## Rendering contract

> **B1 adopted by [ADR 0018](0018-seamless-per-archetype-render-topology.md)
> (2026-07-21).** The B2 default below shipped, but its per-cell mesh reads as
> "four squares" split by a "+" (the Frontend's extrusion closes the 2r seam
> only by collapsing the facing columns coincident, which stamps a UV
> discontinuity on the join — handoff 0038). ADR 0018 switches to the B1
> alternative recorded here: per-archetype triangle patterns that bridge the
> seams with real geometry (`bodyTriangleIndices`), plus a per-direction
> free-edge mask (`particleFreeEdges`) so the extrusion pushes only true
> silhouette. The physics below is unchanged.

The default exposure (**B2**) keeps `SimState.triangleIndices` as the single
per-cell pattern the renderer already consumes (a cell == today's body, so the
array is unchanged); the renderer reuses it for `4 · bodyCount` cells at offset
`k·L²`, and the ADR 0011 silhouette extrusion closes the `2·particleRadius`
seam gaps. The core marks `particleEdge` on the shape's **true outer boundary**
(0 on seam-facing edges) so internal seams do not rim-light. `particlesPerBody`
is published additively so the renderer sizes buffers against `4·L²`. An
alternative (**B1**, per-archetype triangle patterns that fill seams with real
geometry) is available if the Frontend's extrusion cannot close a 2r gap; the
choice is theirs (they own the mesh) and is recorded in
`.team/conversations/2026-07-21-backend-engineer.md`. Either way the physics
above is identical.

## Consequences

- The seven shapes deform, settle, and pile like today's blocks, with shape.
- Coverage-band clearing is unaffected: bands stamp per particle
  (shape-agnostic) and the clear removes whole bodies by centroid band. Proven
  by test on shaped pieces.
- A tetromino is four cells, so ~4x the material density of a single block. The
  measured cost of a near-full 20x44 well is ~0.78 ms/frame at lattice 4 (in
  budget at the 12x device derating) and ~1.56 ms at lattice 5 (over). That
  measurement is the input to **ADR 0014**, which pins the shipping lattice at
  4; the tier decision lives there, not here. `buildBenchmarkScene` is re-defined
  to a near-full tetromino pile as the calibration reference for that pin.
- A tetromino spans more bands than a block, so a clear releases more material —
  the same "material in the glowing zone is released" reading ADR 0005 already
  documents, more so.
