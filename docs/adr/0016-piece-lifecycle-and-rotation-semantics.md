# ADR 0016 — Piece lifecycle (position → fall) and rotation semantics

Date: 2026-07-21 · Status: superseded by 0017 · Author: Backend Engineer

> **Superseded by [ADR 0017](0017-single-controlled-descent-control-scheme.md)
> (2026-07-21).** The client field-tested this scheme and rejected the
> positioning lockout: they want to steer *and* rotate for the whole descent
> under gravity, with no phase where control is taken away. The lifecycle below
> (POSITIONING→FALLING, the gravity-suppress freeze, the tick-counted window,
> the phase-gated input and the `drop` intent) is replaced by a single
> controlled descent. **The rotation semantics in this ADR still hold verbatim**
> — only *when* rotation is allowed changed (now: the whole descent).

## Context

The client changed the control scheme after playing (this reverses the earlier
"pieces hang until you drop them, no fall speed" instruction). The new model,
in the client's words, translated:

1. A new piece appears at the top; the player slides it **left/right only** to
   aim it.
2. The player can move it for only a **short** window — "much less long able to
   move" — which is deliberate time pressure, the other half of the difficulty
   fix (shapes being the first half).
3. On release it **falls under real gravity** (*valversnelling*), not a gentle
   hang.
4. During the fall the player may still **rotate** it (tap), but no longer
   slide it.
5. It lands, deforms, settles, locks — then the next piece.

This makes the piece lifecycle a small state machine with input that means
different things in different phases. It is expensive to reverse and the client
feels every part of it, so it is recorded here. Rotation semantics for a
soft-body tetromino are folded in — they are the same "the client feels it"
class of decision.

## Decision — the lifecycle

Two per-piece phases, orthogonal to the game-level `Phase`
(Playing/Clearing/Overflow/GameOver, unchanged):

- **POSITIONING.** The piece is parked at the spawn row with **gravity
  suppressed**; the player slides it horizontally only. A **tick-counted**
  window (`MechanicTuning.positioningTicks`, a live dial, ADR 0013 — never
  wall-clock) counts down. Slide (`dragX`) is applied; rotate is ignored; a
  `drop` intent releases the piece immediately.
- **FALLING.** On release (a `drop` this tick) or on the window expiring,
  gravity turns on and the piece falls under the normal solver. Rotate (tap) is
  applied; slide and drop are ignored. Deform, settle, and lock all happen here
  — the existing `hasSettled` → `lockActivePiece` path is unchanged. On lock the
  active piece is released and the next spawns into POSITIONING.

Gravity is suppressed during POSITIONING by making the active body **kinematic**
— its particles' `invMass` set to 0, restored to `1/mass` on release. This
reuses the solver's existing `invMass == 0` short-circuits in `integrate` and
the contact kernels; it adds **no** branch to the hot loop and no new per-particle
state. Considered a per-body "frozen" flag gating gravity in `integrate` and
rejected: it puts a new test in the innermost loop for a state the solver
already models.

### Input gating lives in the core

The Frontend's gesture recognizer stays dumb and phase-agnostic: pointer-up
emits `drop`, a tap (within touch-slop) also emits `rotate`, horizontal drag
emits `dragX`. The **core** decides what they mean by phase — a tap in
POSITIONING drops, a tap in FALLING rotates — so "the core decides what a
gesture means" (`docs/contracts.md` §2) holds and no phase signal has to reach
the recognizer, keeping it JVM-testable. Agreed with the Frontend Engineer.

### What `:app` reads (additive to `SimState`, no boundary cross per §5)

- `activePiecePhase: PiecePhase { POSITIONING, FALLING }` — **defaults to
  FALLING whenever `activePieceBody < 0`**, never POSITIONING, so a stray
  `dragX` can never slide a piece that is not being positioned. Guarantee:
  `activePiecePhase == POSITIONING ⇒ activePieceBody ≥ 0`.
- `positioningTicksRemaining: Int` — ticks left in the window; **0 outside**
  POSITIONING.
- `positioningWindowTicks: Int` — the current window length, so the shell draws
  a `remaining/window` 0..1 countdown without re-deriving it (re-derived copies
  drift, `docs/contracts.md`).

No SETTLING phase: deform/settle/lock all occur inside FALLING (rotate stays
allowed until lock), so FALLING already means what a SETTLING value would.

### InputFrame signature change

`InputFrame` becomes `{ dragX: Float, rotate: Boolean, drop: Boolean }`. The
old `hardDrop` + `hardDropVelocity` and all hard-drop machinery are removed:
release *is* the drop and the fall is real gravity, so a flick-boost is
redundant (plain gravity). This crosses the module boundary (`docs/contracts.md`
§2/§5) and is signed off by the Architect.

## Decision — rotation semantics

Rotation **snap-rotates the particle positions** by an exact quarter turn
`(x, y) → (y, −x)` about the piece centroid — the existing `applyRotate`, which
already generalises to a tetromino because it loops over `particlesPerBody`.

The soft body holds its new orientation *for free*: the distance and area
constraints are **isotropic** — they constrain lengths and signed areas, both
of which a rotation preserves — so a rotated configuration is *already a rest
state*. There is no separate "rest shape" to rotate and no material spring-back.
The quarter turn is exact (no `sin`/`cos`, ADR 0006) and deterministic, and
cannot drift under repeated application.

Considered and rejected: rotating a target rest shape and letting the physics
drive the material toward it (adds a controller and a transient the player sees
as mush), and snap-rotating only after re-solving (no benefit — the snap is
already a rest state).

A rotation that would overlap settled material is **rejected outright** — the
saved positions are restored and the piece simply does not turn. This is the
standard behaviour when a rotation is blocked, and it is the existing fix for
the launch-energy bug (seeding an overlap turns into ejection velocity). During
the fall the piece keeps its downward velocity through a rotation: `applyRotate`
rewrites only `framePrev`/`substepPrev`, never `velX`/`velY`.

## Consequences

- Time pressure in POSITIONING plus real gravity in FALLING answer the "too
  easy" complaint alongside the shapes.
- Determinism holds: the window is tick-counted, rotation is exact, the freeze
  is a deterministic `invMass` toggle. `DeterminismTest` covers the running game
  including positioning, falling, and rotation.
- Zero per-frame allocation holds: the freeze/restore is an in-place loop, the
  rotation scratch is sized once at `4·L²`.
