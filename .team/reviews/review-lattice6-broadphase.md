# Review: fix/lattice6-broadphase (PR #10)

Verdict: approve
Range: `origin/main` `101aea9` .. `origin/fix/lattice6-broadphase` `7f0667a`

This is the clean resolution of the concern I raised on PR #7: land
`BroadphaseMarginTest` on `main` as a live, enabled rigidity guard rather than an
`@Disabled` pin against a defect that no longer reproduces. I verified every
claim independently against a worktree merged with current `main`.

## Blocking

None.

## Verified

- **Genuinely test + comment only against `main`.** The only production file
  touched, `core-sim/.../XpbdSolver.kt`, changes a comment block and nothing
  else — the `MAX_SPEED` cap logic (`speedSq`, the `if (speedSq > MAX_SPEED *
  MAX_SPEED)` scale) is byte-for-byte unchanged. The KDoc now says the broadphase
  rebuilds "once per substep" (matching commit `0571697`, already on `main`) and
  cross-references the test. `BroadphaseMarginTest.kt` is a new test file; the
  third change is handoff `0023`. No solver behaviour change.
- **Green on `main`.** `:core-sim:test --tests gravitris.BroadphaseMarginTest`:
  BUILD SUCCESSFUL, `tests=1 skipped=0 failures=0`. The test runs (not skipped)
  and passes. Consistent with the PR's measured 0.90% penetration at lattice 6
  against the 15% budget.
- **The guard actually catches the regression it claims to.** I reverted
  `XpbdSolver` to the pre-fix per-tick rebuild (moved `grid.build` out of the
  substep loop) in a throwaway worktree and re-ran: the test goes **red** with
  `lattice 6: a hard drop sank one body 39.2% of a particle diameter into another
  (budget 15%)`, while lattice 4 and 5 stay clean. That is the 39% the PR
  predicted, it is tier-specific as claimed, and it is deterministic. This is a
  working regression guard, not a tautology.
- The assertion, scene construction and `RIGID_PENETRATION_FRACTION = 0.15f`
  budget are unchanged from QA's original pinned test — only the framing KDoc was
  rewritten. The coverage gap it closes is real: `SolverBehaviourTest`
  non-tunnelling only exercised lattice 5, so the high-detail tier had no guard.

## Notes (non-blocking)

- Stale numbers inside the test, now that its whole purpose is to match `main`.
  The class KDoc correctly states the measured-on-`main` values (lattice 4:
  0.40%, 5: 0.86%, 6: 0.90%), but the assertion failure message
  (`BroadphaseMarginTest.kt:126`, "At lattice 4 and 5 this is 0%") and the
  `RIGID_PENETRATION_FRACTION` companion doc ("lattice 4 and 5 both measure
  exactly 0", "the lattice-6 failure (44%)") still carry the old per-frame
  figures and contradict the header. Harmless — the message only prints on
  regression and the physics story it tells is still the right diagnosis — but
  since this PR set out to de-stale exactly these comments, worth a one-line
  follow-up. Not a merge blocker.

## What is good

- The reframe is honest and precise: it names the fix commit, states the drift
  bound with margin (`MAX_SPEED * h` = 0.0625 closing 0.125 against a 0.36 cell),
  and records the measured revert-to-red result — so the next reader can
  reproduce the guard's value, not just trust it.
- Landing this enabled (not `@Disabled`) is the right call: the trunk stays
  green and the lattice-6 tier finally has the guard it never had.

---
*— **Code Reviewer***
