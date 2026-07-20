# 0027 — Backend Engineer to Product Lead

Two pieces this session, both `:core-sim`, both green on `./gradlew check`.

## 1. The losing condition — `feat/lose-condition`, PR #17

Commit `5753612` off `main`. **The last core piece: clears + lose = a complete game.**

`Simulation` now returns the `Phase.Overflow` and `Phase.GameOver` that already
existed in the sealed `Phase` but were never produced — so **no contract churn**,
the shape I agreed with the Frontend Engineer.

**The rule (ADR 0005):** a piece is due, and if the spawn band (`spawnBandIndex`)
is over `overflowThreshold` — or there is physically no room — a tick-counted
settle grace opens (`Phase.Overflow(remainingTicks)`) instead of spawning. If the
stack falls back below the line **and** goes quiet within the grace, play resumes
with no penalty; if the grace expires with the block still there, `Phase.GameOver`,
terminal.

- **No death by transient:** trigger and resume both read the damped `bandFill`
  and the KE quiet predicate, so a hard landing's bulge is attenuated and is
  never quiet at its peak.
- **Tick-counted (ADR 0013):** grace survives dropped ticks and backgrounding.
- **Runtime-tunable:** `overflowThreshold`, `graceTicks` joined `MechanicTuning`.
- **Zero per-frame alloc:** one reused `Phase.Overflow`, `remainingTicks` mutated
  in place (it is now a `var`, matching `Phase.Clearing` — a one-word change,
  the shape/type/field-name are unchanged).
- **Clear precedence:** `advanceMechanic` runs a clear before overflow; they
  never overlap (overflow has no active piece to lock).

`LoseConditionTest`: natural top-out via a no-clears well, resume, grace expiry
(exact tick), tick-counting whole-vs-split, both live dials, determinism to game
over.

## 2. FrameDriver per-tick input drain — PR #16, MERGED

Commit on `main` already. The Frontend Engineer, wiring the real driver, found
that `advance(delta, input)` reused one `InputFrame` across every catch-up tick —
correct for replay, but under a dropped frame it moved a drag N× and re-fired
one-shots N×. Added `advance(delta, drainTick: (InputFrame) -> Unit)` which asks
for fresh intent before each tick, plus the missing `FrameDriverTest`. Additive;
old overload kept for fixed input.

## What the Frontend Engineer needs (`feat/wire-real-game`, `a78bab7bb3170d752`)

- Read `Phase.Overflow` for the warning + countdown (`uOverflow` is in the shader
  contract), `Phase.GameOver` for the end screen. **Read `remainingTicks`, don't
  retain the `Overflow`** — mutated in place, same bargain as `Clearing`.
- Restart is app-side: reconstruct `Simulation` + `FrameDriver.resetAccumulator()`.
- Drive live input via `advance(delta, drainTick)`; pass a stable lambda.
- Sequence agreed: my lose logic (PR #17) lands, then their driver + game-over UI
  reads it. They won't wire the game-over screen until the flag returns those
  phases — no dead code.

## Deliberately not built

- **Restart method in the core** — app-side reconstruct is cleaner (deterministic,
  no in-place-reset bug surface). Will add `Simulation.restart()` only if the
  Frontend asks.
- **Scoring / difficulty ramp** — out of ADR 0005's scope; scoring formula is
  still an open UX/PL question (ADR 0005 "Open").

## Considered and rejected

- **Block-out only (spawn when the piece overlaps)** — ADR 0005 already rejected
  it (too late, no warning). I trigger on spawn-band fill first, with `canPlace`
  as a safety net so `addBody` is never called into a spot it would throw on.
- **Per-tick allocation of `Phase.Overflow`** — rejected for the reused-instance
  pattern, the only reason `remainingTicks` became a `var`.
- **Testing the state machine through a genuinely packed well** — rejected as
  physics-fragile (a settled pile leaves room at the very top). The dial-driven
  tests are reliable, and one natural top-out proves they exercise the same path.

## Uneasy about

- The "no death by transient" test drives the state machine through the
  `overflowThreshold` dial rather than a literal physics bulge; the physics path
  is covered indirectly by the damped-fill trigger and the natural top-out test.
  If QA wants a literal hard-drop-bulge-does-not-kill fixture, that is a good
  adversarial addition and I would welcome it.
