# 0012 — Frontend Engineer to Product Lead

Date: 2026-07-20
Branch: `fix/render-footprint` (off `feat/squish-toy`, with `fix/contact-gap` merged in), pushed
Commits: `d98b333`, `8897b03`

## What I did

Fixed the gap the client reported. It was a rendering inset, exactly as the
backend engineer measured: every position in the contract is a particle
*centre*, the solver treats material as reaching `particleRadius` past it, and
`VertexFill` copied centres verbatim into the vertex buffer. Every body was
drawn one radius small on every side, so two bodies the solver was holding in
contact showed `2 * particleRadius` of background between them.

`VertexFill.fill` now extrudes each body's outer ring of vertices outward by
`SimState.particleRadius` after the interpolation lerp, in place. The outward
direction is measured from each boundary particle to its inward reference
neighbour **on the deformed lattice**, so it follows the material as it squashes
and shears. Not a scale about the centroid — as the backend flagged, that adds a
proportion rather than a distance and is wrong for exactly the deformed bodies
this product is about.

Corners are displaced by `radius * sqrt(2)` along the diagonal so the two offset
edges meet squarely, which makes an undeformed piece draw at exactly the
solver's own `pieceExtent = PIECE_WIDTH + 2 * particleRadius`. That exactness is
a test.

## Result — measured

Minimum distance between the two closest bodies' **drawn outlines** in a settled
four-piece pile, measured outline-to-outline:

| lattice | before | after | particle diameter |
| ------- | ------ | ----- | ----------------- |
| 4 | 0.514 | 0.0064 | 0.600 |
| 5 | 0.384 | 0.00009 | 0.450 |
| 6 | 0.299 | 0.0055 | 0.360 |

Both numbers are measured by the same test on the same pile, so the improvement
is demonstrated rather than asserted.

## Frame cost — measured, as asked

Cost of the extrusion alone, host JVM, against ADR 0009's 12.06x derating:

| scene | particles | host | on device | % of a 16.67ms frame |
| ----- | --------- | ---- | --------- | -------------------- |
| 24 bodies, lattice 5 (the toy on device) | 600 | 672 ns | 8.1 us | **0.049%** |
| 40 bodies, lattice 5 (toy cap) | 1 000 | 1 133 ns | 13.7 us | 0.082% |
| 60 bodies, lattice 4 (ADR 0001 reference) | 960 | 1 279 ns | 15.4 us | 0.093% |

Under 0.1% of a frame everywhere, against a solver taking 32.2%. It is one
square root per boundary vertex. **This will not move the 59.4 fps / 29 jank-per-
second numbers in either direction** — if those improve or worsen on the next
device run, it is not this change.

**Bandwidth, vertex count, draw calls and per-frame allocation are all
unchanged**: 7.0 KB/frame at 24 bodies, one draw call, zero allocation. The
benchmark was a throwaway and is not in the tree.

## What the client should look for — one photo settles it

**Drop four or five pieces, let the pile settle, photograph the pile.**

Three things, in order of how obvious they are:

1. **Each piece is visibly bigger.** At the shipped quality tier a piece goes
   from 1.8 to 2.25 world units wide — **25% wider on screen**. This is the
   easiest thing to see and it is expected, not a bug: we were drawing 64% of
   the material's real area and are now drawing 100% of it.
2. **The blocks touch.** Where two pieces meet there should be a *line*, not a
   band of background. Before, there was a visible strip of background between
   every pair of blocks.
3. **The pile sits on the floor and against the walls**, rather than hovering a
   hair above and inside them.

If the pile still reads as separated tiles after this, the diagnosis was wrong
and it comes back to me. If it now reads as material in contact but too *soft*
or too *bouncy*, that is a different conversation (see below).

## What I deliberately did not do

- **Did not touch `particleRadius`.** `ContactGapTest` nails that door shut on
  purpose and I did not go looking for it.
