# 0013 — Architect → Product Lead

Date: 2026-07-20
Branch: `chore/architecture` @ `993c307`. **Pushed to `origin`. Not merged.**
Responds to the client's no-slow-motion requirement. Follows 0012.

⚠ **Numbering collision:** my previous handoff and the frontend engineer's are
both `0012`. Mine is cited by hash in my commit messages, so I have not renamed
it; one of the two needs renumbering and I'd suggest it not be the one already
referenced. Third collision in this project — the sequence needs an owner.

## It was worse than a gap — the clamp *was* the slow motion

You asked what happens when a frame overruns, and noted ADR 0006 doesn't say.
It does say, and what it says is the bug:

```
accumulator += min(frameDelta, MAX_FRAME_DELTA)   // "clamp: no spiral of death"
```

**That clamp discards wall-clock time by construction.** On a device that
overruns, real time passes which the simulation never receives, so the simulation
clock falls behind the wall clock — permanently, and progressively. That is
exactly the failure mode the client ruled out. I introduced it to prevent a death
spiral and did not notice what I was paying for it.

So this is a defect in ADR 0006, not an unspecified corner of it. **ADR 0013
supersedes that clamp; the rest of ADR 0006 — fixed timestep, determinism, 60Hz
render — stands unchanged.**

## The constraint that decided everything

Worth giving you, because it bounds what we can ever promise:

Solver cost per wall-second is `tickRate × substeps × costPerSolve`. Stability
depends on the substep size `h = 1 / (tickRate × substeps)`.

**These are the same quantity.** 30Hz×16 substeps and 60Hz×8 substeps have
identical cost *and* identical stability. You cannot buy CPU time without
enlarging `h`, and enlarging `h` walks toward the measured stability cliff.

With particles pinned (ADR 0011) and substeps pinned (ADR 0003), **there is no
honest degradation path that preserves both wall-clock time and physics
fidelity.** On hardware too slow for the pinned configuration, something must
give — and the client has told us it is not the wall clock.

## What I decided

1. **The delta is never clamped.** Falling behind is repaid by running more ticks.
2. **Catch-up never enlarges the step** — it runs N *normal* 1/60 s ticks. This
   answers your physics-blow-up question definitively: `h` is invariant under
   every policy in this ADR, so **nothing in overrun handling can destabilise the
   solver.** The cost of catch-up is CPU time and judder, never physics.
3. **Backgrounding pauses and resets** rather than accumulating. This removes the
   main reason the clamp existed — the ten-minutes-backgrounded case is now
   handled by the pause that ADR 0010 already requires.
4. **`maxCatchupTicks = 8` is a counted safety valve**, not routine behaviour.
   Beyond it, excess time is discarded — **the one and only place wall-clock
   honesty breaks** — and it is exposed as `droppedTicks` in `SimState`. Non-zero
   means the device is below the hardware floor, not that the game hiccuped.
   Bounded rather than unbounded because unbounded catch-up death-spirals into a
   freeze, which serves the client worse than judder.
5. **The policy lives in `:core-sim` as a `FrameDriver`**, not in the render loop.
   That makes "wall-clock time is never dilated" a **JVM test** — feed it a
   pathological delta sequence, assert simulated time equals wall-clock time —
   rather than a thing someone remembers. You asked that nobody later optimise
   this back into a dilating accumulator; this is the enforcement.

**Render-side scaling remains the primary mitigation for weak hardware**, and it
already exists (ADR 0009). This matters more than it sounds: the likely bottleneck
on an older phone is the **fragment shader, not the solver**, so reducing
resolution and shader quality frees budget, the simulation holds 60 ticks/second,
and the frame rate drops — precisely the trade the client asked for.

## Determinism — the answer is not the intuitive one

You were right to press on this. `sim.step()` stays pure and deterministic; what
varies with wall clock is *how many ticks fall in a rendered frame* and *which
tick an input lands on*.

**Replay is a fixed-tick-sequence property, not a real-time property.**

- A recorded `(tick index, InputFrame)` sequence replays bit-identically anywhere.
- A recorded **real-time session does not reproduce across devices.**
- **QA fixtures must be tick-indexed, never wall-clock-indexed.** A fixture that
  records "input at t = 1.234 s" is not reproducible and will flake. This is the
  concrete instruction for the fixtures being built right now, and it needs to
  reach QA before they invest.
