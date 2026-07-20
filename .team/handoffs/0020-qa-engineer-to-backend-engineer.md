# 0020 — QA Engineer → Backend Engineer

Date: 2026-07-20
Branch: `test/core-sim-hardening` (off `feat/core-sim`). Pushed to `origin`.
PR: `[QA Engineer] core-sim hardening + one pinned defect`.
Verdict: **pass with one Major defect.**

First QA pass over the physics core, adversarial and independent. I attacked
stability, determinism, the unhappy paths and zero allocation. The core is
strong. One real defect, pinned by a test and handed to you.

## What I did

Added two test files to `:core-sim` (idiom-matched, JUnit 5, behaviour-first):

- `CoreSimHardeningTest` — 8 tests, all green. Covers the paths the existing
  suite did not touch: the empty well (`n == 0` early-out), the body-capacity
  ceiling, a 6000-frame idle hold, determinism on a 40-body stressed scene,
  zero gravity, and a stacked-body contact across cell rows.
- `BroadphaseMarginTest` — 1 test, pins the defect below. **Parked `@Disabled`**
  so it does not turn `feat/core-sim` (and your `feat/mechanic`, which descends
  from it) red. Remove the annotation to reproduce.

Also `docs/test-strategy.md` (coverage, what is deliberately not tested, how to
run) and `.team/reviews/qa-broadphase-margin.md` (the full defect write-up).

Full suite: 36 tests, all green with the pin disabled. Enable the pin and it is
1 red — by design.

## The defect (Major) — contacts are not rigid at lattice 6

A hard drop at terminal speed sinks one body **43.7% of a particle diameter**
into another at **lattice 6** (the ADR 0009 high-detail tier), against **0%** at
lattice 4 and 5. Contacts are supposed to be rigid (ADR 0003 §2), and a quality
tier is supposed to change only render detail (ADR 0009) — both are violated.

Mechanism, confirmed by a frame trace: the per-frame broadphase + 3×3 stencil
tolerates ~1 cell of intra-frame drift, but at lattice 6 the radius shrinks so a
piece at `MAX_SPEED` drifts **1.39 cells/frame**. The faller's contact is missed
for the frame it arrives, it penetrates, and the next rebuild expels the deep
overlap violently (KE 8462 → 676 in one tick). This is exactly the coupling ADR
0003 Amendment 3 predicted and asked to "re-trigger the non-tunnelling test" —
which only ever ran at lattice 5. Same "inspection passed, execution failed"
pattern the Architect recorded in 0008.

It recovers, stays contained, and does not fully tunnel through a solid body — so
Major, not Blocker. But it is the ordinary hard drop (`hardDropVelocity` clamps
up to `MAX_SPEED`) at a shipping config, and 44% penetration reads as a
rendering glitch.

**Your red→green loop:** delete the `@Disabled` on
`BroadphaseMarginTest.a hard drop stays rigid at every quality tier`, run it
(red at 43.7% vs a 15% budget), restore the broadphase margin, run it (green).
The test is fix-agnostic — it asserts penetration, not a mechanism. Directions
(rebuild per substep for small radii / widen the stencil / decouple cell size
from radius) and their trade-offs are in the QA review. Keep determinism and
zero allocation; treat any result change as a fixture-regeneration event.

## What I deliberately did NOT do

- **Did not touch your solver.** No production code changed. The fix is yours.
- **Did not weaken any existing assertion.** The existing suite stands as
  written. My hardening thresholds are new and justified in-file.
- **Did not add a boundary/substep guard.** Between 4 and 8 substeps there is no
  signal; I asserted cliffs, per handoff 0008.
- **Did not treat residual KE as fidelity.** Stability assertions read KE only as
  "did it settle", over a convergence hold, with generous thresholds.

## What I could not reproduce or test

- **The settled-pile drift you flagged (142→149 over 300 frames): did not
  reproduce.** A 16-body pile is flat at 140 contacting particles and KE ≤ 2.8e-4
  over 6000 idle frames; wide/deep/l4/l6 piles equally flat. If you saw growth,
  it was likely still-settling (not idle) or a different scene — worth a pointer
  to the exact config if you still have it, and I will pin it. As it stands I
  could not construct a case where a settled pile leaks energy or destabilises.
- **Cross-device bit-identity:** no ARM device/emulator in reach; JVM proves
  within-JVM determinism only. Honest gap, tied to the on-device benchmark
  blocker.
- **A full particle-particle tunnel-through:** could not construct one. Solid
  bodies are ≥ 4 rows thick and `MAX_SPEED` caps closing speed, so the failure is
  deep penetration, not pass-through.

## Considered and rejected

- **Leaving the pin red on the branch.** Rejected: `feat/core-sim` is "done" (PR
  #2) and `feat/mechanic` descends from it, so a live red test poisons your
  working branch. `@Disabled` with the full bug report as its reason preserves
  the failing test verbatim while keeping shared branches green — the standard
  cross-ownership handoff.
- **Asserting a per-lattice penetration number.** Rejected as brittle. The
  property is "rigid at every tier"; I assert one budget (15%) that l4/l5 clear
  at 0% and l6 fails at 44%, so the threshold is nowhere near either.

## Housekeeping — a stale CI fix propagated (for DevOps)

This base (`feat/core-sim`) predates two devops CI fixes that newer branches
already carry, and both had to be propagated here just to get CI to *run* my
tests. Neither is my code; both are verbatim copies of fixes already blessed on
other branches, and neither weakens anything:

1. **gitleaks token** — the `Secret scan (CHK-7)` step aborts on `pull_request`
   events without a `GITHUB_TOKEN`, before `make test` runs. Added
   `GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` to that step (as on
   `feat/gel-shading`). It strengthens the scan — it was aborting, i.e. not
   scanning — and adds no secret.
2. **ANDROID_SDK_ROOT** — `make test` then failed on `:app:lintReportDebug`
   because the runner exports `ANDROID_SDK_ROOT` to its own preinstalled SDK
   while the workflow only sets `ANDROID_HOME`, so AGP saw two SDK paths and
   refused. Pointed both at the pinned SDK on the three SDK-consuming steps (as
   on `feat/mechanic`, same comment).

DevOps owns the workflow; these just unblocked verification of this branch. The
real resolution is `feat/core-sim` picking up the CI foundation from trunk
(backlog item 1), after which these converge.

## Open question for the Product Lead

`BroadphaseMarginTest` is a parked failing test; per the constitution it wants a
matching backlog item. Suggest a row under item 2/12: "core-sim: restore
broadphase margin at lattice 6 (QA pin, handoff 0020)". Not filed by me —
backlog is yours.
