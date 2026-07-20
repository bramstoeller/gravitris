# 0013 — Backend Engineer to Product Lead

Branch `fix/interlock-jitter`, off `feat/squish-toy`. Commits `855243c..193dfd4`.
Pushed. `./gradlew check` green (JVM tests, ADR 0008 no-Android check, CHK
manifest checks, lint).

## The verdict

**Neither candidate 1 nor candidate 3. It is candidate 2's mechanism — energy
manufactured out of resolved penetration — driven by two sources that are
neither the material's softness nor the renderer.**

The headline for the client: **the compliance you approved is exonerated, and
reverting it would have made this worse, not better.** No trade-off to take to
them. Nothing about the feel changes.

### 1. The interlock-and-tremble: the drag was a per-tick teleport

`Simulation.applyDrag` translated the active piece by the whole tick's drag
before the substep loop, with **no overlap check at all**. Ten lines below it,
`applyRotate` rejects a turn that would overlap, with a comment saying exactly
why — "that push is launch energy". The drag never got the same treatment.

Against a settled neighbour, the drag seeded overlap that the contact solve then
removed **inside a single substep**. `deriveVelocities` divides position change
by the substep `h`, not by the tick. So the overlap re-emerged as a speed
`substeps` times the finger's own: a leisurely 3 units/s drag manufactured
**24 units/s** of ejection, close to the 30 units/s terminal cap, renewed every
tick for as long as the finger was down.

That is the client's report, including the ending. "Trillend voor een tijdje,
**toen los**" — the tremble stops when the finger lifts. Nothing comes unstuck;
the player lets go.

This is reachable in ordinary play: the toy spawns each new piece on top of the
ones already there, and every new piece is draggable.

### 2. The crumpled airborne screenshot: the broadphase under-reached

The narrowphase stencil reaches one grid cell (`2 * particleRadius`, 0.45 well
units at lattice 5). The grid was rebuilt **once per tick**, but a particle may
move `MAX_SPEED * TICK` = 0.5 units in a tick, so two converging particles close
up to 1.0 against a 0.45 cell. Pairs converging faster than ~35 units/s were
**never tested** and interpenetrated up to **0.39 of a particle diameter** before
being found — then resolved rigidly in one substep, ejecting at the velocity cap.

The tell that this is a missed pair and not a stiffness limit is that it is
**non-monotonic** in closing speed: clean at 10, 20, 27, 30, 35 and 50, deeply
wrong at 40 and 60. Whether a pair is missed depends on where it sits relative to
a cell boundary when the grid happens to be built.

Free fall in a 20-unit well reaches the 30 units/s cap, so two bodies can close
at 60. This is reachable, and it is the best explanation for two *airborne*
bodies with one appearing to pierce the other.

## Evidence

Reproduced deterministically. `core-sim/src/test/kotlin/gravitris/InterlockJitterTest.kt`.

Tremor measured as direction reversals of each particle's offset from its own
body's centroid, over 120 frames of held drag. Total kinetic energy is useless
here — a piece shoved bodily along by a finger carries real momentum and is not
trembling.

| substeps | before | after |
| -------- | ------ | ----- |
| 4        | 189    | 38    |
| **8** (shipped) | **745** | **27** |
| 16       | 1290   | 31    |

Deepest cross-body overlap, fraction of a particle diameter, mid-air collision:

| closing speed | before | after |
| ------------- | ------ | ----- |
| 10–35, 50     | 0.000  | 0.000 |
| 40            | 0.309  | 0.000 |
| 60            | 0.393  | 0.000 |

## Why compliance is exonerated — the number that matters

Peak kinetic energy under a held drag against a neighbour, swept across the
stiffness range:

| distanceCompliance | peak KE |
| ------------------ | ------- |
| 1e-6 (Milestone 1) | 266.6   |
| **1e-4 (shipped)** | **162.1** |
| 2e-4               | 122.8   |

**The tremble is worse on stiffer material.** Reverting compliance would have
traded the product's reason to exist for a *louder* version of the same glitch.
This was the leading hypothesis and it was wrong; I am glad it was cheap to test
before designing anything.

I did not touch `distanceCompliance`, `areaCompliance`, `linearDamping`,
`friction`, `gravity` or `MAX_SPEED`. No feel parameter moved.

## Candidate 3 — handing it back, but only partly

I checked it directly rather than by inspection: reproduced
`VertexFill.extrudeBoundary` in the test harness and counted concave corners in
the drawn silhouette ring.

- **It never inverted.** Not in any scene measured, including the loudest drag
  and the 60 units/s collision. Zero inverted extrusions everywhere. A resting
  body draws perfectly convex.
- **It does mildly amplify concavity the physics already has.** At a 40 units/s
  collision, one genuinely concave corner in the particle lattice drew as
  **three**; in the held-drag case, zero raw concave corners drew as one.

So it is not the defect and not the cause, and I have not changed it. But the
extrusion is a ~2–3x amplifier of local boundary noise, because it displaces
each boundary vertex a fixed `radius` along a direction measured from a single
inward neighbour — and at lattice 5 that displacement is half the spacing between
adjacent boundary particles. **Worth the Frontend Engineer's eye as a polish
item**, not as a bug fix: deformation will read as more crumpled than it is. I
would not touch it until the client sees the current build.

## The jank question — it is not contacts, and it is not the solver

You asked whether the 23 jank/s and 43.9 ms worst frame correlate with contact
count. Measured, and the answer is no on both counts:

