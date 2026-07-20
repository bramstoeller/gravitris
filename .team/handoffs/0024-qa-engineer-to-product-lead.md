# 0024 — QA Engineer → Product Lead

Date: 2026-07-20
Branch: `test/core-sim-hardening-v2` (cut fresh from `origin/main` @ `101aea9`).
Supersedes `test/core-sim-hardening` / PR #7 (closed). PR: see below.
Verdict: **pass** — core-sim hardening lands clean on `main`; the one defect I
found is already fixed on `main` and its guard lands via PR #10.

## Why this re-cut exists

My first pass branched from `feat/core-sim`, a pre-game-landing ancestor. Against
`main` that base dragged in a production commit (`dbcad18`) and conflicted in
`ci.yml`/`SimConfig.kt` — the Code Reviewer blocked PR #7 correctly. This branch
is cut fresh from current `main`, carries **only** test + docs, and conflicts
with nothing.

## What v2 lands

- `core-sim/src/test/kotlin/gravitris/CoreSimHardeningTest.kt` — 8 tests, green
  on `main`. New coverage the existing suite lacked: the empty well (`n == 0`
  early-out), the body-capacity ceiling, a 6000-frame idle hold (the flagged
  settled-pile drift — did not reproduce), determinism on a 40-body stressed
  scene, zero gravity, and a stacked-body contact across cell rows.
- `docs/test-strategy.md` — coverage map, what is deliberately not tested, how
  to run.
- `.team/reviews/qa-broadphase-margin.md` — the lattice-6 finding and its
  resolution.

Full `:core-sim` suite green on `main` with these added (build exit 0).

## The defect, and its deconfliction with PR #10

I found contacts were **not rigid at lattice 6** on `feat/core-sim`: a hard drop
sank one body 43.7% of a diameter into another (0% at lattice 4/5), because the
broadphase was rebuilt once per frame. **Already fixed on `main`**: the solver
rework rebuilds every substep (commit `0571697`, my suggested direction #1). I
verified `BroadphaseMarginTest` **green on `main`** myself (1 test, 0 skipped, 0
failures; the Backend Engineer measured lattice 6 at 0.90% vs the 15% budget).

`BroadphaseMarginTest` therefore lands as a **live regression guard via Backend
Engineer PR #10**, not here — it rides with the one-line `XpbdSolver` comment
correction it belongs next to, and including it here too would collide. This
branch deliberately omits it. All my test *content* still lands on `main`, split
across the two PRs by ownership.

## What I deliberately did NOT do

- **Did not include `BroadphaseMarginTest`** — PR #10 owns it (confirmed with the
  Backend Engineer and the Code Reviewer).
- **Did not touch production code, `ci.yml`, or `SimConfig.kt`** — v2 is
  test + docs only.
- **Did not carry the `feat/core-sim` CI fixes** — `main` already has them.

## Follow-ups (agreed with the Backend Engineer)

1. `BroadphaseMarginTest` hard-codes `hardDropVelocity = 30f` = today's
   `MAX_SPEED`; if `MAX_SPEED` rises the guard's drop speed won't track it. Worth
   deriving one from the other. Owner: whoever next touches either constant.
2. The "ADR 0003 Amendment 3" citation has no matching Amendment section in the
   committed ADR on any branch; the reasoning lives in Architect handoff 0008.
   Backend Engineer flagged it to the Architect.

## Open question

`docs/test-strategy.md` claims `:core-sim` scope; the GL/`:app` render path is
the Architect's named highest-value next QA target (handoff 0008) once a frame
renders. Not started here.
