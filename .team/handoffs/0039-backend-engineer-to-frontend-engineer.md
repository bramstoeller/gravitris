# Backend Engineer → Frontend Engineer: seamless mesh — the `:app` half

**Branch:** `feat/candy-seamless-mesh` (off `feat/candy-material-render`) ·
**PR:** #32 (base `feat/candy-material-render`) · **Commits:** `9803d35`
(core-sim + test), `30a2cfa` (ADR/contract). **ADR 0018.**

Your handoff 0038 was right, and I verified it against source before building:
render mesh per-cell (`SoftBodyWorld.triangleIndices` is one L×L pattern;
`LatticeTopology`/`BodyMesh` tile it per cell at `k·L²`, no seam bridges), UV
already body-wide continuous, `particleEdge`/`particleContact` correctly 0 across
seams. I took your recommended path (b) = ADR 0015's recorded **B1**.

## What I built (core is done, green, additive)

Two new `SimState` fields (additive per contracts.md §5 — nothing removed):

### 1. `bodyTriangleIndices: Array<IntArray>`
Per-archetype (indexed by `bodyArchetype`, length `Simulation.ARCHETYPE_COUNT`).
Each entry is the **whole piece's** body-local triangle indices — the four
cells' own triangles (same `p00,p10,p11 / p00,p11,p01` split as
`triangleIndices`) **plus** the seam-bridge triangles. Values in
`[0, particlesPerBody)`. **Length varies by shape** (O = 4 seams, the other six
= 3), so it is a jagged array, not a fixed stride.

The bridges are the solver's seam area constraints verbatim, so every render
triangle is one area constraint → `particleCompression` (vCompression) is now
continuous across a seam too, not only `vBodyUv`. You get that for free.

### 2. `particleFreeEdges: IntArray`
Per-particle silhouette-direction bitmask. **`LEFT=1, RIGHT=2, DOWN=4, UP=8`** in
the particle's own lattice (row 0 at the bottom, so `DOWN` = -y). A seam-facing
side is clear. Invariant: `particleEdge[i] == 1f ⇔ particleFreeEdges[i] != 0`.
`LEFT`/`RIGHT` are never both set, nor `DOWN`/`UP`. This is the value that makes
the "extrude only true silhouette" constraint livable — you previously had only
the OR'd `particleEdge`.

Nothing in the vertex path changed. Bridges reuse existing particles — no new
vertices, positions/UV/compression/contact/corner/edge/archetype all untouched.

## Your half — precise steps

### A. `BodyMesh` — assemble the index buffer per body-set change
Today `uploadIndices()` builds `LatticeTopology.buildIndices(maxCells, lattice)`
once in `create()` and tiles one cell pattern. Replace with per-archetype
assembly, on the **same schedule you already rebuild the archetype/UV statics**
(`if (state.bodyCount != uploadedBodies)` in `upload()` → `uploadStatics`):

- For each body `b in 0 until state.bodyCount`, append
  `state.bodyTriangleIndices[state.bodyArchetype[b]]`, each value **offset by
  `b * particlesPerBody`** (`particlesPerBody = state.particlesPerBody`; NOT per
  cell — the array already spans all four cells), into a `ShortArray` scratch,
  and upload it to the IBO (`GL_DYNAMIC_DRAW` now, not `GL_STATIC_DRAW`).
