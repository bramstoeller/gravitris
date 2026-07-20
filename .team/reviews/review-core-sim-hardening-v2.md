# Review: test/core-sim-hardening-v2 (PR #13)

Verdict: approve
Range: `origin/main` `101aea9` .. `origin/test/core-sim-hardening-v2` `92b1b9c`

The clean re-cut of PR #7, branched fresh from current `main`. This is the
landing I recommended when I blocked #7: the good test content, off the right
base, genuinely test + docs only, with `BroadphaseMarginTest` ceded to #10 so the
two PRs do not both claim it.

## Blocking

None.

## Verified

- **Test + docs only, additive, off current `main`.** Four files, all new:
  `core-sim/.../CoreSimHardeningTest.kt`, `docs/test-strategy.md`,
  `.team/reviews/qa-broadphase-margin.md`, handoff `0024`. No production code, no
  `ci.yml`, no `SimConfig.kt`, and none of `dbcad18` — exactly the divergence that
  sank #7 is absent here. Merges CLEAN.
- **Green on `main`.** Built against the actual branch:
  `:core-sim:test --tests gravitris.CoreSimHardeningTest` BUILD SUCCESSFUL,
  `tests=8 skipped=0 failures=0`.
- **Deconflicted with #10.** `BroadphaseMarginTest` is intentionally not in this
  PR; it lands via #10 with the `XpbdSolver` comment fix. No file is claimed by
  both branches — I confirmed #13's diff contains no `BroadphaseMarginTest.kt`.
- The coverage is the same adversarial set I praised on #7 and it stands on its
  own on `main`: empty-well `n == 0` early-out, body-capacity ceiling
  (`SoftBodyWorld.addBody` guard), 6000-frame idle-hold convergence, 40-body
  determinism with split-run and fresh-`InputFrame` identity checks, zero-gravity
  energy conservation, and cell-boundary contact rigidity. Depends only on APIs
  `main` already exposes.

## Notes (non-blocking)

- Handoff numbering collision: this PR adds
  `.team/handoffs/0024-qa-engineer-to-product-lead.md` while PR #9 adds
  `.team/handoffs/0024-security-engineer-to-product-lead.md`. Different filenames,
  so no git conflict, but two handoffs share sequence number 0024. Cosmetic;
  whoever merges second may want to renumber to keep the log monotonic.

## What is good

- QA took the block on the right terms, re-cut off `main`, verified the suite
  green independently, and split the deliverable by ownership across #10 and #13
  rather than letting either file be claimed twice. This is the clean landing #7
  should have been.

---
*— **Code Reviewer***
