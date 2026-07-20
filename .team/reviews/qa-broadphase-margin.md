# QA review — broadphase margin at the lattice-6 quality tier

Reviewer: QA Engineer. Date: 2026-07-20.
Finding raised on: `feat/core-sim` (pre-game-landing). **Resolved on `main`.**
Verdict: **defect was real, is already fixed on `main`; a live regression guard
now protects it (Backend Engineer PR #10).**

## Summary

Hunting the pre-game-landing solver (`feat/core-sim`), I found that **contacts
were not rigid at lattice 6** — the ADR 0009 high-detail tier. A hard drop at
terminal speed sank one body **43.7% of a particle diameter** into another at
lattice 6, against **0%** at lattice 4 and 5. Root cause: the broadphase was
rebuilt **once per frame**, and at lattice 6 the shrunken particle radius let a
piece at `MAX_SPEED` drift 1.39 cells in a frame — past the 3×3 stencil — so its
contact was missed for the frame it arrived and the deep overlap was expelled
violently on the next rebuild.

**This is already fixed on `main`.** The solver rework rebuilds the broadphase
**every substep** (`XpbdSolver.step`, main line 152; commit `0571697`
"fix(core-sim): rebuild the broadphase every substep") — my suggested direction
#1. Per substep the drift bound holds with a ~3.6× margin.

## Verified, not taken on trust

Per the constitution I measured rather than believing the report:

- Read `main`'s `XpbdSolver.kt`: `grid.build(...)` is inside the substep loop
  (line 152), and the comment documents exactly this failure —
  "interpenetrated up to 0.39 of a particle diameter with no contact ever being
  detected ... the signature of a missed pair".
- Ran `BroadphaseMarginTest` **enabled** against clean `main`: **1 test, 0
  skipped, 0 failures — green.** The Backend Engineer independently measured the
  three tiers on `main` at 0.40% / 0.86% / 0.90%, all under the 15% budget, and
  reproduced a 39.2% failure by temporarily reverting to a per-frame rebuild
  (I measured 43.7% on the pre-rework solver — same order, small scene/settle
  difference).

So the finding was genuine on the per-frame solver and the fix on `main` is
correct. What was a red pin becomes a **green regression guard**: the
high-detail tier previously had no non-tunnelling coverage (the existing test
only ran lattice 5), which is why the regression could have re-entered unseen.

## Where the guard lives

`BroadphaseMarginTest` (`a hard drop stays rigid at every quality tier`, 15%
budget across lattice 4/5/6) lands via **Backend Engineer PR #10**
(`fix/lattice6-broadphase`), **not** this QA branch — it rides with the one-line
`XpbdSolver` comment correction it belongs next to, and the Backend Engineer had
already un-parked it verbatim and recorded the measured numbers when we compared
notes. Landing it green (rather than staging a fresh red→green) is the right
outcome and matches my intent: the defect is fixed; the test's job now is to
keep it fixed. This QA branch (`test/core-sim-hardening-v2`) therefore lands only
`CoreSimHardeningTest`, this review, and `docs/test-strategy.md`.

## Two follow-ups (agreed with the Backend Engineer)

- **`hardDropVelocity = 30f` is hard-coded to today's `MAX_SPEED`.** If
  `MAX_SPEED` is ever raised the guard's drop speed will not track it and the
  margin it proves shrinks silently. Worth deriving the drop speed from
  `MAX_SPEED` so the guard scales with the thing it guards. Recorded for whoever
  next touches either constant.
- **The "ADR 0003 Amendment 3" citation** (in the original write-up and the
  solver comment) has no matching Amendment section in the committed ADR 0003 on
  any branch. The reasoning it refers to is real — it is in Architect handoff
  0008 — but the ADR was never amended to carry it. The Backend Engineer flagged
  it to the Architect; the citation should point at handoff 0008 until the ADR
  catches up.

## What I could not test

- **Cross-device bit-identity** (ADR 0006): no ARM device in reach; the JVM
  proves within-JVM determinism only. Honest gap, tied to the on-device
  benchmark blocker.
- **A full particle-particle tunnel-through**: could not construct one on either
  solver. Solid bodies are ≥ 4 rows thick and `MAX_SPEED` caps closing speed, so
  the failure mode was deep penetration, not pass-through.