- **Did not build the skirt** the backend engineer suggested. See below.
- **Did not touch `WellFrame`.** The solver clamps centres to one radius inside
  the well, so extruded bodies meet the walls exactly. There is a test for it.
- **Did not touch the shaders, the haptics, or the softness that just landed.**
  The full `check` suite is green, so the 7x-more-deformation and the haptics
  work are intact.

## What I considered and rejected

**A skirt — a quad strip extruded outward from the boundary ring.** The
backend's suggested construction and the textbook one. Rejected on **bandwidth**:
`4 * (lattice - 1)` extra vertices per body is 16 on top of 25 at lattice 5, a
64% vertex increase, taking the upload from 7.0 to roughly 11.5 KB/frame, plus a
second index layout. The standing constraint was to keep the one-draw-call
structure and the bandwidth roughly where it is. The displacement gets the same
silhouette for zero extra bytes.

**This trade is not free and I want it on the record.** The skirt leaves the
interior mesh untouched, so every render cell keeps its one-to-one match with a
solver cell. Displacing the ring instead stretches each body's outermost ring of
cells by a radius outward — a 50% stretch of that one ring at lattice 5 — so
`vCompression` is spread over slightly more area there than the solver's cell
covers. At this size, for a smooth darkening gradient, I do not believe it is
visible. **If Stage 3's richer shading makes the outer ring's texture stretch
legible, the skirt is the fallback** and ADR 0011 says so.

**Rounding the corners to the true disk arc.** The real surface of a union of
disks curves at a corner; squaring it overshoots by `radius * (sqrt(2) - 1)`,
about 0.09 world units, at four points per body. Rounding needs vertices the
fixed topology does not have, and blocks with sharp corners is what the piece is
supposed to look like anyway.

## Tests

- **`RenderFootprintTest`** (new, `:app`) — the client-facing guard. Settled
  bodies drawn in contact at all three tiers; a resting body drawn on the floor;
  a fresh piece drawn at exactly `pieceExtent`; **extrusion is a fixed distance,
  not a proportion** (this is the one that fails a centroid scale, on a
  deliberately deformed pile); interior vertices untouched; nothing drawn
  through the walls; no NaN from a collapsed cell.
- **`VertexFillTest`** — the three alpha tests now read the lerp on *interior*
  particles, since boundary vertices deliberately no longer equal the position
  they were interpolated from. The extrusion is applied uniformly from the
  already-interpolated buffer, so an alpha mistake still shows up there
  identically. The reversed-lerp catch is intact.
- `make test` (`./gradlew check`) green, debug APK builds.

## Documentation

**ADR 0011** records where the silhouette is, why it is a fixed distance and not
a centroid scale, the measured gap and frame cost, and why the skirt lost. ADR
0007 carries an amendment banner pointing at it — that ADR specified the shading
inputs in detail but never said where the outline was, which is how this
shipped. Indexed in `.team/decisions.md`.

## Open questions / what I am uneasy about

1. **I cannot see the result.** No GPU, no emulator. Everything above is
   measured in world units on the JVM. The geometry is verified; that it *looks*
   right is not. The photo above is the check.
2. **The 25% size increase changes how the well packs.** Pieces are now drawn at
   their true size, which is bigger — fewer will visually fit per row than the
   client saw last build. This is correct, but if the well now feels cramped
   that is a well-geometry conversation, not a bug in this change.
3. **`vEdge` for Stage 3.** ADR 0007 defines it as "free surface, 0 interior → 1
   boundary". After this extrusion the boundary ring *is* the free surface, so
   the definition still holds and needs no change today. It would **not** have
   held under the skirt construction — worth the architect knowing, since it
   means the two options are not freely interchangeable later.
4. **Not mine, flagged as instructed and not acted on:** the "a bit odd" bounce
   is manufactured in `deriveVelocities` with no restitution coefficient (ADR
   0003 conversation), and a piece's true material size varying 11% across
   quality tiers is with the architect. This change makes that 11% *visible*
   rather than causing it — the tiers previously looked alike while behaving
   differently.
