# Frontend Engineer → Backend Engineer: the internal cell seam is a mesh-topology defect

**Context:** glossy-candy render, PR #31. The client's loudest complaint —
multi-cell pieces still "read as four squares" — survives on the O as a dark "+"
splitting it into four cells. I traced it to source (measured, not recalled) and
it is a mesh-topology issue on the boundary between our modules, not shading.
Flagging straight to you per the team norm; I tried `SendMessage` first but no
backend agent was live.

## What it is NOT (ruled out by reading the code)

- **Rim / edge darkening.** `SoftBodyWorld.kt:252` sets `shapeEdge` to 0 on
  seam-facing edges (`freeLeft/Right/Down/Up` are false there). The rim reads
  `vEdge`, so it does not draw internal seams. Correct.
- **Contact / AO darkening.** `XpbdSolver.kt:449` — `if (world.particleBody[j]
  == bodyI) continue` — skips same-body pairs, so `particleContact` is 0 on
  internal seams. Correct.
- **Subsurface.** `min(uv, 1-uv)` is symmetric about 0.5, so it does not step
  across a seam at the piece centre. Not the cause.

## What it IS

The render mesh is **per-cell** (ADR 0007/0015: each cell is its own `lattice²`
index block; there are no triangles bridging two cells). Your body layout
(`SoftBodyWorld.kt:237-244`) places adjacent cells one `spacing` apart on a
continuous grid with continuous UV — so *within* a bridged region the UV would
interpolate seamlessly. But because the two cells are separate meshes with no
bridging quad, their facing edge columns (gcol `L-1` uv `a`, gcol `L` uv `a +
1/maxSpan`) never get interpolated across — the shading (grain, specular)
**steps** by one grid unit right at the seam. That step is the visible "+".

My `VertexFill.extrudeBoundary` makes it worse but is not the root: written for
single-cell Milestone-1 bodies (`particlesPerBody = lattice*lattice`), it
extrudes *every* cell's outer ring, so it pushes both facing seam columns to the
seam midpoint and collapses them coincident. That fills the 2r gap (good) but
puts the uv discontinuity right at the join.

Body-wide UV (your §15 work) fixed *within-cell* continuity beautifully — grain
and highlight now sweep the whole piece. The seam is the one thing UV alone
can't fix, because it's a missing-geometry problem.

## What I need from you (the fix is yours)

Either:
- **(a) Bridge adjacent cells with seam quads** in the index/topology so UV
  interpolates continuously across the seam; **and** expose per-particle,
  per-direction free-edge info (right now `:app` only gets the OR'd
  `particleEdge`) so my `extrudeBoundary` can push ONLY true silhouette edges and
  leave the seams to the bridge; **or**
- **(b) Build a tetromino as one continuous footprint lattice mesh** instead of
  four separate cell blocks — then seams are interior lines with full bridging
  and the problem disappears entirely.

Both are ADR-0015 / topology territory. `LatticeTopology` + `BodyMesh`'s single
static index pattern would need to become per-archetype (or bridged), which is
why I'm not doing it solo under demo pressure — it's a contract change I'd want
to design with you and the Architect, with tests. Happy to own the `:app` half
(the extrusion change + the bridged index upload) once the core exposes cell
adjacency / per-direction edges. Let's pair on the contract.

## What I did NOT do

- I did not touch the extrusion's seam-collapse in this pass — removing it
  without a bridge would open a 2r gap (worse). Left as-is until the bridge
  exists.
- Everything else in PR #31 (specular, rounded corners via alpha-to-coverage,
  soft shadow, light world) is shading/render-only and does not depend on this.

---
*Handoff from the **Frontend Engineer**.*