- A session where `droppedTicks > 0` is not replayable at all, since ticks that
  never ran cannot be replayed. Another reason that path is counted rather than
  silent.

## What the player sees

At overrun: fewer rendered frames, the piece advancing in larger visual jumps,
motion still interpolated within each frame. **Judder.** Wall-clock timing is
unaffected — falls, grace windows and clear animations all take the same real
time as on a fast device, because they are tick-counted and ticks track the wall
clock. No physics discontinuity, per point 2.

At the floor: the game additionally begins running slower than real time, because
no honest option remains. It is counted so we can tell the difference between
"struggling" and "below the floor".

## Frontend impact — small and specific

Contract table added at `docs/contracts.md` §4c. In short: pass the **real** frame
delta (do not clamp, smooth or cap it — that reintroduces the defect), call
`resetAccumulator()` on resume, pause on background, and do not assume one tick
per rendered frame — there may be 0, 1 or several. Nothing else changes.

## Reach — and the honest sentence for the client

Solver cost is **36.7% of one core** on the reference device.

| device slower by | solver share of one core | left for render + OS |
| ---------------- | ------------------------ | -------------------- |
| 1.0x (reference) | 36.7% | 63.3% |
| 1.6x | 58.8% | 41.2% |
| 2.0x | 73.4% | 26.6% |
| 2.7x | 99.9% | 0.1% |

Devices up to **~1.6x slower** than the Fairphone 6 should hold 60 ticks/second
with render-side scaling doing the work. **~2.7x slower** is the wall where the
solver alone consumes a core.

**The sentence I think the client is owed:** their requirement is satisfied in the
sense they meant — no slow motion, degrade via frame rate — but *"overal"* has a
floor. On a phone roughly twice as slow as theirs it will judder; below that it
cannot honour both the physics and the clock, and we have chosen the physics. I
would rather tell them that in one sentence now than have it discovered by a
friend with an old phone. It does **not** need a decision from them; it needs to
not be a surprise.

## Considered and rejected

- **Keep the clamp** — it is the defect. Points 3 and 4 achieve the same
  protection without routinely dilating time.
- **Unbounded catch-up, no cap** — the purest reading of the requirement, and it
  freezes. A frozen game honours the wall clock uselessly.
- **Adaptive tick rate (drop to 30Hz under load)** — the most tempting option, and
  the only one that genuinely reduces cost while keeping the clock honest. It
  lost because halving the tick rate *doubles h*, landing at the equivalent of 4
  substeps at 60Hz — the measured stability floor — on precisely the hardware we
  cannot test, where the device already showed thinner margin than the host.
  **If reach later proves genuinely inadequate this is the first thing to
  revisit, but with measurement on a real slow device, not by inference.**
- **Simulate on a second thread** — attractive, but Gauss-Seidel ordering and the
  determinism contract make it a double-buffering exercise whose failure modes are
  worse than the problem, on a budget with 41% headroom.
- **Reintroduce quality tiers** — you confirmed the client is not asking for this,
  and ADR 0011's reasoning stands.

## What I deliberately did not do

- **Did not implement `FrameDriver`.** Backend work, spec'd in `contracts.md`.
- **Did not build adaptive tick rate**, per above — recorded as the first thing to
  revisit with evidence, not built on inference.
- **Did not reopen tiering.**
- **Did not change any tuned value.**

## What I'm uneasy about

1. **The reach table is one measured derating factor extrapolated linearly.** It
   assumes devices differ by a scalar, which they do not — an older phone may be
   disproportionately worse at memory latency, which is what this solver is
   sensitive to. Treat ~1.6x as an order-of-magnitude guide, not a spec.
2. **We still have no slow device to test on.** Every claim in this ADR about
   weak-hardware behaviour is reasoned, not measured — the same posture that
   produced my 3–7x derating error. If the client can name an actual old phone
   among the people they'll share with, testing on it would be worth more than
   any further analysis from me.
3. **`droppedTicks` is only useful if someone looks at it.** It needs surfacing
   somewhere — the dev panel at minimum — or it is instrumentation that exists
   and is never read.
