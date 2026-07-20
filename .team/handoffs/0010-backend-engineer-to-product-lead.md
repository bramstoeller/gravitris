# 0010 — Backend Engineer → Product Lead

Date: 2026-07-20 · Branch: `feat/squish-softness` (off `feat/squish-toy`)
Commit range: `a7ed18c..48a0dc6` (one commit)

## The headline

**The blocks now deform. The fix was one number.**

`distanceCompliance` 1e-6 → **1e-4**. That is the whole change to the product;
everything else in the commit is tests and documentation.

| what a hard landing does | before | after |
| ------------------------ | ------ | ----- |
| height, as fraction of rest | 0.980 | **0.852** |
| width, as fraction of rest | 1.149 | **1.203** |
| **aspect ratio** (1.0 = perfect square) | **1.17** | **1.41** |
| body under a 24-body stack, height | 0.992 | **0.936** |

Measured on the client's scenario: lattice 5, 10x20 well, 8 substeps, hard drop.

## The thing nobody spotted, including me

**The 0.888–1.00 compression range I reported was the wrong number, and it is
why the rigidity was invisible in the data.**

`particleCompression` is an *area* ratio. Area is held near-rigid **on purpose**
— `areaCompliance` stays 100x stiffer than `distanceCompliance` so a squashed
body bulges sideways instead of shrinking, and bulging into gaps is the
coverage-band mechanic. So area was never the rigid quantity. **Shape was.**

The consequence is that my number could not have predicted the problem, and it
still cannot measure the fix: area compression at impact is ~0.895 before *and*
after. Judging squash by it reports "11% deformation" for a block that is
visually a perfect square. Aspect ratio is the honest metric, and it is what
`DeformationTest` now asserts on.

Two useful corollaries:

- The frontend's compression-darkening gain of 4.0, tuned against 0.888–1.00,
  **is still correct and needs no retuning.**
- That term will still be subtle, because the quantity it reads barely moved.
  The squash the client will see is the *silhouette*, not the shading.

## What it cost

**+3% solver time, not zero.** Measured host p50 at the ADR 0001 reference scene
(960 particles, 60 bodies), interleaved and repeated three times: 0.444 ms stiff
against 0.460 ms soft, ratio 1.025–1.035. The stiff number reproduces the
recorded 0.444 ms baseline exactly, which is what makes the comparison sound.

The code path is identical — same loops, same branches, same memory — so this is
not compliance itself. Softer bodies deform more, so slightly more particles
fall into contact and the narrowphase does more pair checks.

Scaled by the measured 12.06x derating: **5.36 ms → ~5.50 ms on device**, about
+0.16 ms on a 16.67 ms frame. That nudges an already-marginal 59.4 fps / 29 jank
situation very slightly worse. It does not change the shape of the performance
problem, and it does not fix it either.

**The architect's "softness is free" is true enough for cost and false for
stability.** See below — that is the load-bearing correction.

## Why not softer

I wanted more. 1e-3 gives an aspect ratio of 2.18, which is dramatic. **It is
not available**, and the reason is stability, not budget.

Softer bodies compress far enough that particles of *different* bodies
interpenetrate deeply. ADR 0003 makes contacts rigid, so resolving that depth
injects energy. Above roughly 2e-4 a deep pile or heavy material diverges rather
than settling.

**Substeps are not the lever, and the headroom ADR 0009 opened cannot be spent
here.** I measured 6 substeps as *worse* than 8 in exactly these marginal cases,
repeatedly and non-monotonically. Since I am not spending substeps, the substep
pin at 8 stands and there is no additional frame cost from this route.

## What I tried and rejected

**Damping** (0.005 → 0.02/0.05/0.10). ADR 0001 names it: dough is dissipative,
rubber is not. It stabilises everything — and destroys the mechanic. At damping
0.05 the peak height ratio is **1.000**: no squash at all, because per-substep
damping kills the impact velocity before the body can deform. It buys stability
with exactly the thing we are trying to create.

**Splitting structural and shear compliance.** My hypothesis was that squash
shortens *structural* edges while the collapse mode is *shear*, so the material
could be soft to the first and stiff to the second. I implemented it and
measured it. **Refuted:** squash saturates at aspect ~1.24 across a 33x range of
structural compliance, because a lattice cell with stiff diagonals and preserved
area cannot change aspect ratio at all. Shear compliance is the binding
constraint on squash, which is the inverse of my hypothesis — so squash *needs*
soft diagonals, which is precisely what permits collapse. The two cannot be
separated. I reverted the change rather than leave an unused config field and a
branch in the hot loop.

**A measurement method I had to throw away.** My first stability metric was peak
kinetic energy over a hold window. It produced an *alternating* pass/fail pattern
as compliance moved 20% — 1.0e-4 fail, 1.2e-4 pass, 1.5e-4 fail. A real
stability limit is monotonic, so that pattern meant I was measuring noise. Peak
alone cannot tell a one-off slip in a settled pile from a divergence. Measuring
peak *and* final energy *and* frames-above-quiet separates them, and it changed
the answer: what I had recorded as a failure at 1.0e-4 is a single loud frame
that decays (0.05 → 0.023). **Had I trusted the first metric I would have
shipped a needlessly timid value, or a wrong one.**

## Constraints held

- **Determinism** — `DeterminismTest` green. No new transcendentals, no
  concurrency, constraint order untouched.
