# QA review — broadphase margin at the lattice-6 quality tier

Reviewer: QA Engineer. Branch: `test/core-sim-hardening` (off `feat/core-sim`).
Date: 2026-07-20. Verdict for the pass: **pass with one Major defect** — see
handoff 0020.

## Verdict

`:core-sim` is as solid as advertised on almost every axis I attacked. One real
defect: **contacts are not rigid at lattice 6**, the ADR 0009 high-detail
quality tier. Severity **Major** — a core interaction (hard drop) is visibly
wrong at a shipping configuration — not a Blocker, because it recovers, stays
contained, does not fully tunnel, and is deterministic.

## The defect

**Claim under test.** Contacts are rigid (ADR 0003 §2): two bodies must never
visibly sink into each other, because a soft contact "reads as a rendering bug
rather than as softness". Separately, the ADR 0009 quality tier (lattice 4|5|6)
changes only render detail — "an accessibility or performance setting must never
change what happens in the game" (`XpbdSolver.MAX_SPEED` comment).

**What happens.** A hard drop at terminal speed onto a settled pile:

| lattice | particle diameter (= cell) | drift/frame at v=30 | worst inter-body penetration |
| ------- | -------------------------- | ------------------- | ---------------------------- |
| 4       | 0.600                      | 0.83 cells          | **0.0%**                     |
| 5       | 0.450                      | 1.11 cells          | **0.0%**                     |
| 6       | 0.360                      | **1.39 cells**      | **43.7% of a diameter**      |

Measured at the frame boundary (after the tick's final contact solve), so a
detected rigid contact reads ~0 and a broadphase miss reads the full uncorrected
overlap. Numbers are bit-identical across runs — a clean cliff, not a noisy
boundary.

**Mechanism (confirmed by a frame trace, not inferred).** The broadphase is
rebuilt once per frame; the narrowphase centres a 3×3 stencil on each particle's
frame-start cell, tolerating ~1 cell of intra-frame drift (ADR 0003 §1,
Amendment 3). At lattice 6 the radius shrinks so a piece at `MAX_SPEED` drifts
1.39 cells in a frame — past the stencil. On the frame the faller arrives, its
stored cell is ~1.9 cells from the resting body, so the pair is never visited;
it penetrates, and the *next* frame's rebuild resolves the deep overlap
violently (trace: kinetic energy 8462 → 676 in one tick as the overlap is
expelled). ADR 0003 Amendment 3 named this exact coupling and asked that
shrinking the radius "re-trigger the non-tunnelling test". Nothing did — the
existing non-tunnelling test only exercises lattice 5.

`MAX_SPEED` (30) is reachable directly: `hardDropVelocity` is clamped up to it,
so this is the ordinary hard drop, not a contrived speed. In an 8-body pile the
transient penetration reached 44%; against a single resting body, 16%. It does
**not** fully tunnel through a solid body (5 rows thick » one frame of drift) and
containment against walls/floor is unaffected (those are solved per substep,
bypassing the grid).

## Reproduction

`core-sim/src/test/kotlin/gravitris/BroadphaseMarginTest.kt`, test
`a hard drop stays rigid at every quality tier`. It is parked `@Disabled` so it
does not turn `feat/core-sim` and its descendants red. To reproduce:

```
# delete the @Disabled annotation, then:
./gradlew :core-sim:test --tests "gravitris.BroadphaseMarginTest"
# => lattice 6: a hard drop sank one body 43.7% of a particle diameter into
#    another (rigid-contact budget 15%). expected: <true> but was: <false>
```

The assertion bounds penetration at 15% of a diameter for every lattice: lattice
4 and 5 measure exactly 0, lattice 6 measures 43.7%, so the threshold sits far
from both — it will not flake and it turns green the moment the broadphase margin
is restored at every tier.

## Suggested directions (for the Backend Engineer — your call, your module)

Fix-agnostic; the test asserts behaviour, not a mechanism. Options that would
restore the margin, each with the coupling ADR 0003 Amendment 3 already spelled
out:
- Rebuild the broadphase per substep when the radius is small (moves cost onto
  the substep multiplier — measure it).
- Widen the stencil to the drift (5×5 when drift > 1 cell) — cheaper than a
  rebuild, more candidate pairs.
- Make the cell size ≥ the max intra-frame drift rather than exactly one
  diameter — decouples cell size from radius.
- Clamp `MAX_SPEED` per lattice so drift stays < 1 cell — but that changes feel
  and would itself make physics depend on the quality tier, so probably not.

Any of these must keep determinism (ADR 0006) and zero allocation, and should
regenerate replay fixtures if it changes results — treat it as a
fixture-regeneration event, not a panic (handoff 0008).

## What I could not break (measured, adversarially)

- **Settled-pile drift** (Backend Engineer's flag): did not reproduce. A 16-body
  pile holds a flat 140 contacting particles and kinetic energy ≤ 2.8e-4 (quiet
  threshold 0.05) across 6000 idle frames. Wide, deep, lattice-4 and lattice-6
  piles are equally flat. No energy leak, no destabilisation over long runs.
- **Determinism under stress**: a 40-body scene under fast drags, hard drops at
  terminal speed, rotations and wall contact replays bit-identically, and splits
  across two step loops identically.
- **Empty well**: stepping with zero bodies is a safe no-op and recovers.
- **Body capacity**: the ceiling throws a clean IllegalStateException; a fully
  packed well (80 bodies, 2000 particles) stays finite and contained.
- **Zero gravity**: a weightless body at rest stays at rest.
- **Max fill / wedge against wall**: contained, bounded, no escape.

## What I could not test, and why

- **Cross-device bit-identity** (ADR 0006): no ARM device or emulator in reach;
  the JVM proves within-JVM determinism only. Honest gap, tied to the on-device
  benchmark blocker.
- **A full particle-particle tunnel-through**: I could not construct one. Solid
  bodies are ≥ 4 rows thick, far more than one frame of drift, and `MAX_SPEED`
  caps closing speed. The defect is deep penetration, not pass-through.
