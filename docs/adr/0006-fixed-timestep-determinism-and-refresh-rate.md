# 0006. Fixed 60Hz timestep, deterministic simulation, 60Hz render on a 120Hz panel

Status: proposed — **the accumulator clamp below is superseded by ADR 0013.**
The fixed timestep, determinism and 60Hz-render decisions stand unchanged.
Date: 2026-07-20

## Context

Three questions that look separate and are actually one: what timestep the
simulation runs at, whether the simulation is deterministic, and what to do with
the reference device's adaptive 10–120Hz LTPO display.

They are one decision because XPBD's stability depends on the substep size (ADR
0003 fixes a floor of 8 substeps, tuned at a 60Hz tick), because determinism
requires a fixed timestep, and because the display refresh rate determines
whether the render loop can drive the simulation directly or must be decoupled
from it.

The reference device is a Fairphone 6 with a 10–120Hz adaptive panel. On an
adaptive panel the system picks a rate unless we ask for one, so "do nothing" is
not a neutral option — it is a choice to let the platform decide, silently, per
device.

## Decision

**Simulation runs at a fixed 60Hz tick with 8 substeps. Rendering is decoupled
via an accumulator with interpolation. We explicitly request 60Hz from the
display.**

**1. Fixed tick of exactly 1/60 s**, subdivided into 8 substeps of 1/480 s. Never
variable, never scaled by wall-clock delta. XPBD's behaviour is a function of
substep size: a variable timestep would make the material's apparent stiffness
and the stack's stability vary with framerate, so the game would feel different
on a loaded device — the exact opposite of what a physics-driven product needs.

**2. The simulation is deterministic, and this is a load-bearing property.**
Measured in the spike:

```
same binary, same seed, 900 frames, 8 substeps: hashes MATCH (bit-identical)
450+450 frames vs 900 frames:                   hashes MATCH (stepping is stateless)
same scene at 12 substeps instead of 8:         hashes DIFFER (as expected)
```

Determinism holds across devices, not just across runs, because of ADR 0002:
since Java 17 all JVM floating-point arithmetic is strict IEEE-754 and
`Math.sqrt` is correctly rounded. The solver uses only `+ - * /` and `sqrt`.

Two rules protect this and must be enforced in review:
- **No transcendental functions in the simulation core.** `sin`, `cos`, `exp`,
  `pow` are permitted up to 1–2 ulp of platform variance and would silently break
  cross-device determinism. Where trigonometry is unavoidable (piece rotation),
  use a fixed lookup table.
- **No concurrency in the constraint solve.** Gauss-Seidel is order-dependent
  (ADR 0003); parallelising it without graph colouring changes results run to run.

**Clarified by ADR 0013:** this is a *fixed-tick-sequence* property, not a
real-time one. A recorded sequence of `(tick index, InputFrame)` replays
bit-identically anywhere; a recorded real-time session does **not** reproduce
across devices, because how many ticks fall in a rendered frame depends on the
wall clock. **QA fixtures must be tick-indexed.**

What this buys: the simulation contract becomes *same seed + same input sequence
= same final state*, so QA can record an input sequence and replay it as a
regression test, on the JVM, with no device and no renderer. Physics bugs become
reproducible from a seed and an input log rather than from "it jittered once".
Substep count is part of the contract — changing it changes results — so it is
pinned configuration, not a tunable.

**3. Render decoupled with interpolation.** The classic accumulator:

```
accumulator += min(frameDelta, MAX_FRAME_DELTA)   // SUPERSEDED — see ADR 0013
while (accumulator >= TICK) { sim.step(input); accumulator -= TICK }
alpha = accumulator / TICK
render(lerp(previousPositions, currentPositions, alpha))
```

~~Frame delta is clamped to at most 4 ticks so a stall cannot cascade into a death
spiral of catch-up steps.~~

