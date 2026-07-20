# 0004. Coverage bands via a coarse occupancy bitmap

Status: proposed
Date: 2026-07-20

## Context

The core scoring mechanic replaces "a row is perfectly full" with "a horizontal
band is ~90% filled by material". Nothing is grid-aligned, the geometry deforms
every frame, and the answer is needed every frame — it drives both the clear rule
and the band-glow feedback the brief specifies ("material in a nearly-full band
glows warmly from within", with no HUD readout).

The 90% figure is explicitly a guess. The Product Lead flagged it as unknowable
without play, so **runtime tunability is a requirement, not a nicety**.

## Decision

**A coarse occupancy bitmap: stamp each particle's disk into a low-resolution
grid, then count set cells per band.**

The well is divided into 20 bands. Each band is a small grid of cells roughly one
particle radius across (40 columns x 4 rows measured as the default). Each frame:
clear the bitmap, stamp every particle's bounding cells as occupied, then count
set cells per band. Band fill = set cells / total cells.

Measured cost:

| particles | cells/band | p50 ms | % of a 16.67ms frame |
| --------- | ---------- | ------ | -------------------- |
| 960   | 160 | 0.0060 | 0.04% |
| 960   | 360 | 0.0090 | 0.05% |
| 1 500 | 160 | 0.0082 | 0.05% |
| 1 500 | 360 | 0.0116 | 0.07% |
| 2 160 | 160 | 0.0125 | 0.08% |
| 2 160 | 360 | 0.0175 | 0.10% |

**This is free.** At the default tier it is 0.05% of the frame — roughly 1.5% of
the solver's own cost — and it allocates zero bytes per frame. The hot-loop
concern that motivated this ADR turns out not to be a concern at all; the
algorithm choice can be made on correctness grounds alone.

It measures the right thing. Reading a settled pile gives band fills of 75–92%,
which sit sensibly around the brief's ~90% target — the threshold is in a range
where it discriminates, rather than being trivially always-met or never-met.

**Tunability.** The threshold is a single float read from configuration each
frame, not a compile-time constant. Band count, cell resolution and threshold are
all part of the tuning set exposed through the dev panel (ADR 0008), so the
client can turn the dial during a demo and feel the difference immediately.

**The same machinery serves three consumers**, which is the main simplicity
argument: the clear rule reads band fill against the clear threshold; the
renderer reads the band fill array to drive the internal glow (ADR 0007); and the
losing condition reads the fill of the topmost spawn band against a different
threshold (ADR 0005). One algorithm, three uses, no new hot-loop cost.

## Alternatives considered

**Sum of particle disk areas per band** — rejected, and it is the obvious first
idea. It is marginally cheaper and it answers the wrong question. Overlapping
particles double-count, so a compressed clump reads as over-full; and a ring of
particles around a void reads as completely full, which is exactly backwards for
a mechanic about squeezing material into gaps. The occupancy bitmap measures
*span*, which is what the player sees.

**Exact rasterisation of the deformed triangle mesh** — rejected. It is the
accurate answer: scan-convert the same triangles the renderer draws and integrate
the covered area. It lost on cost and on complexity — per-triangle scan conversion
across 20 bands is far more work than stamping particles, and the measurement
shows we do not need the accuracy. The bitmap's error is bounded by cell size,
which is a tunable, and the threshold is being hand-tuned anyway, so systematic
error is absorbed into the tuned value.

**GPU rasterisation with readback** — rejected firmly. It looks attractive because
the GPU is already drawing this geometry. It lost because reading back a render
target stalls the pipeline, and a stall costs more than the entire CPU algorithm
by orders of magnitude. It would also make the game rules depend on the renderer,
breaking ADR 0008's framework-free core and taking the clear rule out of reach of
JVM tests.

**Computing coverage only when a piece locks, rather than every frame** —
rejected. It would be cheaper still, and for the *clear rule* alone it would be
sufficient. It lost because the band glow must track continuously as the stack
sags and settles — that feedback is how the player learns the rule without a
tutorial. Given the measured cost, there is nothing to save.

**Vertical (column) coverage as well as horizontal** — not built. No mechanic asks
for it. Recorded here so nobody adds it by drift.

## Consequences

**Easy.** The threshold, band count and resolution are all runtime-tunable at
negligible cost, so the open question the brief carries ("what percentage actually
feels fair?") becomes a dial to turn at the demo rather than a rebuild. Band fill
is a plain `FloatArray` in the simulation state, trivially assertable in JVM
tests and trivially passed to a shader.

**Hard.** Fill percentage is quantised by cell resolution — at 40x4 cells per
band the granularity is ~0.6%, which is finer than the threshold needs but means
fill values step rather than vary smoothly. If the glow visibly steps as a band
fills, the fix is finer cells (measured at 0.0175ms, still free) rather than a
different algorithm.

**Live with.** Bitmap coverage counts a cell as full if any particle overlaps it,
so it systematically *over*-reports fill at coarse resolutions. The tuned
threshold absorbs this, but it means the tuned number is tied to the chosen cell
resolution: **changing band resolution invalidates the tuned threshold.** That
coupling must be written next to both settings.

**The same coupling applies to the quality tier**, and it is easier to miss:
coarser lattices have larger particles, which stamp larger disks, which read as a
higher fill for the same material. **The clear threshold must therefore be
calibrated per tier**, or the startup performance tier silently changes the game
rules. See ADR 0009.

## Amendment 1 — the band-fill consumer contract (2026-07-20)

Settled between the backend and frontend engineers while wiring `uBandFill`.
Everything here **ratifies behaviour that already ships**; nothing in this
amendment asks either module to change. It is recorded because all of it was
true and none of it was written down, which is the condition under which the
contact gap (ADR 0011) happened.

**1. Fill is per *band*, never per row and never per body.** One value per band,
`set cells / (bandColumns * bandRows)` = `set / 160` at the default resolution.
Rows exist only as bitmap resolution *inside* a band; there is no per-row fill
value anywhere in the system, and there is no per-body one either — a body
spanning three bands contributes to all three independently. That is precisely
what makes the glow a property of a *zone*: two fragments at the same height in
different pieces read the identical value with no work.

**2. Fill cannot exceed 1, structurally — and the reason is load-bearing.**
`set` counts *distinct* set cells within a band's slice, so `set <= cellsPerBand`.
The bound holds because the bitmap measures **span, not area**: overlapping
particles cannot double-count, which is the specific reason the area-sum
alternative was rejected in the decision above. The damping is convex
interpolation between two values already in `0..1`, so it cannot overshoot
either. **Consumers may rely on `0..1` without clamping**, and the shader does.
The "why" is recorded next to the number deliberately: a bare "it cannot exceed
1" is exactly the kind of fact that quietly stops being true.

**3. `uBandFill` drives anticipation glow only. `uBandClearProgress` drives
ignition, hold and dissolve.** Fill alone cannot animate a clear — a band at
fill 1.0 and a band mid-dissolve are indistinguishable — so the two signals are
separate and stay separate. Keying a flash off fill would re-trigger it, because
fill never latches.

**4. `SimState.bandFill` is damped, and the clear rule reads this same damped
value.** Asymmetric first-order, rise 0.25/tick, fall 0.5/tick. The damping is
correct and intentional: raw fill spikes during the bounce of a heavy landing,
and an undamped glow would flash the well amber on every hard drop, teaching the
player a rule that is not the rule. The damping doubles as the clear rule's
quiescence gate — a spike is attenuated here before the rule sees it — which is
why the rule reads the published value rather than a private undamped one (the
earlier private-undamped design paired with a stack-energy gate that measured
*unreachable*; see `Simulation.beginClear`). **A trap survives that change and
belongs in every consumer's head: fill crossing the threshold is not a clear.**
A clear also requires a piece to lock and a body to sit in the band, and the
threshold is runtime-tunable and invisible to the shader — so a band can glow
at full for many frames with no clear. Do not assert "glow crossed threshold ⇒
clear fired" against `bandFill`; read `bandClearProgress` leaving `-1`.

**5. Fill *snaps* on the tick material is removed, rather than damping down.**
The damping exists to swallow a transient the player should not see rewarded.
Material vanishing in a clear is the opposite — a real, intended, instantaneous
change. Left damped, fill merely halves on the removal tick, so a cleared band
keeps glowing over empty space for several frames. The snap covers every band,
not only the cleared ones, because a clear removes whole bodies spanning about
three bands each.

**6. `bandCount` is published on `SimState`.** The shader's uniform array length
is baked in at compile time and out-of-range indexing is undefined behaviour in
GLSL, so `:app` asserts the two agree against the authoritative runtime value
rather than against a default-constructed config.

### Naming: `uBandInvHeight` is a correctness constraint, not a preference

The uniform is the **reciprocal** of `bandHeight`, because the shader multiplies:
`(worldY - uBandBottomY) * uBandInvHeight`. Renaming it to `uBandHeight` and
uploading `bandHeight` scales every lookup by height², putting the glow in the
wrong bands with no compile error and no assertion. At the default
`20 / 20 = 1.0` the bug is invisible, and it surfaces only when the well
geometry changes — which ADR 0010 does at runtime from the display insets. The
uglier name is the one that cannot be got wrong. `docs/contracts.md` has been
moved onto the shader's names (`uBandClearProgress`, `uBandInvHeight`) rather
than the reverse.
