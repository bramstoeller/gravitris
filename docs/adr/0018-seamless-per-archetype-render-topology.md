# ADR 0018 — Seamless per-archetype render topology (adopt B1)

Date: 2026-07-21 · Status: accepted · Author: Backend Engineer
Adopts the **B1** option ADR 0015 recorded as an alternative, and supersedes its
"Rendering contract" default (**B2**). Amends **ADR 0007 §2** (index buffers).

## Context

The client's loudest surviving complaint on the glossy-candy render: a
multi-cell piece still "reads as four squares", split by a dark "+". The Frontend
Engineer traced it to source (handoff 0038) and it is a mesh-topology defect on
the `:core-sim` / `:app` seam, not shading:

- The render mesh is **per-cell**. `SoftBodyWorld.triangleIndices` is a single
  `L×L` cell pattern; `:app` tiles it once per cell at vertex offset `k·L²`
  (`LatticeTopology`, `BodyMesh`). There are **no triangles bridging two cells**.
- The body-wide UV (§15) made shading continuous *within* a piece, but at a seam
  the two cells are separate meshes with no bridging geometry. Their facing
  columns differ by exactly one grid step of UV, and nothing interpolates across
  that step — so grain and specular **step** by one grid unit right at the join.
  That step is the "+".
- Shading was ruled out: `particleEdge`, `particleContact` and the subsurface
  term are all correctly 0/symmetric across an internal seam. Verified against
  source, not recalled.

This is exactly the case ADR 0015 anticipated. It shipped **B2** (keep the
per-cell pattern; close the 2r seam with the ADR 0011 silhouette extrusion) and
recorded **B1** (per-archetype triangle patterns that fill seams with real
geometry) as the fallback "if the Frontend's extrusion cannot close a 2r gap …
the choice is theirs (they own the mesh)". Their extrusion *can* close the gap,
but only by pushing both facing seam columns to the midpoint and collapsing them
coincident — which is what stamps the UV discontinuity onto the join. So the
Frontend has called for B1.

## Decision

**A tetromino renders as ONE continuous footprint-lattice mesh per archetype.**
The core publishes, per shape, the whole-piece body-local triangulation — the
four cells' own triangles **plus** the seam-bridge triangles that weld adjacent
cells — so an internal seam becomes an interior mesh line with full triangle
bridging and continuous UV. The "+" disappears at its source.

Two additive `SimState` fields carry it (additive per contracts.md §5 — new
fields, no signature removed; the Architect need not sign a breaking change):

- **`bodyTriangleIndices: Array<IntArray>`** — indexed by archetype
  (`ARCHETYPE_COUNT = 7`). Each entry is the body-local triangle indices for a
  whole tetromino of that shape, values in `[0, particlesPerBody)`. Interior
  cells use the same `p00,p10,p11 / p00,p11,p01` split as `triangleIndices`; the
  bridges are the solver's seam area triples verbatim. Length varies by shape:
  an O bridges four seams, the other six three (a jagged array, not a stride).
- **`particleFreeEdges: IntArray`** — per-particle bitmask of the sides on which
  a particle is TRUE outer silhouette: `LEFT=1, RIGHT=2, DOWN=4, UP=8`.
  `particleEdge[i] == 1f ⇔ particleFreeEdges[i] != 0`. It is the per-direction
  detail behind `particleEdge`, which is only its OR.

### Why the bridges are the solver's area constraints

The solver already welds a seam with structural, shear **and area** constraints
(`SoftBodyWorld.buildSeams`), and the seam area triples are wound to match the
interior cell split. So the render topology is defined as *the solver's
area-constraint triangulation* — interior cell areas + seam areas, minus ADR
0015's inert padding. Every render triangle across the whole piece, seams
included, is one area constraint. That buys more than seamless UV: because
`particleCompression` is a per-area-constraint ratio, **compression shading is
now continuous across a seam too**, not only the grain and specular. And the
render mesh cannot drift from the constraints, because it is built from the same
index expressions (`SeamlessTopologyTest` pins render triangles == area
constraints, index set for index set).

### What `:app` does with the free-edge mask

The ADR 0011 silhouette extrusion must push a boundary particle out by
`particleRadius` **only** along the sides whose bit is set — one radius per free
axis, `radius·√2` only when both meeting sides are free — and leave a
seam-facing side at its centre. The bridge geometry then fills the `2·radius`
seam at its natural width: the facing columns stay `2·radius` apart and the
bridge quad interpolates UV/compression across them. No collapse, no gap, no
step. This replaces the pre-0018 "extrude every cell's outer ring" logic, which
had no way to tell a silhouette edge from a seam edge (it only had the OR'd
`particleEdge`) — the "and what do I give them instead?" value that makes the
new constraint livable.

### Nothing in the vertex path changes

Bridges reuse existing particles — no new vertices. Positions, UV, compression,
contact, corner, the archetype and edge attributes are all untouched. Only the
index buffer and the extrusion change, both on `:app`.

## Amends ADR 0007 §2

ADR 0007 §2 says index buffers are "uploaded once per lattice size and reused
for every body forever". With per-archetype topology the pattern depends on
`bodyArchetype[b]`, so the index buffer is **assembled when the set of bodies
changes** — concatenating `bodyTriangleIndices[archetype[b]]` offset by
`b·particlesPerBody` — not once in `onSurfaceCreated`. This is the same
body-set-change schedule `BodyMesh` already uses to rebuild its static
archetype/UV buffers, so it stays **off the per-frame path**: zero per-frame
allocation holds, and it remains **one draw call** for the whole stack. The
`LatticeTopology`-derives-from-lattice-alone rationale (build before a
`Simulation` exists) no longer applies to this buffer; the topology now comes
from `SimState`.

