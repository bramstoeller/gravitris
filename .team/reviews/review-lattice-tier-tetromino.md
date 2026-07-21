# Architect review — tetromino lattice tier (ADR 0009 consistency)

Reviewer: **Architect** · Date: 2026-07-21
Prompted by the Backend Engineer (via the Product Lead): confirm the tetromino
shipping tier is consistent with ADR 0009 and record it.

## What I endorse without reservation

- **The corrected device benchmark is right.** The old benchmark measured a
  single-block pile and under-counted tetromino cost ~4×; the tier was being
  picked against the wrong number. Measuring a real tetromino pile
  (`buildBenchmarkScene`, packed to tetromino density) is the correct fix and I
  endorse it.
- **On the reference device, tetrominoes must ship at lattice 4.** The
  measurement is unambiguous: lattice 4 ≈ 9.4 ms device-est (under the 16.67 ms
  budget), lattice 5 ≈ 18.8 ms (over). Lattice 4 is the only tier that fits.

## Where I do NOT sign off as-is: shipping *multiple* lattice tiers at runtime

The proposal keeps ADR 0009's startup tier selection so "lattice 5 stays for
devices that benchmark faster." That reintroduces a defect the team had already
decided against, and it collides with an ADR that is **already on main**.

**1. Lattice tier still leaks into gameplay (verified in code).**
`SimConfig.kt:187` on `feat/tetromino-pieces`:
`val spacing get() = PIECE_WIDTH / (lattice - 1)` with `PIECE_WIDTH = 1.8`
constant. So `pieceExtent = PIECE_WIDTH + 2r` varies with lattice: **2.40 at
lattice 4, 2.25 at lattice 5** (11%). Piece size, packing, and pieces-per-row
therefore differ per tier, and `clearThreshold` is already documented PER-TIER in
`docs/contracts.md`. Two players on differently-benchmarked phones play a
measurably different game, and a replay captured on one tier is **not**
bit-identical on another (different particle count) — ADR 0006's replay guarantee
is per-`SimConfig`, and lattice is part of `SimConfig`. The unmerged
`chore/architecture` ADR "piece geometry / pinned lattice" fixed exactly this (by
making `pieceExtent` the constant and `spacing = pieceExtent/lattice`), but that
ADR was **not** carried over when 0012/0013 were cherry-picked to main, so the
fix is absent and the leak is live.

**2. It contradicts ADR 0013, which is already on main.** ADR 0013 (landed) reads
"With particle count pinned (ADR 0011)…" (line 53) and "ADR 0011's reasoning
stands: shipping three subtly different games was the wrong trade, and piece size
varying with a [performance setting]…" (lines 201–202). ADR 0013 assumes the
lattice is pinned and tiers are gone. But ADR 0009 (also on main) still specifies
startup tier selection, and main's ADR 0011 is the *silhouette* ADR — so ADR
0013's three references to "ADR 0011" (lines 53, 103, 201) are **dangling**: they
point at pinned-lattice reasoning that is not on main. The cherry-pick brought
0013's conclusion without the ADR that justified it. Right now main holds two
contradictory positions on the same axis.

## Recommendation

**Pin the lattice at 4 as the single shipping configuration; retire ADR 0009's
runtime tier selection.** Reasons, in order:

1. It matches the measurement — lattice 5 is over budget with tetrominoes on the
   reference device, so a runtime tier only ever benefits *unverifiable* faster
   devices, and it buys that at the cost of a per-device gameplay divergence.
2. It removes the leak in (1) and makes ADR 0013's "particle count pinned"
   reference true instead of dangling.
3. It deletes the per-tier `clearThreshold`/`overflow` calibration tax rather than
   extending it to seven shapes.
4. Keep the corrected benchmark — as the calibration reference for the pinned
   tier and as the trigger-to-revisit if a second, faster reference device ever
   appears (a build-time re-pin, not a runtime tier).

This is a **Product Lead call**, not mine to make alone: it narrows the "runs on
faster phones at higher quality" story to "one quality, tuned for the reference
device." I recommend it, but the shipping-story implication is the Product Lead's.
If the decision is instead to *keep* runtime tiers, that is viable only if the
`pieceExtent`-decoupling from the unmerged ADR lands first (so lattice becomes a
pure mesh-resolution detail with zero gameplay effect) and 0013 is amended — you
cannot ship the current tier code, which varies piece size per device.

Either way an ADR is owed (the gap at **0014** is available). I will draft it once
the Product Lead picks the direction, and it must also fix ADR 0013's three
dangling "ADR 0011" references.

## On "does lattice 4 look good enough" (Frontend/UX validation)

Legitimate to check, and it is a render question, not a sim one. Architectural
steer: the visible material quality is carried mostly by the ADR 0011 silhouette
extrusion (which rounds the outline off the particle centres) and the gel
fragment shader (subsurface, grain), not by raw mesh density. If lattice 4 reads
as too faceted in the emulator, the fix is **render-side** — more mesh
subdivision at draw time from the same particles, or shader work — **not**
shipping lattice 5, which reopens the gameplay leak and is over budget. That is
exactly ADR 0009/0013's "scale rendering, never particles" principle. So: pin the
sim at lattice 4, and if it needs to look smoother, make the *renderer* prettier.

## Item 2 — determinism across the phase machine: VERIFIED handled

The Backend reported `DeterminismTest` is migrated off `hardDropVelocity` and
covers the new phases. Confirmed against `feat/tetromino-pieces`:
- `slamActivePiece(9f)` replaces the old `hardDropVelocity = 9f` impact probe
  (`DeterminismTest.kt:36`, cites ADR 0016) — the supplied replacement for the
  removed field.
- `runPhasedGame` (`:164-186`) is a seeded replay that reads `activePiecePhase`,
  slides while POSITIONING, issues an early `drop` (tick % 37), rotates while
  FALLING, and asserts the tick-hash sequence is bit-identical across two runs.
  This exercises the weightless toggle, the positioning countdown and the phase
  transitions. The "one hard string" I flagged (determinism must survive the
  phase state machine + rotation) is genuinely covered in the branch.

Note: `slamActivePiece` is a public method on `Simulation` used as a test probe.
Fine as the test affordance that replaces hard-drop, but it should not be called
from production gameplay (hard-drop is gone) — worth a one-line comment saying so,
or making it `@VisibleForTesting`, so it does not become a backdoor control path.

---
*— **Architect***
