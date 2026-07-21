# 0017. Single controlled descent — steer and rotate under gravity until contact

Status: accepted
Date: 2026-07-21
Author: Architect
Supersedes: 0016 (piece lifecycle POSITIONING→FALLING and its input gating)

Reviewed and agreed as an artifact before any code: the **Backend Engineer**
(lifecycle owner, `:core-sim`) and the **Frontend Engineer** (gesture-contract
consumer, `:app`). Both verdicts were AGREE-WITH-CHANGES; every requested change
is folded into this ADR and into `docs/contracts.md` §2.

## Context

The client field-tested the round-2 build and rejected its control scheme, in
their words (translated):

> "It's not good that you can only move them for a short moment and then not
> anymore. You should be able to move left/right AND rotate until they hit
> another block. And keep the gravity acceleration."

This **reverses** the round-2 instruction that ADR 0016 was built to satisfy —
*"veel minder lang kunnen bewegen"* (a much shorter movement window). That
window was deliberate time-pressure, half of the answer to the round-1 "too
easy" complaint (shapes being the other half). The client has now played it and
changed their mind: the short lockout does not feel good. This ADR records the
reversal explicitly so nobody re-derives the old constraint from ADR 0016 and
reinstates a window the client removed on purpose.

What ADR 0016 built, and what is now wrong with it:

- **POSITIONING**: the piece is parked at the spawn row, gravity suppressed
  (weightless — a per-particle `gravityScale` of 0 in `SoftBodyWorld`, applied
  in `XpbdSolver.integrate`; **not** the kinematic `invMass = 0` that ADR 0016's
  own prose mis-stated), slid left/right only, for a short tick-counted window
  (`MechanicTuning.positioningTicks`). A `drop` releases it early.
- **FALLING**: on release or window expiry, gravity turns on; the player may
  **rotate** but no longer slide, until the piece settles and locks.

The client is rejecting the phase where control is taken away — both the
horizontal lockout on release and the short window before it. They want to
steer **and** rotate for the whole descent, under real gravity, until the piece
contacts other material (or the floor) and settles.

What is kept from ADR 0016, unchanged, because the client did not object to it
and it is correct:

- Gravity acceleration during the fall (*valversnelling*) — explicitly
  re-confirmed by the client.
- The soft-body squash/settle/lock physics (ADR 0001, 0003, 0005, 0012).
- Snap-rotation semantics: exact quarter turn `(x, y) → (y, −x)` about the
  centroid, isotropic constraints hold the turn for free, a rotation that would
  overlap settled material is rejected outright, and a rotation injects no
  velocity (`applyRotate` rewrites only `framePrev`/`substepPrev`). ADR 0016's
  rotation decision stands verbatim; only *when* rotation is allowed changes
  (now: the whole descent).

## Decision — one player-controlled descent

Collapse the two per-piece phases into a **single active state**. A piece is
either the active piece — falling under gravity and fully steerable — or it is
not. There is no phase in which the core takes control away.

### Lifecycle

1. **Spawn.** `doSpawn` places the piece at the spawn row, makes it the active
   piece, and resets the lock counters **itself** — `stillTicks = 0` and
   `touchedTicks = -1`. The `-1` is load-bearing: it is the "has not touched
   anything yet" sentinel, and seeding `0` instead would start the lock timeout
   one tick early (`hasSettled` does `touchedTicks++` on first contact). Today
   this reset lives in `enterPositioning`; when that is deleted, `doSpawn` must
   carry it. `doSpawn` does **not** enter a positioning window and does **not**
   make the body weightless. Gravity applies from the first tick; the piece
   begins to fall and accelerate immediately.

2. **Descent (the whole of it).** Every tick the active piece is live, the core
   applies both intents: `dragX` steers it horizontally (clamped to the well,
   kinematic — see `applyDrag`), and `rotate` turns it a quarter step (rejected
   if it would overlap). Both apply until the piece locks. There is no separate
   FALLING/SETTLING distinction: deform, settle and lock all happen while the
   piece is still steerable.

3. **Lock.** The piece locks by the **existing** `hasSettled` → `lockActivePiece`
   rule, unchanged: it must be **in contact** with something, and its kinetic
   energy must stay below `lockKineticEnergy` for `lockDebounceTicks` running
   ticks — with a `lockTimeoutTicks` ceiling on how long a piece may be in
   contact before it locks regardless. `lockDebounceTicks` **is** the settle
   grace the client's model needs: a soft-body body wobbling on impact is not
   quiet, so the debounce prevents a premature lock on the instantaneous
   stillness at the bottom of a squash. On lock the active piece is released and
   the next spawns — straight into the same live-and-falling state.

The contact requirement is what makes gravity-from-spawn safe: a piece spawns at
zero velocity and touching nothing, so `hasSettled` returns false and it cannot
lock in mid-air. Gravity does the rest.

This is closer to classic falling-block steering, but the piece is a soft body
under accelerating gravity, not a rigid shape on a lock-delay timer.

### Gravity suppression is deleted, not just unused

