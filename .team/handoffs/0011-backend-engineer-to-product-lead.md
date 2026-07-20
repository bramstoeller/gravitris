# 0011 — Backend Engineer → Product Lead

Date: 2026-07-20 · Branch: `fix/contact-gap` (off `feat/squish-toy`)
Commit range: `371ba0f..HEAD` (one commit)

## The answer

**It is rendering. The bodies touch.**

You asked me to establish which before changing anything. I did, and the
measurement is not close.

| lattice | particle radius | nearest inter-body centre distance | **physical surface gap** |
| ------- | --------------- | ---------------------------------- | ------------------------ |
| 4 | 0.300  | 0.599999 | **-0.0000006** |
| 5 | 0.225  | 0.449999 | **-0.0000009** |
| 6 | 0.180  | 0.359813 | **-0.00019**   |

Settled pile, pieces dropped down the middle exactly as the squish toy drops
them, 90 frames per piece plus a 240-frame settle. The surface gap is zero to
within float noise at every quality tier — very slightly negative, which is
contact being resolved, i.e. touching.

The floor tells the same story: a resting body's lowest particle centres sit at
**exactly** one radius above y=0. Its surface is on the floor.

**So there is no separation to close in the physics, and nothing in ADR 0003
needs to change.** This is not the structural answer I was told to watch for.

## Where the margin actually comes from

Every position in `docs/contracts.md` is a particle **centre**. The solver
treats the material as extending `particleRadius` past that centre — that is
what `pieceExtent` means, what the placement guard validates against, and what
boundary contacts clamp to.

The renderer builds its mesh from those positions verbatim (`VertexFill.fill`
copies `positionX/Y` straight into the vertex buffer; `LatticeTopology` spans
particle centres). So **every body is drawn one particle radius small on every
side**, and two bodies whose surfaces are touching draw with `2 * radius` of
background between them.

At the shipped lattice 5 that gap is **0.45 world units — a quarter of a piece's
width**. It is not a subtle inset:

| lattice | drawn width | true material width | **fraction of the material we draw** |
| ------- | ----------- | ------------------- | ------------------------------------ |
| 4 | 1.8 | 2.40 | **56%** |
| **5 (shipped)** | 1.8 | 2.25 | **64%** |
| 6 | 1.8 | 2.16 | **69%** |

**We are drawing 64% of the material's area and leaving 36% invisible.** The
client is not seeing a margin around the blocks; they are seeing the blocks
drawn too small.

## Good news on the mechanic

Your concern was that a permanent margin caps coverage below 100% by
construction and undermines the coverage-band mechanic before it exists. **It
does not**, on both counts:

- The physics packs to genuine contact, so material can reach 100%.
- ADR 0004 already specifies stamping **each particle's disk** into the
  occupancy bitmap, not the centre hull. Implemented as written, coverage reads
  the true material footprint and is unaffected by this bug.

The mechanic is safe. What was at risk was only what the player sees — though
that is quite bad enough, because packing tightly currently *looks* unrewarded
even though it is.

## What I did — and the line I did not cross

Per your instruction I did not touch the renderer. But I found the renderer
**cannot** fix this correctly today: `particleRadius` was not published on
`SimState` or in the contract, so `:app` would have had to re-derive it from
`PIECE_WIDTH` and `bodyLattice`. That is a second definition of one thing —
precisely the failure mode `LatticeTopology`'s own doc comment warns about, and
it would drift silently the first time the radius derivation changes.

So I shipped the backend half and stopped:

- **`SimState.particleRadius`** published, documented, and added to
  `docs/contracts.md`. Additive per contracts §5, so no module boundary is
  crossed.
- **`ContactGapTest`** — three tests pinning the physics invariant: the
  published radius is half the lattice spacing, settled bodies touch with no
  physical gap, and a resting body's centres sit one radius above the floor.

**This was a judgement call and you can reverse it.** I judged one field with an
immediate consumer to be a blocker worth removing rather than an unused
abstraction. If you would rather the frontend engineer own the whole change,
the commit is self-contained and revertible.

I did **not** touch `VertexFill`, `BodyMesh`, `LatticeTopology`, the shaders, or
anything else in `:app`.

## What the Frontend Engineer needs

**The fix is to expand each body's drawn outline outward by
`state.particleRadius`.** Consume the published value; do not re-derive it.

Three things to know before starting:

1. **Radius is the right expansion, not a fudge factor.** It is the same number
   the contact solve and the placement guard use. Expand by exactly it and the
   drawn surface coincides with the physical one: touching bodies meet with no
   gap and no overlap, and a resting body sits on the drawn floor. The well
   frame is already drawn at the true well bounds, so expanded bodies will meet
   the walls correctly too — no change needed there.

2. **Uniform scaling about the centroid is wrong.** It over-extends corners
   relative to edge midpoints, and it breaks entirely on a deformed body, which
   is every body that matters here. The correct construction is a boundary
   extrusion: walk the boundary ring (`particleEdge == 1`), accumulate an
   outward normal per boundary vertex from its adjacent boundary edges, and
   offset by the radius — a quad strip skirt around the existing mesh. That is
   `4 * (lattice - 1)` extra vertices per body.

