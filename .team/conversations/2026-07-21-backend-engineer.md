# Backend Engineer — tetromino pieces + drop-control lifecycle

Date: 2026-07-21 · Branch: `feat/tetromino-pieces` (off `main`)

Design decisions for the tetromino piece system and the new positioning →
falling control lifecycle. Contract co-designed with the Frontend Engineer
(their message + my reply, both recorded). This file is the reasoning; the
expensive-to-reverse calls become ADRs.

## What the client changed

1. Pieces must be real **tetrominoes** (I, O, T, S, Z, J, L) — four connected
   cells, rotatable — not featureless single squares. This is the fix for
   "super easy and boring".
2. **New control model** (reverses the earlier "pieces hang until dropped"):
   piece appears at top → player slides it left/right for a **short**,
   time-limited window → release (or window expiry) → it **falls under real
   gravity** → during the fall the player may **rotate** (tap) but not slide →
   it lands, deforms, settles, locks → next piece. The time pressure is half the
   difficulty fix; the shapes are the other half.

## Geometry model — DECIDED

A tetromino is **ONE soft body** (one `activePieceBody`, one `bodyArchetype`,
one hue). Not multiple bodies. This keeps the Frontend's rendering (one
hue/body via `vBodyIndex`) and input (one active index) assumptions intact.

The body is **4 square lattice cells**, each an `L×L` block (`L = config.lattice`,
the existing quality tier), identical to today's single piece. Body-local
particle layout: cell `c ∈ 0..3` owns indices `[c·L², (c+1)·L²)`; within a cell
particle `(row, col) = c·L² + row·L + col`, row 0 at the bottom — the **same**
per-cell layout as today, so today's per-cell triangulation and area
constraints apply unchanged per cell.

**`particlesPerBody = 4·L²`, uniform across all seven shapes.** The shape is
encoded in the *rest positions* of the four cells, not in the particle count or
the internal topology. This is the key decision: it keeps every fixed-stride
assumption in `SoftBodyWorld` (`removeBody` swap-remove, the solver's
body-major indexing) working unchanged — only the constant `particlesPerBody`
grows.

### Cells are contiguous, bonded across the seam

Adjacent cells are placed one lattice `spacing` apart at the facing edge (cell
pitch `= L·spacing`), so a bonded pair is physically a **continuous lattice** —
same rest lengths as interior material, so the seam feels like solid material,
not a hinge. Bonds per seam: structural (`L`) + shear (`2(L-1)`) distance
constraints across the seam. Considered adding seam *area* constraints and
rejected for now: structural+shear already prevent the parallelogram/hinge
collapse, and leaving area per-cell keeps the render triangulation matching the
solver's area constraints cell-for-cell (the invariant `TopologyMatchesSolverTest`
guards). If the seam thins visibly under load in play, seam area is a localised
add.

### Constant constraint stride via inert padding

Cell adjacency: six of the seven shapes are **trees** (3 seams); **O** is the
only one with a cycle (4 seams). To keep `distancePerBody` constant (so
swap-remove's fixed strides survive), every body reserves **4** seams' worth of
constraint slots; a tree shape fills 3 and pads the 4th with **inert**
constraints — a distance constraint with `a == b` (rest 0) and, if area is ever
added, `a == b == c`. These are provable no-ops through the solver's *existing*
guards: `solveDistance` does `if (d < EPS) continue` (XpbdSolver line 262) so a
zero-length constraint is skipped before any division; `solveArea` does
`if (wsum < EPS) continue` (line 307). Verified by reading the kernels. A test
asserts a tree tetromino's state is bit-identical with and without the padding
present (the padding must never move a particle).

Considered instead making the 4th seam a *duplicate* of a real seam (real, not
inert) — rejected: it would double one seam's stiffness and make O and, say, T
subtly different in a way nobody chose.

### Physics SCALE is unchanged — feel preserved by construction

A cell keeps today's exact scale (`PIECE_WIDTH`, `spacing`, `particleRadius`,
`gravity`, both compliances — all unchanged). A tetromino is therefore ~4× the
material of today's piece and up to 4 cells (~9 world units) long for the I.
The well is widened so the shapes are playable and read as "four *small*
squares" on screen (world→screen scale is free; a wider well shrinks everything
on screen). **No physics constant is retuned** — the client-approved squash/
settle feel is preserved exactly. Well width is Frontend-owned (inset-derived,
ADR 0010); the widening is a coordination point, and `maxBodies` is re-derived
against tetromino area (÷ ~4·cellArea) not cell area.

