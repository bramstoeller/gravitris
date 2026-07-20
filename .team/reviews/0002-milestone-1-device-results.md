# Milestone 1 — measured results from the client's device

Date: 2026-07-20 · Fairphone (Gen. 6), Android 16, Adreno GPU, `arm64-v8a`
Source: two client screenshots of the on-screen readout, since deleted at their
request. This file is the durable record of what they showed.

Client's verbal summary: **"60fps, but not very squishy."**

## Readings

| | 6 bodies | 24 bodies |
| - | -------- | --------- |
| mean frame | 11.1 ms | **16.8 ms** |
| p95 | 14.5 ms | **25.5 ms** |
| max | 15.0 ms | **31.6 ms** |
| cpu | 4.7 ms | 11.2 ms |
| fps | 90.2 | **59.4** |
| jank/s | 0 | **29** |
| triangles | 192 | 768 |
| bandwidth | 1.8 KB/f | 7.0 KB/f |

Solver benchmark, 960 particles over 600 frames:
`5.36 ms p50 · 5.39 p95 · 5.44 max · host p50 0.444 ms (jvm)`
→ **12.06× host derating · 32.2% of a frame**

Both screenshots read `shade:off` and `haptics:fixed`.

## What this actually says

**1. The blocks do not deform. At all.** Every body in both screenshots is a
geometrically perfect square with sharp corners — rotated, stacked, tumbling,
but never squashed. The client's "not very squishy" is not a matter of degree;
the deformation is visually absent. This is the product's entire reason to
exist and it is the finding that matters most.

Consistent with the backend engineer's measurement that compression spans only
**0.888–1.00** — at most 11% area change, at peak impact. ADR 0001's compliance
is far too stiff for the intended feel.

The good news is that ADR 0001 also measured solver cost as **independent of
stiffness across a 1000× compliance range**. Softer is free. This is a tuning
problem, not an architecture problem.

**2. Performance is worse than the client believes, and worse than estimated.**
Their "60fps" reading comes from the 6-body screen, which was actually running
at 90. At 24 bodies it is 59.4 fps with **29 jank events per second** — visible
stutter, not a clean 60.

The host→device derating blocker is now **closed with a real number: 12.06×.**
The architect's reasoned estimate was 3–7×. Reality is roughly double the
pessimistic end. Every performance projection in this project that used 3–7×
needs redoing, and ADR 0009's quality tiers need re-deriving from 12×.

**3. Haptics are not scaled.** The readout says `haptics:fixed`, so the build
fell back to a constant pulse. Amplitude scaling was argued — successfully, and
at the cost of adding the `VIBRATE` permission and a security review — on the
grounds that it is *the* highest-return element for the sensation of weight. It
is not running. The client judged "does it feel heavy" without it.

**4. The client never saw the compression darkening.** `shade:off` in both
shots. The term exists, was tuned against the real solver, and was not on
screen. Given finding 1 it would have had almost nothing to show anyway.

## Consequence

Milestone 1 did its job: it answered the question it existed to ask, and the
answer is no. Not fun-yet, not pretty-yet — the core mechanic is not present.
Better to know this now, with one block and nothing else built, than after the
art direction and the game rules are layered on top.

---

## Third device run — 2026-07-20, after the gap fix and softening

`15.0ms mean · 24.5 p95 · 43.9 max · 8.2 cpu · 66.5 fps · 23 jank/s · 544 tri · 17 bodies · 5.0 KB/f`
`shade:on · haptics:fixed (no amp control) · imp:1247 puls:346 e:0.17 · sys vib:? touch-fb:OFF`

**Fixed and confirmed by the client:** bodies now touch — no margin, contact reads
as a line. Deformation is clearly visible. Client: *"Ziet er goed uit nu, blokjes
raken elkaar."*

**Answered:** the Fairphone 6 reports **no amplitude control**. The open question
from the haptics work is closed, and the answer is the unwelcome one — the
scaled-impact cue that justified the `VIBRATE` permission is not available on the
client's device. They feel a fixed buzz. `imp:1247 puls:346` shows most impacts
fall below the pulse floor.

**New, not yet triaged:** the two airborne bodies in the screenshot show sharp
concave spikes and a folded, crumpled silhouette rather than smooth squash. May
be legitimate deformation from a mid-air collision, or the boundary extrusion
inverting on heavily deformed bodies. Needs a verdict.

**Still janky:** 23 jank/s and a 43.9 ms worst frame at only 17 bodies.
