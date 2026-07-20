# 0005. Losing condition: spawn-region overflow with a settle grace

Status: proposed
Date: 2026-07-20

## Context

This is the open question the brief and the Product Lead both flagged as
genuinely unsolved, and it needs a real answer rather than a default.

The difficulty is specific to this product. In a grid game, "topped out" is
unambiguous: a locked block occupies a cell above the line. Here the stack is
alive — it sags, compresses under its own weight, and settles back down. A fixed
top-out line punishes the physics for doing exactly what the product exists to
do: a piece landing hard makes the whole stack bulge upward for a few hundred
milliseconds before it settles back well below the line. Ending the game on that
transient would feel like a bug, and would teach players to fear the mechanic.

The condition must be **fair** (no death by transient), **legible** (the player
understands why they lost and sees it coming), and must **not punish the physics**.

## Decision

**The game ends when there is genuinely no room to spawn the next piece, and the
stack has been given time to settle and prove it.**

Concretely, a two-state rule built entirely on machinery that already exists:

1. **Spawn band.** The topmost region of the well — the space a new piece needs
   in order to appear — is one of the coverage bands from ADR 0004. Its
   occupancy is already computed every frame at no extra cost.

2. **Normal play.** When a piece comes to rest, it merges into the stack and the
   next piece spawns.

3. **Overflow state.** If the spawn band's fill is above the overflow threshold
   when the next piece is due, the game does **not** end. It enters *overflow*:
   no new piece spawns, the material intruding into the spawn band glows and
   pulses — reusing the band-glow visual language the brief already specifies for
   nearly-full bands — and a grace window begins.

4. **Resolution.** During the grace window the stack is left alone to settle.
   - If the spawn band drops below the threshold **and** the stack is quiet, play
     resumes immediately with no penalty.
   - If the grace window expires with the band still occupied, the game ends.

"Quiet" is not a guess: ADR 0003 measures a settled pile at residual kinetic
energy ~0.002 and a jittering one above 1.0, so *total kinetic energy below a
threshold* is a real, already-computed predicate. It is the same measure QA uses
to assert stability, which means the losing condition is testable on the JVM with
no device.

Proposed starting values, all runtime-tunable: overflow threshold **~50%** fill of
the spawn band, grace window **~1.5 s** (90 ticks), quiet threshold from measured
settle data. These are starting points for tuning at Milestone 1, exactly like the
coverage threshold.

**Why this is fair.** A transient bulge cannot kill you — that is the entire point
of the grace window. The rule is not "material touched a line", it is "material
stayed there after being given time to fall". The physics settling back down is
*rewarded* with resumed play rather than punished.

**Why this is legible.** The player watches the top of their own stack light up
and pulse, and then watches it either sink back or not. There is no line to
explain, no percentage readout (the brief rules those out), and the feedback lives
on the material, consistent with everything else in the product. It teaches itself
in one occurrence.

**Why it is simple.** It adds no new hot-loop computation, no new data structure
and no new visual language. It is a threshold comparison against an array we
already fill, a countdown, and a reuse of the glow shader path.

## Alternatives considered

**Fixed top-out line, immediate loss** — rejected. This is the default and it is
the specific unfairness the Product Lead identified. A hard landing makes the
stack bulge; ending the game on a bulge that settles back is indistinguishable
from a bug, and it would train players to drop pieces gently, which fights the
mechanic.

**Fixed top-out line with a grace period** — rejected, though it is close to the
chosen design and was the leading candidate for a while. It lost on legibility:
an arbitrary line drawn across a well full of organic, deformed material is
visual chrome in a game that has deliberately removed chrome, and it invites the
question "why *there*?" Tying the condition to the spawn region answers that
question physically — you lost because the next piece had nowhere to go.

**Block-out only: lose when the spawning piece overlaps existing material** —
rejected, though it is the most physically honest option. It lost on two counts.
It is *late* — the player gets no warning, the piece simply fails to appear, and
losing should be visible as it approaches. And with soft bodies "overlaps" is
fuzzy: a piece can spawn into a marginal gap and immediately be squeezed, which
would be a bizarre way to end a run. The chosen design is block-out with a
warning state and a fair test.

**Pressure-based: lose when total stack compression exceeds a limit** — rejected.
Thematically lovely, and it would make the mechanic about load rather than height.
It lost on legibility: compression is not something the player can read off the
screen or reason about ahead of time, so losses would feel arbitrary. Worth
revisiting as a *scoring* input rather than a losing condition.

**Survival with no losing condition at all** — rejected. The brief specifies
"play until the stack tops out" and a personal best, both of which need a run to
end.

## Consequences

**Easy.** No new machinery, no new visual language, and the whole rule is
expressible in the pure simulation core, so it is unit-testable on the JVM: seed a
stack, assert the game does not end on a transient bulge, assert it does end when
the band stays occupied. Both thresholds and the grace window are tunable dials.

**Hard.** The grace window is a real pause in the action, and pacing it is a feel
problem: too short and it does not do its job; too long and the game stalls at its
most tense moment. It also needs to interact sensibly with a *clear* — if a band
clears during grace and the stack drops, play should resume, and that path needs
testing.

**Live with.** A player can extend a run by hovering near the overflow state and
repeatedly earning grace windows. Whether that reads as skilful tension or as
exploitable stalling is a playtest question. If it is a problem, the simple fix is
to shorten the grace window on each consecutive overflow rather than to add a new
rule.

**Open.** The scoring formula remains unanswered and is out of scope here — the
brief's fourth open question (whether packing efficiency is rewarded beyond the
clear) is a design decision for the UX Designer and Product Lead, not an
architectural one. Noted so it is not lost.