## Consequences

- The internal seam "+" is gone: a piece reads as one continuous glossy candy.
  The proof is `:app`'s re-render (the Frontend's half); the core proof is
  `SeamlessTopologyTest` — every seam bridged, no triangle edge spanning a UV
  discontinuity, render triangles == the solver's area constraints.
- **More geometry.** Bridges add one seam strip per adjacency: `2(L-1)`
  triangles each, 3 seams for six shapes and 4 for the O. At the shipped
  lattice 4 that is `3·6 = 18` (O: `24`) extra triangles on a `4·2·9 = 72`
  interior, roughly +25–33% triangles per body. On a near-full well (~30 bodies)
  that is ~2 160 → ~2 880 triangles — trivial for the GLES3 pipeline and far
  under the fragment-bound budget ADR 0006/0009 actually protect; the seam
  strips are thin, so fragment cost is negligible. The extra index bytes are
  paid on body-set change, not per frame. No measured frame-budget risk, but the
  Frontend re-checks on the Fairphone as part of their half.
- `triangleIndices` is superseded for rendering but kept: the solver still
  defines its cell area constraints on those two triangles, and `:app`'s
  `TopologyMatchesSolverTest` pins the per-cell split to it. It is retired once
  `:app` no longer reads it — a breaking removal the Architect signs then.
- The change is delivered in two halves. `:core-sim` (this ADR's fields + tests)
  ships additively and independently green; `:app` (index assembly move,
  `extrudeBoundary` gated on `particleFreeEdges`, re-render) is handoff 0039.

## Follow-up: the O centre junction (2026-07-21)

The `:app` half (handoff 0040) shipped and the "+" is gone on I/T/S/Z/J/L — but
the O showed a small square hole at its dead centre. The O is the only shape
where four cells meet at a *point* (a 2×2 block), and the seam model above is
strictly edge-to-edge: the four pairwise seam bridges each clip one corner of the
central 2r×2r square and leave the middle untriangulated. The old per-cell
extrusion had masked it by collapsing the four inner corners over the centre; the
correct silhouette extrusion (gated on `particleFreeEdges`) exposed it.

**Fix: fill the junction square with two render triangles on its four inner
corners, and add NO solver constraint.** `bodyTriangleIndices[O]` gains the two
triangles; nothing else changes. Two measured facts drove the render-only choice:

- **The obvious near-rigid area constraint destabilises heavy piles.** Backing
  the fill with a cell-style area constraint (the natural way to keep "every
  render triangle is an area constraint") turns the O centre into a stiff mode the
  solver cannot damp in 8 substeps against the single global `areaCompliance`
  (there is no per-constraint compliance). Measured: a mass-8 O-bearing pile that
  used to settle rings at kinetic energy ~0.08–0.41 and never falls below the 0.05
  quiet line across 6000 ticks — a direct regression of ADR 0003's stability
  guarantee. Adding shear diagonals instead of, or alongside, area did not rescue
  it. So no junction constraint is added.
- **It is safe without one.** The junction square's four edges are already welded
  by the four seams' structural constraints, so the hole cannot reopen and the
  centre cannot tear. And `particleCompression` is per-*particle* (accumulated
  from the constraints touching each particle, `XpbdSolver` compression pass), so
  the fill triangle shades correctly by interpolating its four corners — each of
  which is already constrained by its cell and the seams. The O settled fine
  before this change; the defect was render-only, so the fix is render-only.

This is the single documented exception to "every render triangle is a solver area
constraint": `SeamlessTopologyTest` now asserts every area constraint is drawn and
that the *only* render triangles beyond them are the O's two junction fills (a
triangle spanning three distinct cells, which nothing else produces). `:app` needs
zero changes — it concatenates `bodyTriangleIndices` by length regardless of how
many triangles a shape carries.

Rejected: a per-constraint area-compliance field so the junction could be a soft
area constraint. It is the "correct" physical answer, but it adds an array read to
the area-solve hot loop on every constraint every substep — a per-frame cost on
the Fairphone budget (ADR 0009/0014) for a tear the welded perimeter already
prevents. Revisit only if the O centre visibly deforms wrongly in play; it is a
look-call for the Frontend's re-render, not a data defect.

## Alternatives considered

**Stay on B2 and tune the shader to hide the step** — rejected. The step is a
missing-geometry problem; no shader term can interpolate across geometry that is
not there. It would be hiding the defect, not fixing it.

**Add bridge triangles as a separate `seamIndices` array, keep tiling the
per-cell pattern** (the literal "option (a)" of handoff 0038) — rejected as the
delivered shape though it is mechanically the same triangles. It would force
`:app` to draw two index ranges (interior tiled + bridges per-body) or two draw
calls, splitting one clean per-archetype concept into two. Since bridges are
archetype-dependent the IBO must be assembled per body-set change regardless, so
folding interior + bridges into one per-archetype array is no more invasive and
leaves a single topology definition. Recorded because it is the losing half of a
real decision.

**Rounding the true union-of-disks surface at corners with a bridge fan** —
rejected, out of scope. §16's `particleCorner` already rounds the silhouette in
the shader without new geometry; this ADR is only about the internal seam.