3. **This probably wants an ADR 0007 amendment.** ADR 0007 specifies the shading
   inputs in detail but never says where the *silhouette* is — that omission is
   how this shipped. `vEdge` in particular is defined as "free surface, 0
   interior → 1 boundary", and after extrusion the actual free surface is the
   skirt, not the boundary ring. Worth the architect's eye rather than being
   decided in the renderer.

## A second finding, which I think matters more than the first

**A piece's true material size varies by 11% across ADR 0009's quality tiers.**

`pieceExtent = PIECE_WIDTH + 2 * particleRadius`, and the radius is half the
lattice spacing, so:

- lattice 4 → piece is **2.40** wide
- lattice 5 → piece is **2.25** wide
- lattice 6 → piece is **2.16** wide

The same piece is physically a different size depending on the quality tier.
That means **the performance dial changes the game** — how many pieces fit per
row, how the well packs, and therefore coverage. The codebase elsewhere
explicitly forbids exactly this: the terminal-velocity constant in `XpbdSolver`
is a fixed number rather than a derived one specifically so that "an
accessibility or performance setting must never change what happens in the
game."

This is true **today**, in the physics, independent of the rendering bug. It is
currently invisible because we draw 1.8 regardless of tier — the rendering bug
has been masking it. **Fixing the silhouette will make it visible**, so these
two should be looked at together.

The fix is to make `PIECE_WIDTH` mean the material extent and derive the
centre-to-centre span from it (`PIECE_WIDTH - 2 * radius`), so the piece is the
same size at every tier. That changes every measured number in ADR 0001 and
0003, so it is an architect decision, not a tuning one. I have not made it.

## On the bounce — noted, not chased

You asked me to note an obvious cause if I saw one. I did, from reading the
contact code; **I did not measure it**, so treat this as a hypothesis.

There is no restitution coefficient anywhere — contacts are pure rigid position
corrections, as ADR 0003 §2 specifies. The bounce is manufactured in
`deriveVelocities`, which computes `v = (pos - substepPrev) / h` **after** the
contact solve. So a penetration that gets pushed out becomes outward velocity
that was not there before: energy created by resolving the contact. This is the
classic PBD energy injection, and it is the same mechanism I flagged in handoff
0010 as the ceiling on softness.

The connection to the client's report: softer material at 1e-4 penetrates deeper
per contact than 1e-6 did, so more velocity is manufactured per resolution.
**That is consistent with bounciness appearing only after the softening landed**,
which is what the client describes. The standard remedy is to not let contact
corrections feed back into velocity at full strength, which is an ADR 0003
change to the contact model. Not a defect report, so I stopped here.

## Constraints held

- **Deformation not regressed** — `DeformationTest` green; aspect ratio 1.41
  untouched. I changed no solver code.
- **Stability at compliance 1e-4** — `StabilityTest` green, unchanged.
- **Determinism** — `DeterminismTest` green. The new field is a published
  constant; nothing in the solve changed.
- **Zero per-frame allocation** — `AllocationTest` green. `particleRadius` is a
  `Float` computed at construction.
- `./gradlew check` fully green including lint and the ADR 0008 boundary check.

## Verified rather than assumed

I checked that `ContactGapTest` actually rejects the state it claims to guard
against, by injecting a 2.15x separation margin into the contact solve
(`r2 = 2.15f * particleRadius`) and confirming
`settledBodiesTouchWithNoPhysicalGap` fails. Then restored the solver and
confirmed a clean diff. A test asserting "the gap is zero" that computes the gap
from the same radius it is checking could easily have been vacuous; it is not.

## What I deliberately did not do

- **Did not touch the renderer.** Your instruction, and I agree with it.
- **Did not change `particleRadius` itself.** It is the physics quantity —
  shrinking it until the drawn shapes meet is the tempting fix and it is wrong
  twice: a body's own particles would stop touching at rest (it becomes a bag of
  separated dots), and it is the broadphase cell size, so stability moves.
  `ContactGapTest` now fails loudly if anyone tries.
- **Did not fix the tier-dependent piece size.** Architect call; see above.
- **Did not touch the bounce, haptics, or `haptics:fixed`** (device review
  finding 3, still open).

## What I am uneasy about

1. **I published a field with no consumer yet.** It is one `Float` with an
   immediate, identified use, but it is technically an unused abstraction until
   the frontend lands the extrusion. If that work does not happen, this should
   be reverted rather than left sitting there.

2. **The tier-dependent piece size is the bigger bug and I am handing it over
   rather than fixing it.** It has been in the physics since the beginning and
   nobody has seen it, including me until today. It deserves an ADR before more
   is built on top of the current numbers.

3. **I measured on the host, not the device.** The geometry claims are exact
   arithmetic and device-independent, so I am confident in them — but nobody has
   confirmed on the Fairphone that closing the gap looks the way we expect. That
   needs the frontend change first.
