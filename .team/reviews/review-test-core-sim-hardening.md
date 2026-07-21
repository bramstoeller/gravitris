# Review: test/core-sim-hardening (PR #7)

Verdict: request-changes
Range: reviewed branch `c01d9a1` merged toward `main` `a4661e2`

PR #7 is described as low-risk and test-only: two test files, one `@Disabled`
pin, a strategy doc and a review doc, no production code. **Against its stated
base (`feat/core-sim`) that is true. Against `main`, where it is asked to land,
it is not** — and the one behaviour it pins no longer exists. The test *content*
is good and I verified it green on current `main`; the *branch as it stands*
must not merge to `main`. Both blockers are about the stale base, not the tests.

## Blocking

- **The branch does not merge cleanly to `main`, and is not test-only against
  it.** `git merge origin/test/core-sim-hardening` into `origin/main`
  (trial, `--no-commit`) conflicts in `.github/workflows/ci.yml` and in the
  **production** file `core-sim/src/main/kotlin/gravitris/game/SimConfig.kt`, and
  silently auto-merges two more production files,
  `core-sim/.../SimState.kt` and `core-sim/.../Simulation.kt`. The branch carries
  commit `dbcad18 fix(core-sim): correct the triangle index contract and publish
  bandCount` — a real production change that predates the landed game and is
  **not on `main`**: `main`'s `SimState.triangleIndices` still documents the old
  contract (`0 until bodyLattice * bodyLattice`, `SimState.kt:129`) and `main`'s
  `SimState` has no `bandCount` member. Failure: merging #7 as-is either stops on
  a conflict (blocks the merge), or, if auto-resolved, rewrites `main`'s
  `triangleIndices` contract doc and adds a `bandCount` interface member — a
  production API change arriving through a PR reviewed and waved through as
  "test-only, no production code." Nobody would have reviewed it as the contract
  change it is.

- **The pinned defect does not reproduce on `main`; the pin would park a green
  test.** `core-sim/src/test/kotlin/gravitris/BroadphaseMarginTest.kt:79` carries
  `@Disabled("... this test FAILS on the current solver: a hard drop at lattice 6
  sinks one body 43.7% ...")`. I removed the annotation and ran it against clean
  `main` (`a4661e2`): it **passes**, deterministically, two runs, `--rerun-tasks`
  (`tests=1 skipped=0 failures=0`). The solver was reworked when the game landed
  (`SoftBodyWorld.kt` +187, `XpbdSolver.kt` +88 between the branch base and
  `main`), and the lattice-6 penetration cliff the pin describes is gone. Failure:
  landing the pin `@Disabled` would switch **off** a lattice-6 rigidity guarantee
  that currently holds, under a note claiming a defect that no longer exists —
  and would send the Backend Engineer to fix something already fixed. The pin was
  honest against `feat/core-sim`; it is false against `main`.

## Should fix

- The branch also reverts/re-touches files that have moved on `main` outside the
  two test files: `StabilityTest.kt`, `ci.yml`, and (via `dbcad18`) `SimConfig`.
  These are out of scope for "two test files plus one pin" and are the source of
  the merge conflicts. Land only the intended test content.

## Notes (non-blocking)

- The recommended path: cut a fresh branch from current `origin/main` and bring
  over **only** `core-sim/src/test/kotlin/gravitris/CoreSimHardeningTest.kt`,
  `core-sim/src/test/kotlin/gravitris/BroadphaseMarginTest.kt`,
  `docs/test-strategy.md` and `.team/reviews/qa-broadphase-margin.md`. Drop
  `dbcad18`, the `StabilityTest.kt` edits and the `ci.yml` edits. I verified this
  set compiles and passes on `main` (see below) — it is a clean, genuinely
  test-only landing.
- `BroadphaseMarginTest` should land **enabled**, not `@Disabled`. As written it
  is green on `main` and becomes a real regression guard for lattice-6 rigidity —
  which is exactly the coverage gap the strategy doc argues for. If QA wants a
  stronger guarantee than the current `RIGID_PENETRATION_FRACTION = 0.15f` bound,
  tighten the bound; but do not ship a passing test dressed as a failing pin.
- Coordinate ordering with the Backend Engineer (`fix/lattice6-broadphase`)
  **before** either lands: the "flip the `@Disabled` when the fix goes green"
  plan is moot — `main` is already green. Messaged directly.

## What is good

- I ran the intended deliverable on clean `main` (`a4661e2` + only the two new
  test files): `:core-sim:test` BUILD SUCCESSFUL.
  `CoreSimHardeningTest` = **8 tests, 0 failures, 0 skipped**;
  `BroadphaseMarginTest` = 1 test, 1 skipped (the pin). The tests compile against
  `main`'s current API with no production changes needed — they depend only on
  members `main` already exposes (`SimConfig.PIECE_WIDTH`, `gravity`,
  `quietKineticEnergy`, `TestScenes.*`, `Simulation.ARCHETYPE_COUNT`, the
  `SimState` position/contact arrays).
- The coverage is genuinely adversarial and hits real gaps the existing suite
  left open: the empty-well no-op (`XpbdSolver` `n == 0` early-out, never
  executed), the body-capacity ceiling (`SoftBodyWorld.addBody` guard, untested),
  zero-gravity energy conservation, determinism at reference scale (40 bodies,
  split-run and fresh-`InputFrame` identity checks), and cell-boundary contact
  rigidity. Failure messages are specific and diagnostic, and tolerances are
  justified in comments rather than guessed.
- The `@Disabled` message is thorough and honest about *what it measured* — it is
  simply measuring against the wrong base. The QA instinct to park rather than
  redden the trunk is exactly right; the target just moved underneath it.

---
*— **Code Reviewer***
