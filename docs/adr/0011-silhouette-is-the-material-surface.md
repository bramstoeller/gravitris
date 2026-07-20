# 0011. The silhouette is the material surface, not the particle centres

Status: proposed
Date: 2026-07-20

## Context

The client's report on the Milestone 1 build was:

> "Alleen gek dat er zoveel marge om de blokjes heen zit (was ook al in vorige
> versie)."

Blocks never visually touched. A settled pile read as tiles floating near each
other rather than material pressed together.

It was not a physics problem. The backend engineer measured settled piles, with
pieces dropped exactly as the toy drops them, and pinned the result in
`ContactGapTest`:

| lattice | nearest inter-body centre distance | physical surface gap |
| ------- | ---------------------------------- | -------------------- |
| 4 | 0.599999 | -0.0000006 |
| 5 | 0.449999 | -0.0000009 |
| 6 | 0.359813 | -0.00019 |

Zero to within float noise at every tier. The bodies were genuinely touching.

The cause is a gap in this ADR series. Every position in the `:core-sim`
contract is a particle **centre**, and the solver treats material as reaching
`particleRadius` past it — contacts hold two touching bodies' centres exactly
`2 * particleRadius` apart, and a resting body's lowest centres sit exactly
`particleRadius` above the floor. **ADR 0007 specified the shading inputs in
detail but never said where the silhouette is.** `VertexFill` copied positions
verbatim into the vertex buffer, so every body was drawn one radius small on
every side and two touching bodies showed `2 * particleRadius` of background
between them.

At lattice 5 that is 0.45 world units — a quarter of a piece's width. We were
drawing **64%** of the material's actual area (56% at lattice 4, 69% at
lattice 6).

That omission is how this shipped, and the point of this ADR is that the next
person inherits a decision rather than an accident.

## Decision

**The drawn silhouette is the material surface: the particle-centre boundary
offset outward by `SimState.particleRadius`.**

Implemented as an in-place displacement of each body's outer ring of vertices,
in `VertexFill`, after the ADR 0006 interpolation lerp and before upload.

Three things follow from it.

**1. `particleRadius` is the expansion, consumed and never re-derived.** It is
the same number the contact solve and the placement guard use. Expand by exactly
it and the drawn surface coincides with the physical one: touching bodies meet
with no gap and no overlap, and a resting body sits on the drawn floor. The well
frame is already drawn at the true well bounds and the solver clamps centres to
one radius inside them, so extruded bodies meet the walls correctly with no
change to `WellFrame`.

**2. The offset is a fixed distance along the local outward direction — never a
scale about the centroid.** A uniform scale also closes the gap on an undeformed
piece, so it is a tempting one-liner, but it adds a *proportion* of the distance
from the centroid rather than a distance. On a deformed body it gives too little
extrusion where the body is thin and too much where it is wide — and deformation
is the entire point of this product. `RenderFootprintTest` fails a centroid
scale.

The outward direction is taken as the vector from a boundary particle's inward
reference neighbour to the particle itself, measured on the *deformed* lattice,
so it follows the material as it squashes and shears. The reference is one
lattice step inward on each axis the particle is on an edge of: one step for an
edge particle, one diagonal step for a corner. Every such reference is strictly
interior for any lattice of 3 or more, and interior particles are never moved —
which is what makes this a single in-place pass with no scratch buffer and no
per-frame allocation.

**3. Corners are squared, not rounded.** The true surface of a union of disks is
a circular arc at a body's corner. Reproducing it needs vertices this mesh does
not have. Squaring the corner — displacing by `radius * sqrt(2)` along the
diagonal, so the two offset edges meet — overshoots the true arc by
`radius * (sqrt(2) - 1)`, about 0.09 world units at lattice 5, at four points
per body. Blocks with sharp corners is also what the piece is meant to look
like.

### Measured result

Minimum distance between the two closest bodies' drawn outlines in a settled
four-piece pile, measured outline-to-outline (`RenderFootprintTest`):

| lattice | drawn gap before | drawn gap after | particle diameter |
| ------- | ---------------- | --------------- | ----------------- |
| 4 | 0.514 | 0.0064 | 0.600 |
| 5 | 0.384 | 0.00009 | 0.450 |
| 6 | 0.299 | 0.0055 | 0.360 |

### Measured cost

Cost of the extrusion alone, host JVM, against the ADR 0009 derating of 12.06x:

| scene | particles | extrusion, host | on device | % of a 16.67ms frame |
| ----- | --------- | --------------- | --------- | -------------------- |
| 24 bodies, lattice 5 (the toy on device) | 600 | 672 ns | 8.1 us | **0.049%** |
| 40 bodies, lattice 5 (toy cap) | 1 000 | 1 133 ns | 13.7 us | 0.082% |
| 60 bodies, lattice 4 (ADR 0001 reference) | 960 | 1 279 ns | 15.4 us | 0.093% |

Under 0.1% of a frame in every case, against a solver that takes 32.2%. It is
one square root per boundary vertex and nothing else.

**Vertex count, bandwidth, draw calls and per-frame allocation are all
unchanged** — 7.0 KB/frame at 24 bodies, one draw call, zero allocation.

## Alternatives considered

**A skirt: extrude a quad strip outward from the boundary ring.** The backend
engineer's suggested construction, and the textbook one. Rejected on bandwidth.
It adds `4 * (lattice - 1)` vertices per body — 16 on top of 25 at lattice 5, a
64% vertex increase, taking the upload from 7.0 KB/frame to roughly 11.5
KB/frame — and needs a second index layout, against a standing constraint to
keep the one-draw-call structure and the bandwidth roughly where it is. The
displacement achieves the same silhouette for zero extra bytes.

What the skirt would have bought is exactness in the *shading*: it leaves the
interior mesh untouched, so every render cell keeps its one-to-one
correspondence with a solver cell. Displacing the boundary ring instead stretches
each body's outermost ring of cells by a radius in the outward direction — a 50%
stretch of that one ring at lattice 5 — so `vCompression` is spread over slightly
more area there than the solver's cell covers. For a smooth darkening gradient at
this size that is not visible, and it is the deliberate price of the bandwidth.
If Stage 3's richer shading (`vEdge`, `vContact`, subsurface depth) makes the
outer ring's texture stretch legible, the skirt is the fallback and this ADR
should be revisited.

**Shrinking `particleRadius` until the drawn shapes meet.** Rejected, and nailed
shut on purpose: `ContactGapTest` now fails loudly for it. It would make the
renderer look right by making the physics wrong — the radius is the contact
distance, the broadphase cell size and the placement guard, and shrinking it
changes stability, packing and coverage.

## Consequences

**`vEdge` needs the architect's eye before Stage 3.** ADR 0007 defines it as
"free surface, 0 interior → 1 boundary". After extrusion the boundary ring *is*
the free surface, so the definition still holds and needs no change today. It
would not have held for the skirt construction, where the free surface is the
skirt rather than the ring — worth recording, since it is a reason the two
options are not interchangeable later.

**A piece's true material size still varies 11% across quality tiers.** The
backend engineer's second finding — `pieceExtent = PIECE_WIDTH + 2 *
particleRadius`, so a piece is 2.40 wide at lattice 4 and 2.16 at lattice 6, and
the performance dial therefore changes how the well packs. This ADR makes that
variation *visible* rather than causing it: the drawn size now matches the
physical size at every tier, so the tiers no longer look alike while behaving
differently. Out of scope here; it is with the architect.
