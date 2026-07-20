# 0011. Piece geometry is defined by material extent, and the lattice is pinned

Status: proposed
Date: 2026-07-20
Supersedes the three-tier quality system in ADR 0009.

## Context

Two findings from device testing arrive together and resolve into one decision.

**1. Piece size varies with the quality tier.** A piece's true material size is
its lattice extent plus one particle radius on each side:

```
pieceExtent = PIECE_WIDTH + 2r,  where r = PIECE_WIDTH / (2(lattice-1))
            = PIECE_WIDTH x lattice/(lattice-1)
```

| lattice | material extent |
| ------- | --------------- |
| 4 | **2.40** |
| 5 | 2.25 |
| 6 | 2.16 |

**11% variation.** That changes how many pieces fit per row, which changes
packing, which changes the game. ADR 0009 introduced tiers so weaker hardware
degrades *gracefully*; as written it also silently hands different players a
different game. The backend engineer measured this and correctly declined to
change it unilaterally. It is real in the physics today — the rendering defect
below has been masking it, and fixing that will expose it.

**2. The measured derating collapses the tiers to one.** The blocker is closed:
**12.06x**, against my estimated 3–7x (ADR 0009 amendment). Re-deriving:

| lattice | particles | host ms | device ms | +3% at 1e-4 | frame left |
| ------- | --------- | ------- | --------- | ----------- | ---------- |
| 3 | 540 | 0.265 | 3.20 | 3.29 | 13.38 |
| **4** | **960** | **0.493** | **5.95** | **6.12** | **10.55** |
| 5 | 1 500 | 0.846 | 10.20 | 10.51 | 6.16 |
| 6 | 2 160 | 1.282 | 15.46 | 15.92 | 0.75 |

Lattice 6 is dead — 0.75 ms left for rendering, input and the OS. Lattice 5
leaves 6.16 ms for a fragment shader that is still unmeasured and is the
product's largest remaining performance unknown. **Only lattice 4 is viable on
the reference device**, and it lands almost exactly on the ~6 ms simulation
allowance the frame budget was drawn against.

So the tier system's gameplay leak is a cost being paid for optionality that
measurement says does not exist.

## Decision

**1. The gameplay constant is the piece's material extent, not its lattice
extent.**

```
PIECE_EXTENT = 2.40          // world units, the gameplay-relevant size
spacing      = PIECE_EXTENT / lattice
r            = spacing / 2
```

This makes the lattice a pure implementation detail: changing it preserves piece
size exactly.

| lattice | spacing | r | lattice extent | material extent |
| ------- | ------- | - | -------------- | --------------- |
| 4 | 0.600 | 0.300 | 1.800 | **2.40** |
| 5 | 0.480 | 0.240 | 1.920 | **2.40** |
| 6 | 0.400 | 0.200 | 2.000 | **2.40** |

`PIECE_EXTENT = 2.40` is chosen deliberately to equal today's lattice-4 value, so
**this change alters no behaviour whatsoever right now** — spacing stays 0.600 and
r stays 0.300. It only makes the definition mean what it says, and closes the leak
permanently against any future re-pin.

**2. The lattice is pinned at 4. The three-tier system is superseded.** One
lattice, one set of tuned constants, no startup calibration branch, no per-tier
clear threshold, no per-tier compliance.

**3. Runtime quality scaling stays render-side only**, exactly as ADR 0009
already specified — resolution scale, shader quality, effect density. That part
of ADR 0009 is unchanged and is now the *only* scaling mechanism.

**4. If a weak device ever proves to need less**, lattice 3 (3.29 ms) is the
documented fallback, as a **build-time re-pin with tuning re-calibrated** — the
same discipline as substeps (ADR 0003 Amendment 1). Not a runtime tier. It is
recorded so the option is known, not so it is built.

**Note that ADR 0002's revisit trigger fired and was satisfied by the cheap
response.** That trigger read: "at Milestone 1, if simulation at the default tier
exceeds 8 ms/frame". The old default (lattice 5) measures 10.51 ms. The prescribed
response was "drop to the low tier; if still short, arm64-v8a-only NDK". Dropping
to lattice 4 gives 6.12 ms. **We take the first step and stop; the NDK stays
unnecessary.** The trigger did its job.

## Alternatives considered

**Keep three tiers, fix only the geometry** — rejected. It would close the size
leak, and it was my first instinct. It lost because fixing the extent does not
close the *other* tier leaks: a lattice-6 body has more, shorter constraints than
a lattice-4 body, so at equal compliance it is measurably stiffer, and the
coverage bitmap's quantisation differs with particle radius (ADR 0004, ADR 0009's
per-tier threshold calibration). Closing all of them means calibrating compliance
*and* clear threshold *and* overflow threshold per tier — a permanent three-fold
tuning tax — to support tiers that do not fit on the reference device anyway.

**Keep three tiers with per-tier calibration** — rejected for the same reason,
stated positively: we would be paying a large, permanent tuning and testing cost
for graceful degradation onto hardware we cannot test, while the one device we
*can* test supports exactly one of the three settings.

**Keep lattice 5 as the default and cut elsewhere** — rejected. 6.16 ms for
rendering, GPU, input and OS is not enough for a procedural subsurface fragment
shader on a 6.31" high-density panel, and that shader is explicitly the largest
unmeasured risk (ADR 0009). Choosing the tighter option against the *unmeasured*
cost would be the wrong direction of caution.

**Pin lattice 3 for maximum headroom** — rejected. 9 particles per piece is a 2x2
cell grid; deformation would read as crude, and the whole product is the
deformation. The 3.29 ms is not worth it when 6.12 ms fits.

**Make `PIECE_EXTENT` 2.25 (the old lattice-5 middle)** — rejected. It would
change packing and invalidate feel tuning the client has just approved on device.
2.40 changes nothing today.

## Consequences

**Easy.** The performance dial can no longer change the game, because there is no
dial. One set of tuned constants, one clear threshold, one compliance value — the
per-tier calibration burden ADR 0009 created disappears entirely, along with the
QA test that was going to have to assert equivalent clear decisions across tiers.
Startup calibration is deleted rather than written. The change is behaviour-neutral
today, so it carries no re-tuning cost.

**Hard.** We ship with no simulation-side fallback for weak hardware. A device
meaningfully slower than the Fairphone 6 will run the simulation in slow motion
rather than degrading, because ADR 0006 fixes the timestep and correctness
forbids scaling substeps. That is a real loss of reach, and it is the deliberate
price of not shipping three subtly different games. Render-side scaling is the
only cushion, and it does not touch the 6.12 ms.

**Live with.** `PIECE_EXTENT = 2.40` is now a load-bearing gameplay constant that
several tuned values (clear threshold, well width in pieces-per-row, landing
silhouette) are implicitly calibrated against. Changing it invalidates that
tuning. It should be commented as such where it is defined, in the same way the
band-resolution/threshold coupling is (ADR 0004).

**Trigger to revisit:** a second target device with materially different
performance, or a measured GPU cost that leaves room for lattice 5. Both are
build-time re-pins, not runtime tiers.
