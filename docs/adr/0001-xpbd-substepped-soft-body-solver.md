# 0001. XPBD with substepping as the soft-body solver

Status: proposed
Date: 2026-07-20

## Context

The whole product rests on soft bodies that squash and settle convincingly at
60fps. The brief assumes XPBD and assumes a structure-of-arrays layout because
"soft-body physics is cache-bound". Both assumptions were untested, and the
Product Lead's stated fear was that the affordable budget would be so small that
blocks must be stiffened and "spongy" quietly degrades into "slightly springy".

That fear needed a number, not an opinion, so this decision is made against a
measured spike: `/work/spike/solver-budget/`, results in
`/work/spike/solver-budget/results-host.txt`.

Candidate approaches:

- **XPBD** (Extended Position Based Dynamics) — position-level constraint
  projection with a compliance term, so stiffness is a real material parameter
  rather than an artefact of iteration count and timestep.
- **Shape matching** — fit a rigid transform to each body's rest shape and pull
  particles toward it.
- **Pressure / gas model** — a closed particle shell with an internal pressure
  force proportional to enclosed area.
- **FEM** — continuum elasticity on a triangle mesh.

## Decision

**XPBD, integrated with many substeps and a single constraint iteration per
substep** (the "small steps" formulation), on a structure-of-arrays layout.

Per body: a lattice of particles with structural and shear **distance
constraints**, plus two **area constraints** per lattice cell so the body
resists collapsing flat rather than only resisting stretch.

Measured budget, host (x86-64, HotSpot 21, single-threaded, `taskset`-pinned):

| particles | constraints | substeps | p50 ms/frame |
| --------- | ----------- | -------- | ------------ |
| 320       | 1 200       | 8        | 0.159        |
| 640       | 2 400       | 8        | 0.322        |
| 960       | 3 600       | 8        | 0.493        |
| 1 280     | 4 800       | 8        | 0.663        |
| 1 500     | 6 240       | 8        | 0.846        |
| 2 160     | 9 600       | 8        | 1.280        |
| 960       | 3 600       | 2        | 0.137        |
| 960       | 3 600       | 16       | 0.983        |

Cost is linear in both scene size and substep count, with no surprises:

```
host_ms ≈ 0.49 × (particles / 960) × (substeps / 8)
```

**The headline finding: "spongy" is not threatened by the budget.** Cost depends
on constraint count and substep count, and is *independent of compliance*. The
spike settles cleanly at compliance 1e-8 (firm), 1e-6 (medium) and 1e-5 (spongy)
— a 1000x stiffness range — all at the same cost. The thing that constrains
softness is the substep floor (ADR 0003), and that floor is affordable. The
Product Lead's central worry does not survive measurement.

**Layout: SoA is adopted, but not for the reason the brief gives.** Measured
SoA vs an array-of-objects mirror on identical constraint work:

| particles | working set | AoS / SoA |
| --------- | ----------- | --------- |
| 960       | 100 KB      | 1.01x     |
| 2 160     | 255 KB      | 1.02x     |
| 8 640     | 1.0 MB      | 1.03x     |
| 34 560    | 4.0 MB      | 1.03x     |
| 138 240   | 16 MB       | 1.15x     |

At the scale this game actually runs (100–255 KB) the working set fits in L2 and
**the layout is worth 1–3%, not the large win the brief assumed.** The crossover
only appears an order of magnitude past our budget. We adopt SoA anyway because
its real payoff on Android is *allocation*, not cache: primitive arrays let the
steady-state loop allocate nothing, and the spike measures **0 bytes/frame**
across solver and coverage bands. On ART a per-frame allocation stream buys GC
pauses, and a pause on a 16.67ms budget is a dropped frame. That is the argument
that holds; the cache argument does not at this scale, and we should stop
repeating it.

## Alternatives considered

**Shape matching** — rejected. It is cheaper and unconditionally stable, which is
genuinely attractive. It lost because it pulls every body back toward its *rest
shape*, which is precisely the behaviour this product must not have: a stack that
has settled under load should stay deformed, and pieces squeezed into a gap
should stay squeezed. Shape matching would fight the mechanic. It also gives no
natural volume/area preservation, so bodies thin out under load instead of
bulging sideways — and bulging sideways into gaps is the coverage-band mechanic.

**Pressure / gas model** — rejected as the primary model. A pressurised shell
gives beautiful bulge and is cheap (one area accumulation per body). It lost on
two counts: a shell has no interior structure, so a heavy stack collapses it into
a crease rather than compressing it evenly; and stacking pressurised shells is
notoriously jittery because the pressure response is global to the body, so one
contact perturbs every vertex. Note that our per-cell area constraints give most
of the bulge benefit with local, stable response.

**FEM** — rejected. It is the physically correct answer and produces the best
material behaviour. It lost on cost and on complexity: it needs per-element
stiffness assembly, a linear solve, and careful handling of inverted elements,
against a client instruction to keep the first iteration simple. XPBD's area
constraints reach "good enough to feel heavy" at a fraction of the implementation
risk. Revisit only if playtesting says the material reads as bouncy-rubber rather
than heavy-dough and compliance tuning cannot fix it.

**XPBD with few substeps and many iterations** — rejected on measurement. It is
the older PBD formulation and it makes stiffness depend on iteration count, so
"how spongy" and "how stable" stop being separable dials. The substepped
formulation keeps compliance as an honest material parameter, and ADR 0003 shows
substeps buy stability far more efficiently than iterations buy it.

## Consequences

**Easy.** Stiffness becomes a single tunable per material, independent of
timestep and iteration count, so "how spongy" is a slider the UX Designer and
the client can turn during the demo rather than an emergent property. Cost is
linear and predictable, so quality tiers are arithmetic (ADR 0009). The solver is
a few hundred lines of primitive-array loops with no library dependency, which is
what makes ADR 0002 (pure Kotlin, no NDK) viable and ADR 0008 (framework-free,
JVM-testable core) enforceable.

**Hard.** Area constraints resist collapse but do not conserve volume globally;
under extreme load a body can still thin more than a real solid would. Gauss-
Seidel constraint ordering makes the solver inherently sequential, so we get no
multicore scaling without a graph-colouring scheme we are explicitly not building
(ADR 0009). Compliance is in simulation units with no physical meaning, so every
material value is a tuned magic number that must be documented where it is set.

**Live with.** The measured numbers are host numbers. The derating to the
reference device is an estimate with a wide band until an APK runs on real
hardware — see ADR 0009 and `/work/.team/blockers.md`. The spike's stability
verdicts also come from a pile that is ~4 units wide and ~46 tall rather than a
filled 10x20 well, because of how bodies are seeded; that is a *more* severe
compression case than the real game, so the stability conclusions are
conservative, but the contact counts are not representative and should not be
quoted as such.
