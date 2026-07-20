# 0013. Frame-overrun policy: wall-clock time is never dilated

Status: proposed
Date: 2026-07-20
Supersedes the accumulator clamp in ADR 0006. The rest of ADR 0006 stands.

## Context

**This is a client requirement, not an engineering preference.** Recorded verbatim
so it is not later "optimised" away:

> "Ja, dat is wel zo, hij moet het gewoon goed doen overal. Frames skippen is
> prima, of een lagere frame rate, maar niet vertragen."
>
> — *Yes, that's true, it just has to work properly everywhere. Skipping frames is
> fine, or a lower frame rate — but no slowing down.*

They deferred the Play Store and will share the APK directly, including with
people on older phones, so weak-hardware behaviour is concrete for them, not
hypothetical. A block that takes 3.5 seconds to fall must take 3.5 seconds on
every device. Dropped frames, lower refresh and visible judder are all acceptable.
A slow-motion game is not.

**ADR 0006 specifies exactly the behaviour they are ruling out.** Its loop reads:

```
accumulator += min(frameDelta, MAX_FRAME_DELTA)   // clamp: no spiral of death
```

That clamp **discards wall-clock time by construction**. On a device that
overruns, real time passes that the simulation never receives, so the simulation
clock runs slower than the wall clock. That is time dilation — it is not a gap in
ADR 0006, it is a defect in it, and it was introduced deliberately to prevent a
death spiral without my noticing what it cost.

## The constraint that shapes every option

Solver cost per wall-second is `tickRate x substeps x costPerSolve`. XPBD's
stability depends on the substep size `h = 1 / (tickRate x substeps)`.

**These are the same quantity.** Cost per wall-second is exactly inversely
proportional to `h`:

| configuration | h | solves/sec |
| ------------- | - | ---------- |
| 60Hz x 8 substeps | 1/480 | 480 |
| 30Hz x 16 substeps | 1/480 | 480 |
| 30Hz x 8 substeps | 1/240 | 240 |
| 60Hz x 4 substeps | 1/240 | 240 |

So **you cannot buy CPU time without enlarging `h`**, and enlarging `h` walks
toward the measured stability cliff (ADR 0003: floor at ~3–4 substeps at 60Hz,
and 6 measures *worse* than 8 on device). With particle count pinned (ADR 0011)
and substeps pinned (ADR 0003), there is no honest degradation path that preserves
both wall-clock time and physics fidelity.

That is worth stating plainly because it bounds what we can promise: on hardware
too slow for the pinned configuration, **something must give, and the client has
told us it is not wall-clock time.**

## Decision

**1. The accumulator never discards wall-clock time in normal operation. The
`min(frameDelta, MAX_FRAME_DELTA)` clamp is removed.** When the game falls behind,
it catches up by running more ticks.

```
accumulator += frameDelta
ticks = 0
while accumulator >= TICK and ticks < MAX_CATCHUP_TICKS:
    sim.step(input); accumulator -= TICK; ticks++
alpha = accumulator / TICK
```

**2. Catch-up never enlarges the step.** This is the key safety property and it
answers the blow-up concern directly: catching up runs *N normal ticks of 1/60 s
with 8 substeps each*. `h` is invariant under every policy in this ADR. **No
overrun handling here can destabilise the solver**, because none of it changes
the only quantity the solver's stability depends on. The cost of catch-up is CPU
time and visible judder — never physics.

**3. Backgrounding pauses the game; it does not accumulate.** The huge-delta case
(app resumed after ten minutes) is handled by pausing at `onPause` and resetting
the accumulator at `onResume`, not by clamping. ADR 0010 already requires back to
pause. This removes the main reason ADR 0006 had a clamp at all.

**4. `MAX_CATCHUP_TICKS` is a safety valve, not routine behaviour.** Set at 8
(133 ms of catch-up per frame). If the accumulator still exceeds one tick after
that, the excess is discarded — **this is the one and only place wall-clock
honesty breaks**, and it must be:

- **counted and exposed** in `SimState` as an overrun counter, so it is observable
  rather than silent;
- understood to mean **the device is below the hardware floor**, not that the game
  hiccuped.

Discarding here is chosen over unbounded catch-up because unbounded catch-up
death-spirals into a freeze, which serves the client worse than judder.

**5. The primary mitigation for weak hardware is render-side scaling**, which
already exists (ADR 0009) and is already the only runtime lever. This matters
because the likely bottleneck on an older phone is the **fragment shader**, not
the solver (ADR 0009, ADR 0011). Reducing resolution scale and shader quality
frees budget, the simulation keeps 60 ticks/second, wall clock stays honest, and
the frame rate drops — which is precisely the trade the client asked for.

**6. The stepping policy lives in `:core-sim`, not in the render loop.** A small
`FrameDriver` owns the accumulator and this policy, so that:

- it is **JVM-testable**: a test can feed it a pathological delta sequence and
  assert that simulated time equals wall-clock time;
- the client requirement is enforced by a test rather than by memory, and cannot
  be reverted by someone tidying the render loop.

`:app` feeds it real frame deltas and receives a tick count and a render alpha.

## What this means for determinism and for QA

The coordinator asked this be stated unambiguously, and it needs to be, because
the natural reading is wrong.

