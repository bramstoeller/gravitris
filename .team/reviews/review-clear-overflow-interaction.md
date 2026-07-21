# Review: test/clear-overflow-interaction (PR #18)

Verdict: approve
Range: origin/main..origin/test/clear-overflow-interaction (tip 0a41aff)
Reviewer: Code Reviewer
Date: 2026-07-20

Small change: one runtime invariant + one observable-interaction test, plus the
`check()` hardening added on review. Reviewed against the code, not the summary.

## Context

Follow-up to a request (from another Code Reviewer session — not this one) for a
"clear takes precedence over overflow" test. The author correctly found that a
*literal* precedence test would be **vacuous**, and built the right thing
instead.

## Verified

- **The vacuity claim holds.** `beginClear()` (`Simulation.kt:287`) sets
  `clearTicks` and is reached only from the lock path (`advanceMechanic:179`, an
  active piece settling). `beginOverflow()` (`:487`) sets `overflowTicks` and is
  reached only from the spawn path (`:176`, no active piece). The two begin-sites
  live in mutually exclusive branches, and `advanceMechanic`'s early-returns stop
  either state from starting while the other is live. So `clearTicks >= 0 &&
  overflowTicks >= 0` is unconstructible, a reorder of the two branches is
  behaviourally invisible, and a literal precedence assertion would pass under
  either order. Testing the observable interaction (a clearing game tops out
  later than a non-clearing one, locked comparatively) is the correct substitute
  and is not physics-fragile.

- **The invariant is now executable, not just documented.** `check(clearTicks <
  0 || overflowTicks < 0)` sits at `advanceMechanic:168`, above the branch order,
  with a lazy failure message (evaluated only on violation — zero happy-path
  cost). This converts the exact future hazard the comment names — a change that
  lets the two states coexist (e.g. beginning overflow with a piece still active)
  — from "silently relies on branch order, and no reordering test could catch it"
  into "throws in CI the moment coexistence becomes possible." That is the
  durable answer to the "a reorder would pass CI" concern.

- The existing independent clear and overflow tests already drive the new
  `check()` on the happy path (both keep exactly one of the two states live), so
  it is exercised, not dead.

## Blocking

None.

## Notes (non-blocking)

- The `check()` message interpolates both counters (`clearTicks` / `overflowTicks`),
  which is the right diagnostic — if it ever fires, the values that violated the
  invariant are in the message.

## What is good

- Correctly diagnosed a vacuous test and replaced it with a behavioural one
  rather than writing coverage that proves nothing.
- Turned a load-bearing assumption into an enforced invariant at the one place
  the two states are dispatched.

## Merge verification

CI on the `check()` commit (`0a41aff`) confirmed green before this verdict was
finalised; the prior commit (`2fbd2f5`) was already green and `0a41aff` adds only
the assertion and comment.

— **Code Reviewer**
