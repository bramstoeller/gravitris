# 0023 — Backend Engineer → Product Lead (cc QA Engineer, Architect)

**Task:** Fix the lattice-6 contact penetration defect QA pinned (handoff 0020,
`reviews/qa-broadphase-margin.md`). Branch `fix/lattice6-broadphase`, PR #10.

## Headline

The code fix was **already on `main`**. There was nothing to fix in the solver.
I landed the missing regression guard (green) and corrected a stale comment, and
I proved — both directions — that the fix in place is the one QA suggested.

## What I did, and the commits

Branch `fix/lattice6-broadphase`, off `main` (rebased onto `101aea9`).

- `b4ea2b2` `test(core-sim): guard rigid contacts at every quality tier` —
  un-park QA's `BroadphaseMarginTest`, add it to `:core-sim`. It asserts a hard
  drop sinks one body < 15% of a particle diameter into another at lattice 4, 5
  and 6 alike.
- `b0719cc` `docs(core-sim): correct stale broadphase-cadence comment` —
  `XpbdSolver.deriveVelocities` still said the grid is "rebuilt per frame, not
  per substep — ADR 0003 §1". False since `0571697`. Comment-only.

(Commit hashes are pre-rebase `af8f527`/`624333b` → post-rebase
`b4ea2b2`/`b0719cc`.)

## Why there was no solver change

QA measured 43.7% penetration at lattice 6 on `feat/core-sim`, where the
broadphase was rebuilt **once per frame** (their branch, `XpbdSolver` line 86,
`grid.build` before the substep loop). The narrowphase 3×3 stencil tolerates ~1
cell of intra-frame drift; at lattice 6 a piece at `MAX_SPEED` drifts 1.39
cells/frame, outruns the stencil, and its contact is missed for a frame.

`main` no longer rebuilds per frame. Commit `0571697`
(`fix(core-sim): rebuild the broadphase every substep`, part of the contact-gap
work) moved `grid.build` **inside** the substep loop — which is precisely QA's
first suggested direction ("rebuild the broadphase per substep when the radius
is small"). Per substep the closing bound is 0.125 units against a 0.36-unit
lattice-6 cell, a 2.9x margin, so the missed pair cannot happen. QA's test was
pinned on `feat/core-sim`, before that fix merged to `main`, which is why it was
red there and is green here.

I did not take this on trust. Measured on the test's own scene:

| broadphase rebuild      | lattice 4 | lattice 5 | lattice 6 | result |
| ----------------------- | --------- | --------- | --------- | ------ |
| per-frame (pre-fix)     | 0.40%     | 0.86%     | **39.2%** | RED    |
| per-substep (`main`)    | 0.40%     | 0.86%     | **0.90%** | GREEN  |

I temporarily reverted `XpbdSolver` to a per-frame rebuild and reproduced 39% at
lattice 6 (QA measured 43.7% — same order, small scene/settle differences),
confirming both that my harness is faithful and that the per-substep rebuild is
exactly what closes the gap. Then I reverted the experiment; `main`'s solver is
untouched.

## Constraints honoured

- **Determinism, zero allocation, feel:** no main-code behaviour changed (one
  comment). `AllocationTest` and `DeterminismTest` pass. No result change, so
  **no fixture regeneration** — this is not a fixture event.
- **The QA test was not weakened.** It is un-parked verbatim (15% budget, all
  three tiers); I only updated its class KDoc to read as a guard for a fixed
  defect and recorded the measured numbers. The failure message still describes
  the per-frame regression it would catch.
- Full suite: `./gradlew :core-sim:test` → 50 tests, 0 failures, 0 skipped.

## What I deliberately did not do

- **No solver edit.** Re-fixing correct behaviour would be noise and risk feel.
- **Did not touch ADR 0003.** See open item below — it is the Architect's.

## Open item for the Architect

**ADR 0003 §1 is stale, and its "Amendment 3" does not exist in the file.**
`docs/adr/0003-...md` §1 (lines 26-31) still reads "Broadphase: uniform grid…
**rebuilt once per frame** … Rebuilt per *frame*, not per substep." The code
diverged from this at `0571697` (the `step()` comment even says "revising ADR
0003 §1"), but the ADR was never amended. Separately, QA's review and the solver
comment both cite "ADR 0003 Amendment 3" as having predicted this coupling —
there is no Amendment section in the committed ADR on `main` or on
`test/core-sim-hardening`. The architecture record disagrees with shipped code
and with two documents that reference it. I flagged this to the Architect
directly; recording it here so it is not lost. Not mine to rewrite.

## For QA

Your pinned defect is resolved on `main` by the per-substep rebuild, not by new
code from me. `BroadphaseMarginTest` is now a live guard in `:core-sim`. Please
confirm landing it green (rather than a fresh red→green) matches your intent —
messaged you directly.

## Uneasy about

- The test drops at a hard-coded `hardDropVelocity = 30f`, equal to today's
  `MAX_SPEED`. If `MAX_SPEED` is ever raised, the guard's drop speed will not
  track it and the margin assertion would test a slower-than-terminal drop.
  Low risk (both are tuned constants QA owns), noted so it is on the record.

---
*— **Backend Engineer***
