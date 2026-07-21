# Candy final look — money-shots (feat/candy-final-look, PR #34)

Captured from `make playthrough` on the correctness AVD (software GL,
ANGLE/SwiftShader via `-gpu swangle` — hardware GL is unavailable in this
container). **Correctness only: these say the mechanic RUNS and RENDERS and let
us judge the SHAPE of the material and the seams. They are NOT a look or
performance claim on the client's real device** — colour, gloss falloff and
frame time can only be judged on the Fairphone (docs/operations.md, the brief).
The pieces dealt are whatever the core's dealer gave; there is no debug hook to
force a specific archetype, so these are the O/L/S/J the session happened to
produce.

## What landed (both tracks, one branch)

**A. Material (visual-direction §14.1–14.3) — the client's "die blokken zijn zo
lelijk" fix.** Grain frequency/gain cut so the cross-hatch/waffle is gone;
subsurface flipped to darken the true rim (plump body, not a dark-centred tube);
specular reshaped from a hard full-length diagonal into one soft wet gleam
(along-axis taper + a tight hotspot). Palette, rounded corners, soft shadow and
light background untouched.

**B. Seam `:app` half (ADR 0018).** Extrusion gates on `particleFreeEdges` (true
silhouette only, seams left for the bridge mesh); the IBO assembles from the
core's per-archetype `bodyTriangleIndices`. The internal "+" is gone on every
shape that has only edge-seams.

## The frames

| file | shows |
| ---- | ----- |
| `01-L-green-rounded-outer-sharp-elbow-seamless.png` | Emerald **L**. Rounded OUTER corners, SHARP inner elbow, ONE continuous candy — no internal "+". The L acid-test: passes. |
| `02-J-rose-glossy-wet-gleam-seamless.png` | Rose **J** mid-fall + purple **S** settled. Smooth glossy body, one soft wet gleam, no waffle grain, seamless across cells. This is the material read the client asked for. |
| `03-O-cyan-CENTER-HOLE-core-defect.png` | Cyan **O**. Outer body reads continuous (pairwise seams bridged) BUT the dead-centre shows a white square HOLE — see below. |
| `04-S-purple-smooth-material-no-waffle.png` | Purple **S** at spawn (undeformed). Smooth flat-saturated body, no surface texture — grain fix confirmed. |

## Honest status against the bar

- Smooth glossy candy, no grainy cross-hatch/waffle — **yes** (all frames).
- Plump/voluminous, brighter body + richer rim — **yes**, best seen on the rose J.
- One soft wet gleam, not a hard scratch — **yes**.
- No internal "+", one continuous candy — **yes for I/T/S/Z/J/L**, **NO for the O**
  (see below).
- Rounded outer / sharp elbow / soft shadow / light world kept — **yes**.

## The one remaining miss: the O's centre is a hole (CORE defect, not `:app`)

The O is the only shape where four cells meet at a POINT (a 2×2 block). The core's
seam model is strictly edge-to-edge (`SoftBodyWorld`: "a seam bridges two facing
L-particle edges"), so its four pairwise edge-bridges each clip one corner of the
central 2r×2r square and leave the middle open. Measured on
`bodyTriangleIndices[1]` (O, lattice 5): the four inner-corner particles are
`{24,45,54,75}`; triangles touching ≥2 of them = 4 (one per edge-bridge);
triangles that actually FILL the junction (≥3 of the four) = **0**. So the centre
renders the background straight through.

My correct extrusion EXPOSED this; the old per-cell extrusion was hiding it by
collapsing the four inner corners over the centre. The fix is the backend's,
because it is coupled to the solver: `SeamlessTopologyTest` requires every render
triangle to be a solver area constraint, so a centre-junction quad needs two
matching area constraints (and the junction isn't a "seam", so it likely needs
budget past `MAX_SEAMS`). I cannot add it from `:app` without re-deriving topology,
which ADR 0018 deliberately removed. **Escalated to the Product Lead in handoff
0040 to dispatch the Backend Engineer.** Once the O's `bodyTriangleIndices` gains
the junction, `:app` needs zero changes (it assembles whatever the core publishes,
and the capacity sizing already keys off the longest per-archetype entry) — I will
re-shoot the O to confirm the hole is gone before the client sees it.

*— **Frontend Engineer***