> ⚠ **Superseded by ADR 0013.** That clamp **discards wall-clock time by
> construction**: on a device that overruns, real time passes which the simulation
> never receives, so the game runs in slow motion. The client has ruled that out
> explicitly — *"Frames skippen is prima, of een lagere frame rate, maar niet
> vertragen."* This was not a gap in this ADR; it was a defect in it. I added the
> clamp to prevent a death spiral without noticing what it cost. ADR 0013 removes
> it, handles backgrounding by pausing instead, and bounds catch-up with a counted
> safety valve. Interpolation costs almost nothing here: the solver
already retains previous positions for its own integration, and the vertex buffer
is rebuilt every frame anyway (ADR 0007), so it is one lerp during the buffer
fill.

**4. Request 60Hz explicitly.** Via `Surface.setFrameRate(60f,
FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` (API 30+,
guarded for our API 29 minimum). This tells the LTPO panel to sit at 60Hz, which
matches the simulation, avoids judder from a mismatched rate, and saves power.
Without it the system may run the panel at 120Hz and we pay double GPU cost for
frames containing no new simulation state.

**Rendering at 120Hz is rejected, and the reason is the fragment shader, not the
solver.** The solver comfortably fits either budget — 1.5–3.4 ms estimated at the
default tier against 16.67 ms. But the procedural gel/subsurface shading (ADR
0007) is fragment-bound on a 6.31" high-density display, and that is the cost that
doubles at 120Hz. Halving the frame budget to 8.3 ms to render interpolated frames
of a 60Hz simulation is a poor trade: it buys smoother motion for a game whose
motion is already smooth and heavily damped, at the cost of the entire art
direction's headroom.

## Alternatives considered

**Simulate at 120Hz** — rejected. It halves the frame budget to 8.3 ms and doubles
solver cost simultaneously, which is the one combination that could actually break
the product. It would also require re-tuning the substep floor and every
compliance value. No benefit: the material is damped and slow-moving by design.

**Variable timestep driven by frame delta** — rejected. It is the simplest thing
to write and it destroys determinism, makes stiffness framerate-dependent, and
makes stack stability a function of device load. Every advantage of ADR 0003's
measured substep floor evaporates.

**Fixed tick with no interpolation, rendering the latest state** — rejected,
though it is genuinely simpler and was tempting under "keep it simple". At an
exact 60Hz render on a 60Hz panel it is indistinguishable from the chosen design.
It lost because the panel is *adaptive* and frame delivery is not guaranteed to be
exactly 16.67 ms; whenever render and simulation rates drift, the result is
visible judder, and judder in a game about smooth organic motion is expensive. The
interpolation is ~20 lines against a whole class of bug. Its cost is low enough
that it is not a speculative extension point.

**Let the system choose the refresh rate** — rejected. It is not a neutral default;
it is an unowned decision that would vary by device and by battery state, and
would make frame pacing unreproducible between the client's device and ours.

**Render at 120Hz with a 60Hz simulation** — rejected for now, on GPU cost as
argued above. The chosen design deliberately leaves this reversible: because
rendering is already interpolated, enabling 120Hz is a change to one `setFrameRate`
call, not a rewrite. If Milestone 1 shows large GPU headroom, it can be
reconsidered as a pure win.

## Consequences

**Easy.** Deterministic replay testing on the JVM without a device — the single
biggest lever QA has on this product. Frame pacing is explicit and reproducible.
Physics feels identical on every device, which matters for a product whose whole
proposition is feel. The 120Hz question is reversible.

**Hard.** The no-transcendentals and no-concurrency rules are invisible
constraints that a new engineer will violate innocently; they need to be stated in
the module's own documentation and checked in review, not just recorded here. A
device that cannot sustain 60 simulation ticks per second will run in slow motion
rather than dropping physics accuracy — that is the correct tradeoff for feel, but
it means sustained overrun is a *visible* failure, which is why ADR 0009 tiers
quality at startup.

**Live with.** Interpolation means what is on screen is up to one tick (16.7 ms)
behind the simulation. For this game that is imperceptible and well inside touch
latency. We also inherit a hard coupling between substep count and simulation
results, so substeps cannot be tuned late without invalidating recorded replay
tests — an acceptable price for the determinism, but it means ADR 0003's floor of
8 should be treated as settled before QA invests in replay fixtures.
