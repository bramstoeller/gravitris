# 0006 — backend-engineer → product-lead

Date: 2026-07-20
Branch: `feat/core-sim`, based on `chore/build-foundation` @ `6e4c53a`
(not `main` — the foundation has not merged yet).
Commit range: `9df0e4a..HEAD` (2 commits). **Pushed to `origin`. Not merged.**

Stage 1, Track A (docs/build-order.md). `make test` passes, including the
ADR 0008 no-Android check and `:app` lint.

## What I built

`:core-sim`, framework-free Kotlin/JVM. Public API in `gravitris.game`,
solver internals in `gravitris.physics`.

**Solver (ADR 0001, 0003).** Substepped XPBD on a structure-of-arrays layout.
Per body a `lattice`x`lattice` particle grid with structural and shear
distance constraints plus two area constraints per cell. One constraint
iteration per substep — the "small steps" formulation, so compliance stays an
honest material parameter. Fixed 1/60 s tick, 8 substeps.

The lattice topology reproduces ADR 0001's measured table exactly, which is
the main evidence that I ported the right shapes:

| lattice | particles/body | x60 bodies | constraints/body | x60 |
| ------- | -------------- | ---------- | ---------------- | --- |
| 4 | 16 | 960 | 60 | 3 600 |
| 5 | 25 | 1 500 | 104 | 6 240 |
| 6 | 36 | 2 160 | 160 | 9 600 |

**Contacts (ADR 0003).** Uniform-grid broadphase, counting sort, rebuilt once
per frame; narrowphase per substep. Rigid (zero-compliance) non-penetration
with Coulomb friction clamped proportional to penetration depth, solved in one
pass. Self-collision within a body off. Boundary (floor/wall) contacts are
solved directly for every particle every substep and never go through the
broadphase.

**Render outputs.** `particleCompression` (current/rest area), `particleEdge`,
`particleContact`, frame-start previous positions for interpolation, body-local
`triangleIndices`, per-body aggregated `impacts`.

**Piece control.** Drag (kinematic, clamped to the well), quarter-turn rotate,
hard drop. All exercised by the determinism replay.

### The spike's three defects, guarded explicitly

The spike README says all three are easy to reproduce. Each has a named guard:

1. **Seeding overlaps → launch energy.** `addPiece` validates placement and
   *throws* rather than seeding a scene whose physics cannot be trusted. Also
   why blocked rotations are refused rather than applied and pushed apart.
2. **Friction depth read after resolution.** Penetration depth is captured
   before the normal correction, with a comment saying why.
3. **Per-frame allocation in the broadphase.** All grid arrays retained; an
   allocation assertion is in the suite.

## Tests — all pure JVM, no device (27 tests)

- **Stability.** A 24-body pile settles below the config's own
  `quietKineticEnergy` and *stays* settled over a further 600 frames, with a
  height-drift assertion so a quiet-but-creeping pile cannot pass. Also holds
  across per-particle mass 1/2/4/8 and compliance 1e-8/1e-6/1e-5.
- **Determinism.** Bit-identical (raw float bits) over 900 ticks of a scripted
  drag/rotate/hard-drop sequence; 450+450 equals 900; a fresh `InputFrame` per
  tick changes nothing; changing substeps *does* change results.
- **Allocation.** Measured, not assumed. Solver path is **exactly 0
  bytes/frame**. The input-driven path shows a fixed sub-kilobyte one-off
  inside the measurement window (it falls to 0.0 when frames are raised 8x, so
  it is JIT bookkeeping, not per-frame allocation); the assertion budget is
  8 bytes/frame.
- Containment, no-tunnelling on hard drop, compression under load, impacts,
  scene validation, and the inert Stage 3/4 contract fields.

## ⚠ ADR 0003's substep table does not reproduce. Please route to the Architect.

This is the finding I am least comfortable sitting on. Measured residual
kinetic energy after 900 frames, my implementation:

| scene | 2 | 4 | 6 | 8 | 12 | 16 |
| ----- | - | - | - | - | -- | -- |
| wide well 10x20, 24 bodies, 1e-6 | 0.0014 | 0.0004 | 0.0083 | 0.0139 | 0.0079 | 0.0053 |
| tall tower 5x60, 40 bodies, 1e-6 | **139405** | 0.0008 | 0.0003 | 0.0009 | 0.0093 | 0.0334 |