Rejected: shrinking the cell to half scale so a tetromino ≈ today's piece. That
rescales `spacing`/`radius`/`gravity`/compliance and re-opens every tuned feel
number — the "tune it twice" trap. Not worth it; widen the well instead.

## Rotation semantics — DECIDED (ADR)

Snap-rotate the **particle positions** by an exact quarter turn `(x,y)→(y,-x)`
about the centroid — the existing `applyRotate`. It already works for a
tetromino unchanged, because the distance/area constraints are **isotropic**
(they constrain lengths and areas, which a rotation preserves), so a rotated
configuration is *already a rest state* and the material holds its new
orientation without springing back. No rest-shape to rotate, no lookup table,
exact and deterministic (ADR 0006). Overlap is **rejected outright** (restore
saved positions) — already implemented, already the fix for the launch-energy
bug. Rotation during the fall keeps the piece's downward velocity (rotate only
rewrites `framePrev`/`substepPrev`, not `velX/velY`).

## Lifecycle state machine — DECIDED (ADR)

Per active piece, two phases (game-level `Phase` Playing/Clearing/Overflow/
GameOver is orthogonal and unchanged):

- **POSITIONING**: piece parked at the spawn row, **gravity suppressed**,
  horizontal slide only. Implemented by setting the active body's particles
  `invMass = 0` (kinematic) — reuses the solver's existing `invMass==0` skips in
  integrate/contacts, no new hot-loop branch. A tick-counted window
  (`MechanicTuning.positioningTicks`, live-tunable, ADR 0013) counts down.
  Slide = `dragX` (existing `applyDrag`, clamps already generalise over actual
  particles). Rotate ignored. `drop` = release now.
- **FALLING**: on release (`drop`) or window expiry, restore `invMass = 1/mass`;
  gravity takes over; the normal solver runs. Rotate (tap) enabled, slide and
  drop ignored. Existing `hasSettled` → `lockActivePiece` unchanged; deform/
  settle/lock all happen in FALLING until lock. Then `activePieceBody = -1`,
  next piece spawns into POSITIONING.

Exposed to `:app` per tick: `activePiecePhase: PiecePhase {POSITIONING, FALLING}`
(defaults to FALLING when no active piece, so a stray `dragX` can't slide a
non-positioning piece), `positioningTicksRemaining: Int` (0 outside),
`positioningWindowTicks: Int` (so the Frontend draws a 0..1 countdown without
re-deriving it). Guarantee: `activePiecePhase == POSITIONING ⇒ activePieceBody ≥ 0`.

## InputFrame — DECIDED (signature change, Architect to sign)

`{ dragX: Float, rotate: Boolean, drop: Boolean }`. Remove `hardDrop` +
`hardDropVelocity` and all hard-drop machinery. Release IS the drop and the fall
is real gravity, so a flick-boost is redundant — plain gravity. Agreed with
Frontend. Crosses the module boundary (contracts.md §2/§5) → Architect signs;
Frontend is looping them.

## Render exposure — ONE open sub-item, does NOT block the lifecycle work

Physics above is fixed. How triangles/edges are exposed for the mesh is a
Frontend-owned rendering call, offered as two options (see my message to them):

- **B2 (recommended)**: keep the single per-cell triangle pattern (today's
  `triangleIndices`, unchanged — a cell == today's body), reused for
  `4·bodyCount` cells at offset `k·L²`. The 2r seam gap is closed by the
  existing ADR 0011 silhouette extrusion (each cell is still a square, so
  `extrudeBoundary`/`RenderFootprintTest` stay square-per-cell). I supply
  `particleEdge` marked on the shape's **true outer boundary** (0 on
  seam-facing edges) so internal seams don't rim-light. Lowest risk — keeps
  `LatticeTopology`/`extrudeBoundary`/`TopologyMatchesSolverTest` almost intact.
- **B1**: I emit **per-archetype** triangle patterns that fill seams with real
  geometry (`triangleIndicesFor(archetype)`), vertex base still `body·4L²`.
  Cleaner mesh, bigger Frontend change (7 IBOs, generalised boundary).

Recommend B2 if their extrusion closes a 2r seam cleanly; else B1. This is the
only item still open and it does not block either side starting the input/phase
lifecycle work (feat/drop-controls is phase-gated input, topology-independent).

## Palette 6→7 — flag to UX + Frontend (not mine)

