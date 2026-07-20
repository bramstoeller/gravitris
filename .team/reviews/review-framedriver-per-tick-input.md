# Review: fix/framedriver-per-tick-input (PR #16)

Verdict: approve
Range: `origin/main` `86e23cc` .. `origin/fix/framedriver-per-tick-input`

Adds a live-play input path to `FrameDriver`: a second `advance` overload that
asks the caller for fresh intent *before each tick*, so a dropped frame that runs
N catch-up ticks does not replay one gesture N times. This is the driver-seam
primitive `feat/wire-real-game` builds on. Additive, well-tested, CI green — safe
to land first. One coordination item open (consumer confirmation), noted below.

## Blocking

None.

## Verified

- **Purely additive; the existing contract is untouched.** The change adds
  `advance(frameDeltaSeconds: Float, drainTick: (InputFrame) -> Unit): Float` and
  factors two private helpers (`requireRealDelta`, `finishFrame`) out of the
  existing `advance(delta, input)`. The old overload's behaviour — no clamp,
  catch-up by more ticks not bigger ones, overrun counted into `droppedTicks`,
  sub-tick `alpha` returned — is preserved, which the reused-input tests and cold
  CI confirm. No existing caller can break.
- **The live overload does what live play needs.** Per tick it clears a reused
  `tickInput` (zero per-frame allocation) and calls `drainTick(tickInput)` before
  `sim.step`. Under an N-tick catch-up the caller is asked N times, so a held drag
  arrives as the per-tick share the caller drains (1:1, per `docs/ux/gestures.md`)
  and a one-shot the caller drains once fires on exactly one tick — the rest see
  an empty frame. This is the correct fix for `InputFrame`'s "consumed on the tick
  it is read" contract across frame hitches.
- **The tests lock the property, not the implementation.** `FrameDriverTest`
  (6 tests, green): delta runs whole ticks and returns the remainder; a 100-tick
  hitch runs the ceiling (8) and counts the rest (92) rather than clamping; both
  overloads reject non-finite/negative delta; `drainTick` is called exactly once
  per tick each time with a *cleared* frame (a one-shot set on one tick does not
  repeat on the next); and the decisive comparison — the same 0.3 drag moves the
  piece 0.9 via the reused overload (3×) but 0.3 via drain-once (1×), asserting
  `movedOld > movedNew * 2`. That last test is exactly the N× gesture
  multiplication the change exists to prevent.
- **Wall-clock honesty is intact.** Both overloads share the no-clamp / count-the-
  overrun path (ADR 0013), so the live path inherits the same "frames may drop,
  time is never dilated" guarantee, not a separate one that could drift.
- CI `build-and-test` pass on the PR.

## Consumer confirmation — CONFIRMED (seam closed)

- The Frontend Engineer confirmed the seam from the consumer side, measured
  against their real wiring on `feat/wire-real-game`:
  `advance(delta, drainTick: (InputFrame) -> Unit)` is exactly the shape their
  driver calls. `GameSession.advance` delegates straight to it; `GameRenderer`
  holds a **stable field lambda** (`private val drainTick = { intent.drainInto(it) }`),
  and `PlayerIntent.drainInto` fills the passed frame and resets its own
  accumulator, so tick 1 gets the gesture and ticks 2..N get an empty frame — the
  1:1 mapping this overload exists to guarantee. Both my caveats are satisfied
  (stable lambda, frame not retained past the callback), and fill-the-passed-frame
  is confirmed the right shape (not return-an-`InputFrame`, not a nullable
  no-intent signal), matching `PlayerIntent`'s existing allocation-free
  `drainInto`. The returned alpha feeds `VertexFill`/`mesh.upload`.
- Ordering resolved as planned: #16 landed first as the primitive
  (`main` `601dcd0`), and the wiring builds on it. No follow-up to #16 needed.

## What is good

- The class KDoc is unusually good at saying *why the other overload is wrong for
  live play* (per-frame drag would move N×, one-shot would fire N×) rather than
  just documenting what each does — so the next caller picks the right one. The
  reused-`tickInput` + stable-`drainTick` design keeps the live path zero-alloc,
  and the test proves the fix by contrast against the old behaviour rather than in
  isolation.

---
*— **Code Reviewer***
