# Backend Engineer → Frontend Engineer: the O centre-junction is filled

**Branch:** `fix/o-centre-junction` (off `feat/candy-final-look`) · **PR:** #35
(base `feat/candy-final-look`) · **Commits:** `ddc82b7` (fix + tests), `dad66a1`
(ADR/contract). Closes the defect you raised in handoff 0040.

## What changed, and what you need to do

`bodyTriangleIndices[O]` (archetype 1) now carries **two more triangles** that
fill the central 2r×2r junction — the square where the O's four cells meet at a
point. At lattice 5 they are `{24,45,75}` and `{24,75,54}` (the four inner
corners `{24,45,54,75}`), wound CCW like every other triangle.

**Your side needs zero code changes.** `BodyMesh` assembles the IBO from
`bodyTriangleIndices[archetype[b]]` by length, so a longer O entry just draws two
more triangles. Nothing in the vertex path, the extrusion, or the contract shape
changes.

**What I need from you:** re-shoot the O (and a quick glance at the others) to
confirm the centre hole is gone and it reads as one solid glossy candy. That is
the visual proof — I have no GPU here.

## Why it is render-only (and what that means for you)

I did **not** add a solver constraint for the junction — the fill is render-only.
I tried the obvious near-rigid area constraint first and measured that it
destabilises heavy piles: a mass-8 O-bearing pile that used to settle rings at
KE ~0.08–0.41 and never reaches the 0.05 quiet line over 6000 ticks (the single
global `areaCompliance` makes it a stiff mode XPBD can't damp in 8 substeps). It
is safe without one: the junction's four edges are already welded by the four
seams, so the hole can't reopen, and `particleCompression` is per-particle, so
the fill shades correctly by interpolating its already-constrained corners.

Practical consequence for you: the O's two junction triangles are the **one
exception** to "every render triangle is a solver area constraint". Their
`vCompression` is the interpolation of the four corner compressions rather than a
measured centre area. In practice that reads correctly (the corners reflect the
surrounding squash), but if under a hard crush the O centre ever looks visually
wrong, that is the known trade — flag it and we can revisit with a per-constraint
compliance field (a solver change I deliberately deferred, recorded in ADR 0018).

## What I did NOT do
- No solver/physics change at all — every stability, lose-condition, determinism
  and constraint-count test is unchanged (304 constraints/body still).
- No `:app` change — see above.
- No new contract field — `bodyTriangleIndices` shape is unchanged; only the O's
  entry is two triangles longer.

## Considered and rejected
- **A near-rigid junction area constraint** (the natural "keep every render
  triangle backed by a constraint" answer). Rejected — measured heavy-pile
  instability above.
- **A soft (distance-compliance) area constraint or shear diagonals.** Still
  perturbed stability and still needs the render triangle; the welded perimeter
  already prevents the tear these would guard. Rejected.
- **A per-constraint area-compliance field** so the junction could be a soft area
  constraint. The physically "correct" answer, but it adds an array read to the
  area-solve hot loop every constraint every substep — a per-frame Fairphone cost
  (ADR 0009/0014) for a tear that can't happen. Deferred; recorded in ADR 0018.

## Uneasy about
Only the one look-call above: the O centre's `vCompression` is interpolated, not
measured. I expect it reads fine; your re-shoot under a normal pile is the check.
Everything else is pinned by tests.

---
*Handoff from the **Backend Engineer**.*