**`sim.step()` remains pure and deterministic. That is unchanged.** What varies
with wall clock is *how many ticks occur per rendered frame* and *which tick an
input lands on*.

Therefore:

- **Replay is a fixed-tick-sequence property, not a real-time property.** A
  recorded sequence of `(tick index, InputFrame)` replays bit-identically on any
  device. A recorded *real-time session* does **not** reproduce across devices,
  because tick alignment and input sampling differ.
- **QA fixtures must be tick-indexed, never wall-clock-indexed.** A fixture that
  records "input at t=1.234 s" is not reproducible and will flake. This is the
  concrete instruction for the replay fixtures being built now.
- **The discard path (item 4) breaks reproducibility for that session**, since
  ticks that never ran cannot be replayed. That is another reason it is counted
  and treated as a floor violation rather than as normal operation.

## What the player sees

At overrun: fewer rendered frames per second, the piece visibly advancing in
larger jumps between frames, motion still interpolated within each frame. Judder.
**Wall-clock timing of the game is unaffected** — falls, grace windows and clear
animations all take the same real time as on a fast device, because all of them
are tick-counted (ADR 0005) and ticks track wall clock.

At the floor (item 4): the game additionally begins to run slower than real time,
because there is no remaining honest option. It is counted so we can tell.

## Reach

Solver cost is 367 ms per wall-second at the pinned configuration — **36.7% of one
core** on the reference device.

| device slower by | solver share of one core | left for render + OS |
| ---------------- | ------------------------ | -------------------- |
| 1.0x (reference) | 36.7% | 63.3% |
| 1.5x | 55.1% | 44.9% |
| 1.6x | 58.8% | 41.2% |
| 2.0x | 73.4% | 26.6% |
| 2.7x | 99.9% | 0.1% |

So roughly: devices up to **~1.6x slower** than the Fairphone 6 should hold 60
ticks/second with render-side scaling doing the work; **~2.7x slower** is the
absolute wall where the solver alone consumes the core. Between those, judder;
beyond, the floor.

This is an estimate derived from a single measured derating factor, and it is
one-dimensional — it assumes devices differ by a scalar, which they do not.

## Alternatives considered

**Keep the ADR 0006 clamp** — rejected; it is the defect. It was there to prevent
a death spiral, and it does, but it pays with the one currency the client has said
is not for sale. Item 3 (pause on background) plus item 4 (bounded valve) achieve
the same protection without routinely dilating time.

**Unbounded catch-up with no cap at all** — rejected. It is the purest reading of
"never dilate time", and on a device that cannot keep up it death-spirals: each
frame accrues more debt than it retires, catch-up work grows without bound, and
the game freezes. A frozen game honours wall-clock time in the most useless
possible way.

**Adaptive tick rate — drop to 30Hz when the device cannot keep up** — rejected,
and this was the most tempting option because it is the only one that genuinely
reduces cost while keeping wall clock honest. It lost on the constraint above:
halving the tick rate at fixed substeps *doubles `h`*, landing at the equivalent
of 4 substeps at 60Hz — at the measured stability floor, on hardware we cannot
test, where the device already showed thinner margin than the host (6 substeps
measuring worse than 8). It trades a visible, honest judder for a possible
physics blow-up on exactly the devices we cannot verify. It also changes feel and
determinism silently. **If reach later proves genuinely inadequate, this is the
first thing to revisit — but with measurement on a real slow device, not by
inference.**

**Variable timestep proportional to frame time** — rejected, already rejected in
ADR 0006, and this ADR strengthens the reason: it makes `h` a function of device
load, so material stiffness and stack stability would vary with frame rate.

**Reintroduce quality tiers for weak devices** — rejected, and the client
explicitly is not asking for them back. ADR 0011's reasoning stands: shipping
three subtly different games was the wrong trade, and piece size varying with a
performance setting was a gameplay leak.

**Decouple by simulating on a second thread** — rejected. It would let the
simulation keep real time while rendering lags independently, which is genuinely
attractive here. It lost on ADR 0006's determinism constraints and ADR 0003's
order-dependent Gauss-Seidel solve: the simulation would need double-buffered
state and a strict handoff, and the failure modes (torn reads, contention on a
budget with 41% headroom) are worse than the problem. Revisit only with the same
evidence standard as adaptive tick rate.

## Consequences

**Easy.** Wall-clock timing is honest on every device that can run the simulation
at all, which is what the client asked for. Catch-up cannot destabilise the solver
because `h` never changes. The policy is testable on the JVM, so the requirement is
enforced rather than remembered. Backgrounding is handled by pausing, which is
simpler and more correct than clamping.

**Hard.** We now have a **hardware floor** and should be honest about it rather
than implying "works everywhere". Below roughly 2x slower than the reference
device, the game will judder; below the floor it will discard time despite this
ADR. There is no simulation-side lever left to soften that, by design.

**Live with.** Real-time session recordings are not reproducible across devices —
only tick-indexed sequences are. That is a genuine reduction in what QA can
capture from a live session, and it needs saying before fixtures are built, not
after.

**For the Product Lead:** the client's "het moet gewoon goed doen overal" is
satisfied in the sense they meant — no slow motion, degrade via frame rate — but
"overal" has a floor we cannot remove without giving up either the physics or the
wall clock. That is worth one honest sentence to them rather than a promise we
would have to walk back.
