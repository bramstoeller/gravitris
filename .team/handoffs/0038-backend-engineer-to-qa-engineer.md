# 0038 — Backend Engineer → QA Engineer (+ Product Lead)

The single controlled descent (ADR 0017) is implemented across both modules and
integrated on one branch. This is the record for QA's verification pass and the
Product Lead's status.

## Branch and commits

**PR branch:** `feat/controls-descent` → `main`. Contains, in order:

- `d7e2d01` — `feat(core-sim): single controlled descent lifecycle (ADR 0017)` — **Backend Engineer** (this task).
- `e458a78` — `feat(app): single controlled descent gesture + renderer half (ADR 0017)` — **Frontend Engineer** (see handoff 0037).
- `11a6322` — `docs(team): hand off controls-descent app half (0037)` — Frontend Engineer.
- this handoff commit — Backend Engineer.

`feat/controls-descent-app` (origin) is the Frontend Engineer's integration
branch, now fully contained in `feat/controls-descent`. It is redundant but I
have **not** deleted it — remote-branch deletion is the Product Lead's call
(CLAUDE.md). Flagging it for cleanup.

**Verification:** `:core-sim:test` green (94 tests). The Frontend Engineer ran
the whole project — `make test` and `make build` both pass (`:core-sim` +
`:app`) — on the same tree.

## What the core-sim half does (ADR 0017)

POSITIONING and FALLING are collapsed into one **active** state. A piece is the
active piece from spawn until it locks; it falls under real gravity the whole
time and is steerable (`dragX`) and rotatable (`rotate`) the whole time. There
is no phase that takes control away.

- `doSpawn` places the piece and resets the lock counters itself —
  `stillTicks = 0`, `touchedTicks = -1`. It does **not** make the body
  weightless; gravity applies from the first tick.
- Deleted: the positioning window (`MechanicTuning.positioningTicks`),
  `SimState.activePiecePhase` / `positioningTicksRemaining` /
  `positioningWindowTicks`, the `PiecePhase` enum,
  `SoftBodyWorld.setBodyWeightless` and the per-particle `gravityScale` array
  (with its multiply in the hot `XpbdSolver.integrate` loop and its
  `addBody`/`removeBody` copies), and `InputFrame.drop`.
- `applyInput` no longer branches on phase: `dragX` and `rotate` both apply
  every tick a piece is live.
- **The lock rule is unchanged verbatim**: `hasSettled → lockActivePiece` —
  contact **and** kinetic energy below `lockKineticEnergy` for
  `lockDebounceTicks` running ticks, with the `lockTimeoutTicks` ceiling. The
  debounce is the settle grace.

## What QA needs to verify

New core-sim tests (`ControlledDescentTest`) already pin the behaviours; your
pass is the adversarial + on-device layer:

1. **Gravity from spawn, no hover.** `the active piece falls under gravity from
   the spawn tick, with no hover` — the piece drops within 10 ticks, where the
   old window hovered ~50.
2. **Steer AND rotate mid-descent.** `steer and rotate both take effect while
   the piece is still falling` — the reversal the client asked for. Also
   `TetrominoShapeTest.rotating a tetromino swaps its footprint` (rotate on a
   falling T).
3. **No premature lock.** `a gravity-from-spawn piece never locks in mid-air or
   on its landing squash` — the contact requirement is what makes
   gravity-from-spawn safe; the debounce holds past the impact squash.
4. **`touchedTicks = -1` sentinel.** `the lock timeout is measured from first
   contact, preserving the sentinel` — asserts `lockTick − firstImpactTick ==
   lockTimeoutTicks` exactly. Seeding `0` would lock one tick early.

### The one thing I want you to look hard at — the full-weight shove

ADR 0017 flags it and I hit it: `InterlockJitterTest` used to drive a *weightless*
positioning slide into a neighbour; that state is deleted, so it now drives a
**full-weight** piece shoved into the settled neighbour for the whole descent.
Direction-reversal counts rose from ~27 (weightless) to **97 / 92 / 55 at 4 / 8 /
16 substeps**. I raised `TREMOR_BUDGET` 60 → 130 to clear that.

This is **not** the return of the per-tick-teleport ejection bug, and the reason
is load-bearing: the ejection bug's signature was tremor *growing* with substep
count (189 / 745 / 1290). Here the count is flat-to-*decreasing* with substeps —
the sibling test `drag response does not depend on the substep count` still
pins that, unchanged, and it is the real regression guard. The absolute rise is
genuine full-weight deformation.

But "bounded and substep-independent" is not the same as "feels good." **Please
feel-check on device** that continuously shoving a full-weight piece sideways
into the stack for a whole descent reads as the material deforming, not as a
buzz, and that it does not destabilise a settled pile. If it reads badly, the
lever is `lockDebounceTicks` or `friction`/`linearDamping` — not reinstating a
control lockout (the client removed that on purpose).

### Frontend / gesture (from handoff 0037)

The recognizer test rewrite is the recogniser's own coverage, not the
adversarial pass — the tap/drag boundary and the rotate debounce want a deeper
look. On-device: a fast steering drag with a large vertical component must still
read as pure horizontal steering (vertical is ignored by contract §2), and a
release after a steer now commits nothing.

## Difficulty (for the Product Lead to watch)

Removing the time-pressure window and adding steer-during-fall gives the player
strictly more control than round-2, so placement gets easier. ADR 0017 accepts
this deliberately: the descent itself is now the steering budget (short at high
levels), the mass ramp still destabilises late-game, and `clearThreshold`
(0.80, untouched here) stays the master difficulty lever. I did not judge this
to *materially* break the game — but the "too easy" concern is now answered
through fall-speed / mass / threshold, never a control lockout. One to watch at
the next demo.

## What I deliberately did NOT do

- No hard drop / no `drop` field reintroduced (ADR 0017 non-decision;
  reversible additively later).
- Did not touch `clearThreshold` or any difficulty tuning.
- Did not delete the `feat/controls-descent-app` remote branch (PL's call).
- Did not rewrite the `:app` gesture half myself — that is the Frontend
  Engineer's domain; I coordinated it directly (handoff 0037) so the atomic
  contract change lands as one green branch rather than leaving CI red.

## Uneasy about

- The `TREMOR_BUDGET` change is the only test threshold I moved. I am confident
  it is legitimate (substep-independence holds), but it is a widened budget on a
  client-bug regression test, so I have documented the old/new numbers in the
  test and named the feel-check above. If QA's on-device pass says the shove
  buzzes, that budget is the flag that will point you back here.

---
*— **Backend Engineer***
