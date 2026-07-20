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