- `indexCount` = sum of the appended lengths (jagged — compute during assembly,
  don't derive from a per-cell formula).
- IBO capacity to allocate once in `create()`:
  `maxBodies * maxLenPerBody` shorts, where
  `maxBodies = state.particleCapacity / state.particlesPerBody` and
  `maxLenPerBody = max over a of state.bodyTriangleIndices[a].size`. You don't
  have `state` in `onSurfaceCreated`, so either size the IBO lazily on first
  `upload()`, or (cleaner) allocate it in the first `uploadStatics`. Indices stay
  `GL_UNSIGNED_SHORT` — bridges reference the same vertex range, and the existing
  `maxParticles <= 65536` assertion still covers it.
- `draw()` is unchanged (draws `indexCount` from the IBO). Still one draw call.

Note on the gate: the per-archetype IBO depends on the archetype *sequence*,
exactly like the archetype attribute buffer you already rebuild on that gate, so
keying them together is correct. Keep calling `invalidateArchetypes()` when you
replace the simulation (it already resets `uploadedBodies = -1`).

### B. `VertexFill.extrudeBoundary` — gate on `particleFreeEdges`
Today it iterates per cell and derives `rowStep`/`columnStep` from the
particle's position on a cell edge, extruding **every** cell's outer ring (which
collapses the seam columns coincident — the "+"). Change the step derivation to
read the mask (pass `state.particleFreeEdges` into `extrudeBoundary`; `fill()`
already has `state`):

```
val m = freeEdges[i]                              // i = absolute particle index
val columnStep = if (m and LEFT  != 0) +1 else if (m and RIGHT != 0) -1 else 0
val rowStep    = if (m and DOWN  != 0) +1 else if (m and UP    != 0) -1 else 0
if (rowStep == 0 && columnStep == 0) continue     // interior OR seam-facing: leave it
```

Everything else stays: reference is one step inward along the free axis/axes
within the same cell (`(row+rowStep, col+columnStep)` off the cell base — always
strictly interior for L≥3), `reach = radius*√2` only when both steps are nonzero
else `radius`, single in-place pass. A seam-facing edge now has its bit clear →
`step == 0` → not extruded → it stays at its centre `2*radius` from the facing
cell, and `bodyTriangleIndices`' bridge quad fills that `2*radius` with real,
interpolated geometry. No collapse, no gap, no step. Bit constants: mirror
`LEFT=1/RIGHT=2/DOWN=4/UP=8` from the contract (they are a frozen wire format;
`:app` can't see the internal core enum).

### C. Tests
- `RenderFootprintTest` — rewrite for the free-edge-gated extrusion: the whole
  piece's true outline is extruded by `radius`; seam-facing edges are not, and
  sit `2*radius` apart (filled by the bridge). Assert against `particleFreeEdges`.
- `VertexFillTest` — `extrudeBoundary` signature/behaviour change.
- `LatticeTopology` / `LatticeTopologyTest` / `TopologyMatchesSolverTest` — these
  exist because `:app` derived topology from the lattice alone (no `Simulation`
  at `onSurfaceCreated`). With per-archetype topology consumed straight from
  `SimState`, `:app` no longer derives anything, so the "two definitions" risk
  those guard is gone. Either delete them, or replace `TopologyMatchesSolverTest`
  with one asserting your assembled IBO for a known body set equals the
  concatenation of `bodyTriangleIndices[archetype[b]]` offset by
  `b*particlesPerBody`. Your call — you own the mesh.
- The real proof is visual: re-render the **O and L** and screenshot; confirm the
  "+" is gone and the piece reads as one continuous glossy candy. I can't do that
  here — no GPU in this container (the whole reason `LatticeTopology` was
  JVM-testable).

## What I deliberately did NOT do
- Did not touch `:app` — that's your half, and keeping my PR core-only makes it
  additive and independently mergeable (everything stays green; the "+" is not
  fixed until you switch `:app` to the new fields).
- Did not remove `triangleIndices`. The solver still defines its cell area
  constraints on those two triangles and `TopologyMatchesSolverTest` pins them.
  Once `:app` stops reading it, its removal is a breaking change the Architect
  signs — a follow-up, not this PR.
- Did not add rounded seam/corner geometry — §16 `particleCorner` already handles
  the silhouette corners in-shader; out of scope here.

## Considered and rejected
- **A separate `seamIndices` array, keep tiling the per-cell pattern** (literally
  option (a) of your 0038). Same triangles, but it forces two index ranges / two
  draws and splits one per-archetype concept in two. Since bridges are
  archetype-dependent the IBO must be assembled per body-set change anyway, so
  folding interior+bridges into one per-archetype array is no more invasive.
  Full reasoning in ADR 0018 "Alternatives".

## Frame budget
Bridges add ~25–33% triangles per body (lattice 4: +18 on 72 interior; O +24).
~2160 → ~2880 triangles on a full well — trivial for GLES3, and the seam strips
are thin so fragment cost is negligible. Extra index bytes are paid on body-set
change, not per frame. No runtime change until you switch. Please re-check on the
Fairphone as part of your half and note the number.

## Two review asks (record-carried, since neither of you was live)
1. **You, as the contract's consumer**, review `bodyTriangleIndices` /
   `particleFreeEdges` in `docs/contracts.md` §3 as an artifact before `:app`
   leans on them — the team rule. If the bit layout or the "IBO assembled on
   body-change" shape doesn't fit `:app`, tell me on PR #32 and I'll adjust; the
   core side is cheap to change while it's still additive.
2. **Architect** should confirm the **ADR 0007 §2** amendment (IBO per-archetype,
   reassembled on body-set change). Flagged in ADR 0018 and here.

## Uneasy about
- The body-set-change gate (`bodyCount != uploadedBodies`) is a proxy for "the
  archetype sequence changed". It holds today because every set change also
  changes the count within a tick, and your archetype attribute buffer already
  relies on it — but the per-archetype IBO now relies on it too, so if a future
  change ever mutates the body set at constant count between two rendered frames,
  both the colours AND the topology would go stale together. Worth a comment at
  that gate when you wire it.

---
*Handoff from the **Backend Engineer**.*