- **Zero per-frame allocation** — `AllocationTest` green.
- **No Android dependency in `:core-sim`** — the ADR 0008 check passes; full
  `./gradlew check` including lint is green.
- **No contract change.** I changed a default value, not the field list. `:app`
  overrides only `lattice` and well size, so it inherits this automatically.

## Two existing tests moved — both re-measured, neither relaxed

I want these looked at, because "engineer softens material, engineer widens
failing tolerances" is the exact shape of a bad change.

1. **`StabilityTest` substep floor.** Asserted 2 substeps exceeds 100 energy;
   it now reaches ~89. Softer material fails *less violently* because a
   compliant constraint transmits less of the correction it cannot make. The
   direction is unchanged and still vast: ~89 against ~2e-4 at 4 substeps. I
   lowered the bound to 10 to sit inside that gap. **The test's own comment
   asked for exactly this re-measurement if solver characteristics changed.**

2. **`SolverBehaviourTest` wedged rotation.** Asserted instantaneous kinetic
   energy after a rotation; it went from 0.08 to 3.87. This is real new
   behaviour: turning a body that settled under gravity reorients the strain it
   settled with, and it springs back. **I did not widen the number.** I measured
   what the test exists to catch — a launch — and found the rotated piece's
   centroid moves **0.0000 well units**, its neighbour's likewise, and the pile
   returns to quiet. It is a wobble in place, which is correct for squishy
   material and arguably good feel. The test now asserts displacement and
   return-to-quiet, which is stricter than the energy bound and is not fooled by
   soft material.

## On the tuning panel — yes, pull it forward

**My recommendation: build backlog item 10 before any further tuning of this
value.** The argument is stronger than convenience:

- I tuned against *metrics* — aspect ratio, energy — not against anyone's eyes.
  I can tell you the blocks deform 7x more than they did. I cannot tell you
  whether 1.41 is what "spongy" means to the client, and no amount of further
  measurement on my side will answer that.
- The usable range is narrow and I have mapped it: **1e-4 is near the top.** The
  client can be given a slider from 1e-6 to 2e-4 and every value on it is one I
  have verified settles. Past ~2e-4 it diverges, so the panel should clamp
  there rather than offer a value that breaks the game.

**What the frontend needs to know:** compliance is a `SimConfig` field, configs
are immutable, so changing it means constructing a new `Simulation` (ADR 0006).
`GameRenderer.rebuildSimulationIfWellChanged` is already exactly that path — a
panel reuses it. The well empties on change, which for a squish toy is fine
since you re-drop blocks anyway. No core-sim work is required; this is entirely
`:app`.

## What I deliberately did not do

- **Did not touch the renderer.** Nothing I found suggests the rigidity was a
  rendering problem — the solver's own geometry was rigid, and the silhouette is
  now measurably not. So no handoff on that front is warranted.
- Did not touch haptics, game rules, or the `haptics:fixed` finding from the
  device review (finding 3, still open and still unaddressed).
- Did not re-derive ADR 0009's quality tiers from the 12.06x derating. That is
  outstanding from the device review and is architect work.
- Did not fix the underlying performance problem. 59.4 fps with 29 jank/s at 24
  bodies is untouched and this change makes it 0.16 ms worse.

## What I am uneasy about

1. **The mass ramp is where this will break.** Every genuine divergence I found
   was at per-particle mass 4 or 8, never at mass 1 or 2. XPBD's effective
   softness scales with 1/mass, so material tuned at mass 1 behaves ~8x softer
   at mass 8. `massPerLevel` is Stage 4 and not yet read, so nothing is broken
   today — but **the difficulty ramp will make blocks progressively softer and
   then unstable**, and the natural fix is to scale compliance with mass so the
   material feels the same at every level. That is a solver-semantics decision
   and I did not want to make it unilaterally. It should be an ADR before Stage 4.

2. **1e-4 is close to the edge.** Not a comfortable margin. The mass-8 case
   shows a single loud frame even at the shipped value. It decays and I am
   satisfied it is a slip rather than divergence, but I would not push this
   value up without re-running the full matrix.

3. **I could not test on the real device.** The +3% is a host measurement scaled
   by the recorded 12.06x. The *relative* claim is solid because the code path is
   unchanged, but somebody should confirm the absolute frame time on the
   Fairphone before this is called done.

4. **"Visible" is my judgement, not the client's.** Aspect ratio 1.41 at peak,
   held for 2–3 frames. The sustained deformation under load (0.936) is the part
   I am most confident reads, because it is always on screen rather than being a
   33 ms flash. If the client says it is still not squishy enough, the honest
   answer is that the solver cannot go much further without the contact model
   changing — and that is an ADR 0003 conversation, not a tuning one.

## Verification

```
./gradlew check          # green: 30 core-sim tests, app tests, lint, ADR 0008 check
```

`DeformationTest` is the new regression guard, and I verified rather than
assumed that it rejects the old value:

```
dc=1e-06  h=0.9801  w=1.1494  aspect=1.1728   stack restH=0.9921
dc=1e-04  h=0.8515  w=1.2027  aspect=1.4124   stack restH=0.9364
```

The height, aspect and under-load assertions all reject 1e-6 with wide margin.
The width bound rejects it by 0.0006, which is luck rather than design — it is
there to guard the sideways bulge, not to catch a stiffness regression, and the
test says so.