| bodies | particles | contacting particles | host step |
| ------ | --------- | -------------------- | --------- |
| 4      | 100       | 20                   | 46.9 µs   |
| 8      | 200       | 60                   | 101.3 µs  |
| 16     | 400       | 149                  | 205.0 µs  |
| 17     | 425       | 169                  | 218.9 µs  |
| 16 (fresh, unsettled) | 400 | **0** | **208.5 µs** |

Cost is dead linear in **particle count** and **independent of contact count** —
a scene with zero contacts costs the same as the same scene with 149. The
constraint solve dominates; contacts are noise against it.

At 17 bodies the host step is 0.219 ms. Even at the measured **12.06x** device
derating that is **~2.6 ms**, against a 16.67 ms budget. **The solver is not
producing the 43.9 ms frame.** That jank lives in rendering, GC or the frame
loop, and belongs to the Frontend or DevOps Engineer. I would not spend more
backend time on it without a device trace that attributes the time.

## Cost of the fix

`+2.1%` per solver step on the ADR 0001 reference scene (960 particles, 60
bodies): 468 → 478 µs, measured three runs each side. Seven extra broadphase
builds; a build is 0.6% of a step. My arithmetic estimate beforehand was 3.9%, so
the real cost came in under it — the extra builds partly pay for themselves in
fresher buckets. This is a relative cost and survives the 12.06x derating
unchanged. The drag fix costs nothing measurable.

Zero per-frame allocation preserved (`AllocationTest` green). Determinism
preserved (`DeterminismTest` green) — no transcendentals, no concurrency, and the
new work is in fixed order.

## What I deliberately did not do

- **Did not implement ADR 0012's explicit restitution coefficient.** It is the
  right change and it is the architect's decision, already made — but it is a
  *separate* change and this branch would have hidden it. Note that ADR 0012's
  reasoning is now partly overtaken: it attributes manufactured bounce to
  softness ("softer material penetrates deeper, so more velocity is
  manufactured"). Measured, the two largest sources of manufactured velocity in
  the shipping build were the drag teleport and the broadphase miss, and **both
  were independent of compliance**. Restitution is still worth making explicit
  for the reasons the ADR gives; it is just less urgent than it looked, and the
  stable-mass-range measurement it asks for should be taken **after** this branch
  lands or it will be measured against these two defects. **This wants a short
  ADR amendment — flagging for the Architect.**
- **Did not change `MAX_SPEED` or `HARD_DROP_MAX_SPEED`.** Lowering terminal
  velocity to 13.5 units/s would also have made the stencil sound, and it was the
  cheaper fix. I rejected it: it is a feel constant, it would have slowed every
  drop visibly, and it would have been deciding a feel question in a bug fix —
  the thing you explicitly asked me not to do.
- **Did not reject or clamp an overlapping drag** (the `applyRotate` treatment).
  It would have fixed the energy injection too, and it is simpler. I rejected it
  because it stops the player squishing a piece against its neighbour, which is
  the toy's whole point and what the client just approved.
- **Did not add a contact-churn regression test.** I wrote one, and it failed
  identically before and after my change (a settled 16-body pile goes from 142 to
  149 contacting particles over 300 idle frames). That is slow compaction, not
  churn my fix caused, and `StabilityTest` already covers energy and height
  drift. I removed it rather than ship an assertion I had not established was a
  defect. **Worth someone's attention eventually** — a settled pile should
  probably stop moving entirely — but it is not this bug and not urgent.
- **Did not regenerate replay fixtures.** `DeterminismTest` passes because it
  proves determinism by replaying a construction twice rather than against a
  golden file, so nothing needed regenerating. ADR 0012 anticipates fixtures that
  will need it; they do not exist yet.

## What the next agents need

**Frontend Engineer** — no API change. `Simulation.step(input)` is unchanged;
`InputFrame.dragX` means the same thing. One behavioural difference worth
knowing: the drag is now applied inside the substep loop, so `prevPositionX`
holds the **pre-drag** position and the drag interpolates smoothly across the
frame instead of jumping at the tick boundary. That should look slightly better,
not worse. Separately, see the extrusion amplification note above.

**QA Engineer** — the two reproductions are
`InterlockJitterTest.a piece held against a neighbour does not tremble` and
`...fast collisions are never missed by the broadphase`. The second **must stay a
sweep**; a single closing speed proves nothing because the failure was
non-monotonic. The assertion I would defend hardest is
`drag response does not depend on the substep count` — it fails for the right
reason and cannot be satisfied by tuning a threshold.

Run: `ANDROID_HOME=/state/android-sdk ./gradlew check`, or `./gradlew :core-sim:test`
for just the physics.

## What I am uneasy about

1. **I cannot confirm this is the client's exact event.** I have a mechanism that
   reproduces every element of their description — two bodies, interlocked,
   trembling, for a while, then apart when the finger lifts — and it is
   reachable in normal use. But their screenshot is deleted and I have no trace
   from the device. It is possible they saw something else. **The honest next
   step is a device build and asking them to try to reproduce it**, rather than
   declaring it closed.
2. **The tremor budget is a threshold in a gap, not a bound from first
   principles.** Measured 27–38 after, 189–1290 before; I set it at 60. The
   substep-independence test is the one that is actually principled.
3. **My tremor metric is my own invention.** Direction reversals of
   offset-from-centroid, with a noise floor of 0.01 well units/tick. I am
   confident it separates trembling from bulk motion — total kinetic energy
   demonstrably does not — but it has not been reviewed by anyone.
4. **The residual 27 reversals are not zero and I have not proven they are
   benign.** I believe they are the body genuinely deforming as it is pushed,
   which is the approved feel. I did not verify that visually because I cannot
   run the app here.
5. **The +2.1% is a host measurement.** It should hold as a ratio on device, but
   the 12.06x derating surprised everyone once already.