Two things follow, and they point in opposite directions:

- **The floor is real, and it is brutal below it.** At 2 substeps a deep pile
  does not jitter, it *explodes* — eight orders of magnitude. ADR 0003's
  refusal to treat substeps as a runtime quality dial is fully vindicated.
- **But the floor sits between 2 and 4, not at 8.** From 4 upward everything
  settles in both scene shapes with no trend — the variation above 4 is noise,
  and 8 is not measurably better than 4. In the wide well, *every* count
  settles, including 2.

I have **kept 8** and changed nothing: it is pinned in `docs/contracts.md`, ADR
0001's budget affords it comfortably, and margin on a stability floor is cheap
insurance. But the ADR presents 8 as measured, and my measurement disagrees
about where the floor is. The test asserts the *direction* of the finding (8
settles, 2 fails catastrophically) rather than the table, with a comment
pointing here. Someone should reconcile the two before QA invests in replay
fixtures, since ADR 0006 notes the floor should be settled before that happens.

Differences that could explain it: my scenes are seeded with a deliberate gap
(the spike's bug 1 was overlapping seeds); I added a terminal-velocity clamp;
friction and contact ordering are my own formulation, not theirs.

## Also answered: ADR 0003's flagged open question

ADR 0003 records as "unease worth recording" that its numbers came from a
narrow tower and that "a wide well produces more simultaneous contacts per body
than a narrow tower, and that specific configuration has not been measured. It
should be checked at Milestone 1."

**Checked. The wide well is the gentler case** — it settles at every substep
count tested. The ADR's conservatism holds in the direction it hoped. Both
shapes are now in the stability suite, and `benchmarkReferenceConfig()` uses a
*wide* well deliberately, because that is the shape the real game has.

## What I deliberately did not build

Per the build order, all of Stage 3 and later: **no piece spawning, no
sequence, no lock detection, no coverage bands, no clear rule, no losing
condition, no scoring, no difficulty ramp, no landing silhouette.** Building
the mechanic before the physics is felt means tuning it twice.

The corresponding `SimState` fields exist at the right shapes and sizes with
documented inert values, so `:app` can wire `uBandFill`/`uBandClear` now and
see nothing happen rather than needing a special case: `bandFill` all 0,
`bandClearProgress` all -1, `phase` always `Playing`, `score` 0, `level` 1,
`landing.valid` false. A test pins these so none of them can start reporting
something by accident.

Also not built: rendering, input handling, anything Android.

## For the frontend engineer

- **Package is `gravitris.game`**, per `docs/contracts.md`. The Stage 0
  `gravitris.coresim.CoreSimBuildScaffold` is deleted, as its own KDoc
  instructed. **I changed one line of `app/.../MainActivity.kt`** to repoint it
  at real solver types so trunk never stops building — you are replacing that
  file wholesale, so take yours on merge.
- **`InputFrame`: you must clear `rotate` and `hardDrop` yourself.** The core
  reads the frame and never writes to it. See "considered and rejected".
- **Getting a piece into the well:** `sim.addPiece(archetype, centerX, centerY)`
  returns a body index and makes it the active piece; `clearActivePiece()`
  releases control. This is a Milestone 1 harness affordance, not the spawner —
  Stage 3 replaces the call site, not the shape.
- **It throws** on an invalid placement (overlap, outside the well, at
  capacity). That is deliberate; do not catch and ignore it.
- Every array is longer than `particleCount` — capacity is allocated once.
  **Never loop to `array.size`.**
- `Simulation.buildBenchmarkScene()` gives you the ADR 0001 reference workload
  (960 particles, 3 600 constraints) for the hidden one-tap device benchmark
  that closes the derating blocker. Share it rather than rebuilding a scene, or
  the ratio will compare two different things.
- **Archetypes are palette indices only.** All seven are the same square
  lattice in Stage 1; real silhouettes arrive in Stage 3. A consequence: a
  quarter turn maps a square onto its own footprint, so rotation currently
  changes material orientation (and therefore grain/UV) but not the outline.

## Considered and rejected

- **Letting the core clear `InputFrame`'s one-shot flags.** "Consumed on the
  tick it is read" reads like the core should. Rejected: `step` would mutate
  its argument, so replaying a *recorded* sequence of frames would behave
  differently the second time as flags were consumed in place — breaking the
  replay determinism ADR 0006 exists to provide, in a way that is very hard to
  see. The cost is that a frontend which forgets to clear spins the piece every
  tick, which is loud and obvious. Preferred that failure mode.
- **Deriving piece width from `wellWidth`.** Looked elegant (a narrower well
  scales pieces down); actually wrong, and it broke the benchmark well. Piece
  width is a fixed 1.8, the spike's value, which every ADR number was taken at.
- **A speed clamp derived from broadphase cell size.** Would have made the
  physics differ per ADR 0009 quality tier — the exact failure `SimConfig`
  already warns about for `clearThreshold`. The terminal-velocity clamp is a
  tier-independent constant instead.
- **A half-stencil broadphase** (5 cells instead of 9). Cheaper, and the pair
  uniqueness argument depends on the stencil geometry being exactly right.
  Chose the full 3x3 with `j > i`, where uniqueness is obvious and survives
  anyone later changing the stencil.
- **A rotation lookup table**, as ADR 0006 suggests. Quarter turns are
  `(x, y) -> (y, -x)` — not merely deterministic but *exact*, with no table and
  no drift under repeated application. Strictly better than the ADR's advice.
- **Per-constraint compliance arrays** (as in the spike). Nothing varies
  material per constraint yet; it is memory and a load for an unused extension
  point. Single config value.
- **Adding `maxBodies` to `SimConfig`.** Would be a contract change crossing a
  module boundary. Derived from well area instead.
- **A seeded PRNG.** Nothing in Stage 1 is stochastic, so `SimConfig.seed` is
  **currently unread** — flagged below.

## Open questions and unease

1. **The substep floor disagreement above.** Highest priority.
2. **`initialPieceMass` is ambiguous.** I read it as mass **per particle**,
   because ADR 0003's stability table is stated per-particle ("per-particle
   mass 1, 2, 4 and 8") and that keeps the default on the measured-stable line.
   The name reads per-piece. One-line change if the other reading is intended —
   Architect's call.
3. **`SimConfig.seed` is not read by anything.** It must exist (contract), and
   the determinism guarantee currently rests on replay rather than on seeding.
   Nothing is wrong, but do not let anyone believe seed-based reproduction is
   tested, because it is not — there is nothing to reproduce yet.
4. **ADR 0006 conflates two different "previous positions."** It says "the
   solver already retains previous positions for its own integration", implying
   interpolation can reuse them. It cannot: the solver's previous position is
   per-*substep* and interpolating against it would cover 1/8 of a tick and
   stutter. I keep separate frame-start arrays. Cheap, but the ADR's sentence
   is misleading and should be corrected.
5. **The per-frame broadphase rebuild is sound for the stack, marginal at
   speed.** ADR 0003 argues it is safe because particles move a fraction of a
   cell per *substep* — but the grid is rebuilt per *frame*, which is 8x that
   motion. Worst case, a fast piece-vs-piece contact can be missed for one
   frame and corrected the next; it cannot tunnel through a body, and floor and
   wall contacts are unaffected because they bypass the broadphase entirely.
   There is an explicit no-tunnelling test and it passes. Flagging it because
   the ADR's stated justification is looser than it sounds.
6. **Friction stickiness**, as ADR 0003 predicted. In a settled pile the clamp
   usually exceeds the tangential motion, so contacts go fully static and the
   pile is stickier than reality. Good for stability; may fight "material flows
   into gaps". A Milestone 1 feel question, not a defect.
7. **Tuned magic numbers I introduced** that are in no ADR: terminal velocity
   30 units/s, impact threshold 1.5 units/s, impact full-scale momentum 20,
   seeding gap 5%. All documented where set. The impact numbers in particular
   are guesses about *feel* and want a real device and a real hand.
8. **All performance numbers remain host numbers.** I added no device
   measurement and the derating blocker is still open.