`Palette.pieceHue = floorMod(archetype, 6)` folds 7 archetypes onto 6 hues, so
archetype 6 collides with hue 0. With 7 tetromino types and hue as the identity
cue, two distinct piece *types* now share a hue — a real legibility bug. Palette
contents are UX-owned (contracts.md §5); flagged to Frontend to resolve with UX
(needs a 7th hue: `PIECE_COUNT` 6→7, `SIZE` 7→8, `SURFACE_INDEX` moves).

## Coverage-band clearing — verify, expect no change

Bands stamp every particle's disk (`CoverageBands.update` loops particles,
shape-agnostic); the clear removes whole bodies by centroid band. Both work on
tetrominoes with no change. Adding a test that a band fills and clears with
shaped pieces to prove it.

---

# Seamless render topology — B1 adopted (later same day)

Branch `feat/candy-seamless-mesh` (off `feat/candy-material-render`). Fixing the
internal cell-seam "+" — the client's loudest surviving complaint — per Frontend
handoff 0038. This is the open "render exposure" sub-item above, now resolved: we
switch from **B2** to **B1**, recorded as **ADR 0018**.

## Why B1 now

The Frontend measured the "+" to source: the render mesh is per-cell
(`triangleIndices` tiled per cell, no bridging triangles), and body-wide UV can't
fix a *missing-geometry* problem — at a seam the two cells' facing columns differ
by one UV grid step with nothing interpolating across it, so grain/specular step
right at the join. Their `extrudeBoundary` closes the 2r gap only by collapsing
the facing columns coincident, which is what puts the discontinuity on the join.
B1 (per-archetype mesh that bridges seams with real geometry) removes it at
source. B2's premise — "the extrusion closes a 2r seam cleanly" — turned out
false in exactly the way ADR 0015 flagged as the trigger to switch.

## The one thing that made this clean

The solver already added seam **area** constraints (this reverses the "leaving
area per-cell" note above — seam area was added when the tetromino work landed;
`buildSeams` populates `shapeSeamAreaA/B/C`). So I define the whole-piece render
topology as *the solver's area-constraint triangulation*: interior cell areas +
seam areas. The bridges are the seam area triples verbatim. Consequence beyond
seamless UV: `particleCompression` (a per-area-constraint ratio) is now
continuous across a seam too, and the render mesh cannot drift from the
constraints. `SeamlessTopologyTest` pins render triangles == area constraints.

## Contract — two additive `SimState` fields (§5, no signature removed)

- `bodyTriangleIndices: Array<IntArray>` — per-archetype whole-piece body-local
  topology (interior cells + seam bridges). Jagged: O has 4 seams, the rest 3.
- `particleFreeEdges: IntArray` — per-particle silhouette-direction bitmask
  (LEFT=1/RIGHT=2/DOWN=4/UP=8); `particleEdge == (mask != 0)`. The "what do I give
  them instead" for constraining the extrusion to true silhouette — the Frontend
  had only the OR'd `particleEdge` and no way to tell a silhouette edge from a
  seam edge.

`triangleIndices` kept (solver diagonal doc + `TopologyMatchesSolverTest`);
retired once `:app` stops reading it (breaking change, Architect signs then).
Amends **ADR 0007 §2**: IBO is per-archetype, assembled on body-set change (still
off the per-frame path, still one draw call).

## Agreement carried in the record, not in a live conversation

I tried `SendMessage` to both **Architect** (owns the ADR/contract) and
**Frontend** (consumer, owns the `:app` half) to review the field shapes before
the code leans on them — neither was reachable. Per the dispatch's fallback the
agreement is written down instead of spoken: ADR 0018, `docs/contracts.md` §3
(the consumer-reviewable artifact), this record, and handoff **0039** to the
Frontend carry it in full. The `:core-sim` half is additive and independently
green, so it does not depend on that review to land; the Frontend must review the
contract as its consumer before their half leans on it, and the Architect should
confirm the ADR 0007 §2 amendment. Both asks are in 0039 and the ADR.

## Split of work

`:core-sim` (mine, this branch): the two fields + `SeamlessTopologyTest`, done
and green. `:app` (Frontend, handoff 0039): switch `BodyMesh`/`LatticeTopology`
to consume `bodyTriangleIndices`, move IBO assembly to the body-set-change path,
rewrite `extrudeBoundary` to gate on `particleFreeEdges`, update the app topology
tests, re-render the O and L to confirm the "+" is gone (the real, visual proof —
this container has no GPU).
