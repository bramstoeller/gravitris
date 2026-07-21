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
| `03-O-cyan-solid-seamless-FIXED.png` | Two cyan **O** pieces settled. Each reads as ONE solid glossy candy block with a wet gleam — the dead-centre hole is GONE. The O acid-test: passes. Re-shot on `fix/o-centre-junction` (PR #35) which fills the junction. |
| `04-S-purple-smooth-material-no-waffle.png` | Purple **S** at spawn (undeformed). Smooth flat-saturated body, no surface texture — grain fix confirmed. |
| `05-O-airborne-solid-glossy-gleam.png` | Cyan **O** in a live stack (with a green L and rose J) — solid centre, glossy, one wet gleam. |

## Honest status against the bar

- Smooth glossy candy, no grainy cross-hatch/waffle — **yes** (all frames).
- Plump/voluminous, brighter body + richer rim — **yes**, best seen on the rose J.
- One soft wet gleam, not a hard scratch — **yes**.
- No internal "+", one continuous candy — **yes for all seven, the O included**.
- Rounded outer / sharp elbow / soft shadow / light world kept — **yes**.

## The O centre-hole is FIXED (was a core defect; the Backend Engineer closed it)

The original `03-O-...CENTER-HOLE` shot (now replaced) showed a white square where
the O's four cells meet at a point — a core `bodyTriangleIndices` gap, since
`SoftBodyWorld`'s seam model is strictly edge-to-edge and its four pairwise bridges
left the central 2r×2r square open (I measured: `bodyTriangleIndices[1]`, inner
corners `{24,45,54,75}`, triangles filling the junction = **0**). My correct
extrusion exposed it; the old per-cell extrusion had been masking it.

The Backend Engineer fixed it on `fix/o-centre-junction` (PR #35, off this branch):
`bodyTriangleIndices[O]` now carries two triangles `{24,45,75}` and `{24,75,54}`
that fill the junction. It is a **deliberate, recorded** render-only fill — no
solver area constraint, because a near-rigid junction constraint destabilised heavy
piles (measured), and the four seam-welds already hold the hole shut. `:app` needed
ZERO changes: `BodyMesh` assembles the IBO by length, so it picked up the two extra
triangles automatically.

**Verified visually here.** I rebuilt the debug APK from `fix/o-centre-junction`
(PR #35 CI green) and re-shot with the same `make playthrough` harness. The O now
reads as one solid smooth glossy candy like the other six — see `03` (two settled
O's) and `05` (an O in a live stack). No white centre, no internal "+".

*— **Frontend Engineer***
