# 0040 — Frontend Engineer → Product Lead

**Branch:** `feat/candy-final-look` · **PR:** #34 (base `feat/candy-seamless-mesh`)
· **Commits:** `8e32665` (material), `5213336` (seam `:app` half). CI green,
mergeable. Consolidates the render + seam + material tracks onto one branch, as
dispatched.

## What I did

Two tracks, one branch, so the client judges one clean result.

### A. Smooth glossy candy material (visual-direction §14.1–14.3, PR #33's correction)
Fixed all three code-level defects the UX Designer diagnosed against the shipped
frames:
- **Grain** — `GRAIN_FREQUENCY` 7.0→2.5, `GRAIN_GAIN` 0.06→0.02. The two-sine
  mottle no longer aliases into the cross-hatch/waffle that made every piece read
  as rough rubber. Candy is smooth.
- **Subsurface** — flipped to darken toward the true silhouette EDGE (free
  `1-depth` square), not the centre; gain 0.80→0.40. Flat/bright body, plump
  richer rim — not a dark-centred tube.
- **Specular** — the streak now tapers ALONG its length (`uSpecularLength`) and
  carries a tight wet hotspot (`uSpecularHotspotRadius`/`Gain`) on the same
  distance fields, so the hard full-length diagonal scratch becomes one soft wet
  gleam. Three new uniforms wired through `GameRenderer`.

Palette, rounded corners, soft shadow and light background untouched (client
confirmed those good).

### B. Seamless tetromino mesh — the `:app` half (ADR 0018)
- `BodyMesh` assembles the IBO from `SimState.bodyTriangleIndices` (cells'
  triangles + seam bridges), offset by `b*particlesPerBody`, on body-set change
  (`GL_DYNAMIC_DRAW`); still one draw call. Index count is the jagged running
  total; IBO store re-allocated on context loss.
- `VertexFill.extrudeBoundary` now gates on `SimState.particleFreeEdges`: extrudes
  only TRUE outer silhouette (a radius, `radius*√2` at a corner), leaves
  seam-facing sides at their centres for the bridge mesh — the old per-cell ring
  extrusion collapsed the seam columns into the "+".
- Extracted the pure concatenation into `BodyIndexAssembly` (JVM-testable, same
  pattern as `VertexFill`); retired `LatticeTopology` +
  `TopologyMatchesSolverTest`/`LatticeTopologyTest` (the shell no longer derives
  topology, so the "two definitions" they guarded is gone — the core's
  `SeamlessTopologyTest` pins it now).

### Contract review, as the consumer
Reviewed `bodyTriangleIndices` / `particleFreeEdges` (contracts.md §3) as an
artifact before leaning on them — the bit layout, the "assemble on body-set
change" shape and the body-local index range all fit `:app` as written; no change
asked of the Backend Engineer's PR #32.

## Verification
- `make test` green (JVM + `buildSrc:check` + lint). PR #34 CI green.
- `make playthrough` on the software-GL AVD. Money-shots in
  `.team/reviews/0040-candy-final-look/` (with a README that states the
  correctness-only caveat). The **L reads as one continuous glossy candy** with
  rounded outer corners and a sharp elbow; the S, Z, T, J likewise; the material
  is smooth with a wet gleam and no waffle.

## The one thing NOT done, and it is a CORE defect I cannot fix from `:app`

**The O's dead centre is a hole** (`03-O-cyan-CENTER-HOLE-core-defect.png`). The O
is the only shape where four cells meet at a POINT (a 2×2 block). The core's seam
model is strictly edge-to-edge, so its four pairwise edge-bridges each clip one
corner of the central 2r×2r square and leave the middle open — the background
shows straight through. Measured on `bodyTriangleIndices[1]` (lattice 5): inner
corners `{24,45,54,75}`; triangles filling the junction (≥3 of the four) = **0**.
The O already uses all four `MAX_SEAMS` slots on real edge-seams, so there is no
room for a junction in the current model — it needs a new constraint category.

My correct extrusion EXPOSED this; the old per-cell extrusion was masking it by
collapsing the four inner corners over the centre.

**This must be the Backend Engineer's fix, not mine:** `SeamlessTopologyTest`
requires every render triangle to be a solver area constraint, so a centre quad
needs two matching area constraints (plus shear/distance so the centre doesn't
tear). Adding render-only triangles in `:app` would re-derive topology (which ADR
0018 removed) and paper over a contract the producer under-delivers — the contract
says `bodyTriangleIndices` welds cells into "ONE continuous mesh"; for the O it
does not. Per our standing rule I raised it rather than hacking the client.

**Ask:** please dispatch the Backend Engineer to add the O centre-junction to
`bodyTriangleIndices` on `feat/candy-seamless-mesh` (I tried to message them
directly — no active agent under that name is reachable, so it comes to you). I
wrote them the exact defect + proposed fix in the same terms as above. Once it
lands, `:app` needs ZERO changes (I assemble whatever the core publishes and the
capacity sizing already keys off the longest per-archetype entry); I will re-shoot
the O and confirm the hole is gone before the client sees it.

## What I deliberately did not do
- Did not re-tune `SPECULAR_GAIN` (0.85), the palette, the rim colour or the
  shadow/background — none were named wrong and the client confirmed the world.
- Did not touch the core seam topology or the solver — that is the Backend
  Engineer's module and is coupled to the area-constraint invariant.
- Did not add the O centre junction as an `:app`-side render hack — see above.

## Considered and rejected
- **An `:app`-side junction fill for the O** (two triangles over `{24,45,54,75}`
  in the IBO). It would fill the hole and the particles already deform correctly,
  but it re-introduces shape-specific topology in the shell (the exact thing ADR
  0018 removed), hardcodes O=archetype-1, and papers over a producer-side contract
  gap. Rejected in favour of raising it to the owner.
- **A dedicated `uSpecularHotspotGain` uniform vs a fixed ratio of
  `uSpecularGain`** (§14.3 left this to me). Took the uniform: the hotspot-vs-
  streak balance is a look-call the client steers on-device, and this file's whole
  discipline is that such numbers are named constants, not literals.

## Frame budget (for on-device measurement)
Backend measured the bridges add ~25–33% triangles/body (~2160→~2880 on a full
well) — trivial for GLES3. This round stacks 4× MSAA + the §18 shadow pass + the
§19 background + the new bridged topology; that stack is a real on-device budget
item (Fairphone 6 at 15.0 ms mean vs 16.67 ms with a near-flat shader). Only the
phone can price it — MSAA is the first dial to drop (4→2→0) if it overruns. The
material additions this round (one more dot, two smoothsteps, a couple of
multiplies over round 3's specular) are the same order as the rim term — cheap.

## Uneasy about
- The O hole above is the headline. It is the client's named acid test, so the
  branch is **not client-ready until the core junction lands**, even though my two
  tracks are complete and green.
- The IBO body-set-change gate (`bodyCount != uploadedBodies`) is a proxy for
  "the archetype sequence changed" — sound today because every set change also
  changes the count within a tick, but if a future change ever mutated the body
  set at constant count between two frames, both colours and topology would go
  stale together. Commented at the gate; carried from the Backend Engineer's own
  unease.

*Handoff from the **Frontend Engineer**.*