The weightless mechanism ADR 0016 used to hold a piece still during POSITIONING
is removed entirely, in the **same** change as the collapse so trunk never
carries an orphan (Backend Engineer's call, confirmed against the code):

- `SoftBodyWorld.setBodyWeightless` loses its only caller and is deleted.
- With it, the per-particle `gravityScale` array becomes constant `1f`
  everywhere — a dead array, a dead multiply in the hot `XpbdSolver.integrate`
  loop, and dead copies in `addBody`/`removeBody`. All are removed. CLAUDE.md
  forbids dead code and unused abstraction, and a documented weightless
  mechanism with no caller is a trap for the next reader.

This is a small hot-loop simplification (one fewer multiply per particle per
substep) and is **determinism-neutral**: fewer operations, identical arithmetic.
Gravity is on for every active piece, always, from the spawn tick.

The spawn path is not new solver physics: `buildBenchmarkScene` already drops
its pieces under plain gravity with `gravityScale` at its `1f` default — the new
`doSpawn` is that same path with the dealer attached, which is why the model
survives the solver without a spike.

### Difficulty: the descent replaces the timer

ADR 0016 introduced the positioning window as explicit time-pressure. Removing
it removes that lever, and adding steer-during-fall gives the player strictly
more control than round-2's rotate-only fall — so, all else equal, placement
gets easier. Two things carry the difficulty instead, and one lever remains:

- **The descent is the steering budget, and it shrinks with level.** Under
  accelerating gravity the player only has until the piece lands to steer it.
  That budget is long and forgiving early (slow fall — good for onboarding) and
  short and demanding at high levels (fast fall) — the pressure is now intrinsic
  to the physics rather than an artificial countdown, which is exactly the kind
  of difficulty the brief asks for ("difficulty emerges from the physics").
- **Mass ramp** still makes the growing stack sag and shift, so late-game
  placement is unstable regardless of steering freedom.
- **`clearThreshold` (currently 0.80, a live `MechanicTuning` dial) is the
  master difficulty lever** and is untouched by this change. If playtests show
  the game is too easy without the window, the response is a steeper fall-speed
  ramp, a higher clear threshold, or a steeper mass ramp — **not** reinstating a
  control lockout the client removed. Flagged for the Product Lead to watch at
  the next demo.

### No hard drop

Round-2 (ADR 0016) removed the hard-drop because "release *is* the drop." Under
this model there is no release-to-drop either — gravity is always on and the
piece falls on its own. A flick-down hard-drop is **not** reintroduced:

- The client did not ask for it, and the standing instruction is to keep
  existing behaviour (no hard drop) unless it conflicts.
- It slightly *conflicts* with the client's actual complaint: a "slam it down
  now" gesture cuts the steering budget, which is the opposite of "let me move
  it more."
- It would reintroduce a gesture-disambiguation cost (a vertical flick vs. the
  vertical component of a diagonal steering drag) that the new recognizer
  otherwise sheds.

This is a deliberate non-decision, cheaply reversible: `InputFrame` could regain
a `hardDrop` field additively later without touching the lifecycle. Left out
until the client asks.

## Decision — the input contract

`InputFrame` becomes `{ dragX: Float, rotate: Boolean }`. The `drop` field (and
with it every trace of release-to-drop) is removed, along with the phase gate in
`applyInput`. This crosses the `:core-sim`↔`:app` boundary (`docs/contracts.md`
§2/§5) and is authorized here by the Architect. `docs/contracts.md` §2 is
rewritten to define the new semantics in full (see that section — the phase
distinction is not merely deleted; the single-descent meaning of every field is
specified).

### The gesture conflict ADR 0016 solved no longer exists

ADR 0016's recognizer had to emit `drop` on every pointer-up *and* `rotate` on a
tap, because "release = drop" and "tap = rotate" were both a pointer-up, and it
left the core to disambiguate them by phase. Under this model there is no
release-to-drop, so a pointer-up means exactly one thing:

- **Horizontal drag past the touch slop → `dragX`** — continuous steering, the
  whole descent.
- **Tap (pointer-up within the slop) → `rotate`** — the whole descent.
- **Pointer-up ending a drag → nothing.** The steering already happened
  continuously; the release commits nothing.

The recognizer therefore becomes *simpler*, not more complex: the always-emit
`drop` line is deleted, the phase-agnostic disambiguation is gone, and the core
no longer gates input by phase (there is no phase). The tap→`rotate` path,
rotate debounce, multi-touch handling, slop reuse, timestamped historical-sample
feeding and the 1:1 `worldPerDp` drag mapping are all unchanged.

### SimState loses the positioning fields

`activePiecePhase` (`PiecePhase`), `positioningTicksRemaining` and
`positioningWindowTicks` are removed from `SimState`, and the `PiecePhase` enum
is deleted. `MechanicTuning.positioningTicks` is removed. `:app` removes
`PositioningUrgency` and the positioning-countdown draw in `GameRenderer` — with
no window there is nothing to count down. (These fields were added to code by
ADR 0016 but were never documented in `docs/contracts.md` §3, so §3 needs no
edit for their removal; the removal is recorded here.)

## Alternatives considered

- **Keep POSITIONING but make its window very long / soft.** Rejected: the
  client did not ask for a longer window, they asked for the lockout to *go*.
  Any window is still a phase where horizontal control is later taken away, and
  a longer one just delays the same complaint. It also keeps the weightless
  freeze and the phase-gated input machinery for no benefit.

- **Keep a `drop`/hard-drop as a "commit now" accelerator.** Rejected for this
  dispatch (see "No hard drop"): unrequested, mildly counter to the complaint,
  and it re-adds a gesture-disambiguation cost. Recorded as reversible rather
  than closed.

- **Suppress locking while the player is actively giving input** (any `dragX`
  or `rotate` this tick resets the settle debounce), to literally guarantee
  "control until *you* stop." Rejected: it lets a player hold a piece
  indefinitely by wiggling it, which reintroduces the unbounded-wait problem the
  `lockTimeoutTicks` ceiling exists to prevent, and it couples the lock rule to
  input in a way that is hard to reason about deterministically. The chosen rule
  is simpler and honest to "move until it hits another block": once it hits and
  goes quiet, it locks; the ceiling bounds any nudging. If playtests show pieces
  locking while players still want to nudge, the fix is a longer
  `lockDebounceTicks` (a dial), not input-coupled locking.

- **A new `PiecePhase { FALLING }` single-valued enum, kept for symmetry.**
  Rejected: a one-valued enum is dead abstraction (CLAUDE.md: no speculative
  extension points). A piece is active or it is not; `activePieceBody >= 0`
  already carries that.

## Consequences

- **What becomes easy.** The control model matches a mental model every
  falling-block player already has — steer and turn until it lands. Both the
  core (`applyInput` loses its phase branch; `doSpawn` loses the positioning
  setup) and the shell (recognizer loses the drop machinery; renderer loses the
  countdown) get smaller. `InputFrame` shrinks to two fields.

- **What becomes hard / what we live with.** The explicit time-pressure lever
  from ADR 0016 is gone; if the client's "too easy" concern returns, it must be
  answered through fall-speed ramp, mass ramp or `clearThreshold`, and that
  needs a playtest to tune (flagged to the Product Lead). A piece can now be
  steered while it is already resting on the stack; the `lockTimeoutTicks`
  ceiling bounds that so it cannot be surfed forever, but a piece may lock under
  a slow finger once it is quiet and in contact — accepted as correct "it
  settled" behaviour.

- **New interaction to feel-check (for QA).** The drag clamp is against the well
  walls only, never against neighbouring bodies, so for the whole descent a
  player can continuously shove a **full-weight** piece sideways into the stack.
  The solver keeps this bounded — this is exactly why gravity suppression was a
  `gravityScale` multiplier and not `invMass = 0`, so a piece pushed into the
  pile is not an immovable shover — but ADR 0016 only ever allowed a *brief,
  weightless* slide, whereas this is a full-weight continuous push. Bounded, but
  genuinely new; QA should feel-check that shoving into the stack reads well and
  does not destabilise a settled pile.

- **Overflow moves in the safe direction.** Under ADR 0016 a piece hovered
  weightless in the spawn band for the whole positioning window, occupying spawn-
  band fill. Under gravity-from-spawn it falls out of the spawn band immediately,
  so the `canSpawn` spawn-band fill clears *faster* and overflow is if anything
  less likely to trip spuriously. No change to the overflow rule (ADR 0005).

- **Migration surface — flagged so QA and the engineers are not surprised.** The
  collapse is mostly deletion, but it breaks fixtures and prose that positioning
  built:
  - Core: delete `enterPositioning`, `advancePositioning`, `releaseToFall`, the
    `positioning`/`positioningRemaining` fields and the `addPositioningPiece`
    harness; trim `clearActivePiece`'s positioning reset; reword the
    `slamActivePiece` (test/probe) doc that leans on "release is the drop."
    `MechanicTest` / `DeterminismTest` and any contract test that drives
    positioning, `drop`, or reads `activePiecePhase` must be rewritten.
  - Shell: the `GestureRecognizer` KDoc is contract-bearing and now false (the
    "phase-agnostic, every pointer-up emits drop" sections) — it is rewritten as
    part of the change, not left stale. `GestureRecognizerTest` cases that assert
    `drop` are rewritten by QA, not deleted: the "a drag release emits nothing"
    assertion is *added* (repurposing the old drop-only case), because that
    single line pins the client's "no phase where control is taken away." Remove
    `PositioningUrgency`, `gl/UrgencyBar`, the urgency-bar wiring in
    `GameRenderer`, and the `Tunables.POSITIONING_BAR_*` constants.

- **Determinism holds (ADR 0006).** The lock is tick-counted, rotation is exact,
  gravity-from-spawn removes a state (the weightless freeze) rather than adding
  one. Nothing here reads a clock or a transcendental. `DeterminismTest` covers
  the running game and needs its positioning-specific assertions removed with
  the feature.

- **Zero per-frame allocation holds.** No new per-tick state; the change is net
  removal plus the unchanged rotation scratch.
