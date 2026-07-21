# Backend Engineer → Frontend Engineer: candy material data path (§15/§16)

**Branch:** `feat/candy-material-datapath` · **PR:** #30 · **Commits:** `9664537` (code+tests), `d98b755` (contract doc)

The core-sim half of the glossy-candy redirect. You now have the data to make a
tetromino read as one connected piece and to round its true corners. Nothing
here touches shaders or GL — that is yours; I only provide the fields.

## What changed in the contract (`SimState`, all additive per §5)

### 1. `particleU` / `particleV` — now body-WIDE, was cell-local

Old code set `particleU = col / edgeSpan` per cell, so every cell ran 0..1 and
the grain/subsurface/specular tiled per cell (the "four squares" read).

Now: **body-wide, aspect-preserving.** Both axes divide by the piece's *longer*
footprint side (in particle units), so:
- `0..1` on the longer side, `0..k` (k ≤ 1) on the shorter side,
- isotropic (same scale both axes — the grain is not stretched),
- continuous across cell seams (steps by one grid unit, never resets 1→0).

Two consequences, both deliberate:
- `min(vBodyUv, 1-vBodyUv)` still gives silhouette→core depth, but on a piece
  thinner than it is long (an I is 4×1) the short axis never reaches centre, so
  a thin piece reads more translucent. Correct for a thin jelly; retune
  `uSubsurfaceGain` as §14 already says.
- The grain frequency now depends on footprint — corrected by field 2.

Range is still within `0..1`, so your `VertexFillTest` bound assertions still
hold. **But that test's doc comment ("UV is per-cell (0..1 within a cell)") is
now stale** — the assertions pass, the prose lies. Worth updating the comment
and, if you want, strengthening it to assert body-continuity. Your call, your
test; flagging it rather than editing it.

### 2. `grainScaleCompensation: FloatArray` — new, per-archetype, static

Length `Simulation.ARCHETYPE_COUNT` (7), indexed by `bodyArchetype`. Fold it
into your per-archetype grain scale at upload:

```
uGrainScale[a] = paletteGrainScale[a] * grainScaleCompensation[a]
```

This cancels the footprint's effect on grain frequency, so every piece regains
the SAME per-cell grain frequency it had before body-wide UV — now continuous
across the whole piece, no per-cell seam. It does **not** disturb the palette's
own per-archetype grain (the CVD/monochrome identity cue); it only removes the
footprint term. Upload once with the palette; it never changes per frame.

Your shader's grain line stays as-is (`mottle(vBodyUv * uGrainFrequency *
uGrainScale[vArchetype])`) — the compensation rides inside `uGrainScale`, no
shader edit needed for the grain.

### 3. `particleCorner: FloatArray` — new, 0/1 per particle, static

`1` at a particle that is a true convex outer-silhouette corner of the WHOLE
piece; `0` everywhere else, internal cell corners and every seam included.
Computed once at spawn from `PieceShapes.neighbour()`. It is a **subset of
`particleEdge`** — never corner 1 while edge 0.

- **Attribute slot:** add a new float vertex attribute (call it `aCorner`), fill
  it in `VertexFill` from `state.particleCorner[i]` exactly the way `aEdge` is
  filled, and add the matching `in/out float vCorner` to both shader stages.
  Interpolate it like `vEdge`; it ramps 1→0 over one lattice spacing on its own,
  so §16's rounding needs no new geometry — shape the fade with `pow(vCorner, N)`
  (a multiply at mediump) to pull it in to "slightly rounded, not cube."
- **Semantics check:** an L rounds its five real corners and stays sharp at its
  inner elbow; an O has exactly four; I/O = 4, J/L = 5, T/S/Z = 6 corners.

## `WellFrame.kt` needs a `vCorner` generic of 0 — CONFIRMED by reading it

The UX handoff flagged this; I checked `WellFrame.kt` myself rather than relay
it. `WellFrame.draw()` (lines 176-184) sets four generic disabled attributes —
`ATTRIB_COMPRESSION=1`, `ATTRIB_CONTACT=0`, `ATTRIB_BODY_UV=(0,0)`,
`ATTRIB_EDGE=0` — and the doc-comment table (lines 161-165) documents each. There
is **no** corner attribute. Once you enable an `aCorner` array for bodies, the
frame reads the generic value; add `GLES30.glVertexAttrib1f(ATTRIB_CORNER, 0f)`
and a table row ("corner | 0 | the walls have no silhouette corner and would
round"). Value must be **0** — a wall has no corner to round.

## `particleEdge` / `particleContact` are UNAFFECTED

I did not touch either. Edge still marks the whole free surface; contact still
the darkening seam. The rim light and AO seam behave exactly as before.

## Known limit I want you to see on-device (not a bug)

At a diagonal "step" between two cells (the S/Z pinch), neither cell owns that
convex point with both sides free, so it is not flagged as a corner — faithful
to §16's per-cell definition, which defines corners cell-by-cell. So S and Z
round 6 of their outline corners, not the 2 pinch points. Documented in the
contract and pinned by the corner-count test. If the S/Z pinch looks wrong
rounded-or-not on the real device, that's a look-call for you + UX, not a data
defect — tell me and we can revisit the definition.

## How to run

- Core-sim tests: `./gradlew :core-sim:test` (all green, incl. the new
  `TetrominoShapeTest` cases: UV seam continuity, grain compensation, corner
  counts, O extremes, L elbow).
- App tests need the SDK: `ANDROID_HOME=/state/android-sdk ./gradlew
  :app:testDebugUnitTest` (green with my contract change; no fake `SimState` to
  update — `SquishToy` uses the real `simulation.state`).

## Considered and rejected

- **Per-axis 0..1 UV** (each axis independently normalised). Rejected: it
  stretches the grain 4:1 on the I piece (uv.x covers 4 cells, uv.y covers 1),
  and a single scalar `uGrainScale` can't correct anisotropy. Aspect-preserving
  keeps one scalar sufficient — which is what §15 asked for ("fold a factor into
  uGrainScale") — at the cost of the short-axis-not-reaching-centre subsurface
  behaviour above, which is the correct read anyway.
- **Exposing footprint dims as a vec2** so you could fully anisotropically
  correct grain. Rejected as over-scope: aspect-preserving UV already makes the
  grain isotropic, so a scalar suffices; a vec2 would be an unused degree of
  freedom.
- **A separate corner-only "sub-body-aware" pass.** Unnecessary —
  `PieceShapes.neighbour()` already answers the adjacency, so the corner flag is
  computed in the same shape-precompute loop as `shapeEdge`, zero extra alloc.

## Uneasy about

- The **subsurface-on-thin-pieces** behaviour (I piece reads translucent, O
  reads deep-cored) is a real per-piece variation you inherit. I believe it's
  the right jelly read and §14 wants more translucency anyway, but you'll see it
  first on-device. If it reads as inconsistent rather than physical, the lever
  is `uSubsurfaceGain`, not the UV — tell me if you think the UV normalisation
  should change instead.
- The **S/Z pinch corners** above.

---
*Handoff from the **Backend Engineer**.*
