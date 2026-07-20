# 0009. Reference device, the 2020 floor, and how quality scales

Status: proposed
Date: 2026-07-20

## Context

The brief's performance target is "60fps on a 2020-era mid-range device". The
client's actual device is now known: a **Fairphone (Gen. 6)**, Snapdragon 7s Gen 3
(4nm), 8GB RAM, 6.31" LTPO OLED at 10–120Hz, Android 16, `arm64-v8a`.

That device is comfortably above a 2020 mid-range phone. So a pass on it proves
the game works and feels good, and proves nothing whatsoever about the stated
floor. We have no 2020 device, no emulator with meaningful GPU access, and no
prospect of acquiring either. **The stated target is currently unfalsifiable, and
designing against it would mean hand-tuning for hardware nobody in this project
can test.**

The Product Lead proposed replacing hand-tuning with dynamic quality scaling —
measure frame time at runtime and degrade solver iterations, particle count or
substep rate to hold the target. That instinct is right in outline and wrong in
one important detail, which the spike measured.

## Decision

**1. The Fairphone 6 is the reference device.** It is the pass/fail gate for
performance. Every performance claim we make is a claim about that device, stated
as such.

**2. Drop the 2020 floor as a design constraint.** Keep it as an *aspiration*
expressed through the low quality tier, but stop treating it as a requirement we
are meeting. Concretely, the brief's success criterion 1 — "installs and runs on a
mid-range Android 10 device, holding 60fps" — should be reworded to name the
reference device, because as written we cannot honestly claim it at release. That
is a client-facing change to the brief and therefore the Product Lead's to take,
not mine to make. It is flagged in the handoff.

We keep API 29 as the *minimum SDK* — that costs nothing and preserves reach. We
simply stop claiming a verified frame rate on hardware we have never run on.

**3. Quality tiers are chosen once, at startup, by measurement — not continuously.**
On first launch the app runs the spike's reference configuration (960 particles,
3 600 constraints, 8 substeps) for a fraction of a second behind the splash, and
picks a tier from the measured result:

| tier   | particles/piece | total particles | est. device ms (3–7x band) |
| ------ | --------------- | --------------- | -------------------------- |
| low    | 16 (4x4)        | ~960            | 1.5 – 3.4 |
| medium | 25 (5x5)        | ~1 500          | 2.5 – 5.9 |
| high   | 36 (6x6)        | ~2 160          | 3.8 – 9.0 |

Medium is the default. The measured tier is cached and re-measurable from
settings. This is the *same configuration the spike reports*, which is what makes
the derating factor knowable rather than assumed — see the blocker below.

**4. Push back on the dial: substeps and particle count are not runtime dials.**
This is the detail the spike changes.

ADR 0003 measured that below 8 substeps a settled stack does not settle — at 4 or
6 substeps some materials creep or jitter. **Degrading substeps to hold framerate
would trade a smooth-framerate problem for a visible-jitter problem, and jitter
reads to a player as a bug, not as reduced quality.** Substeps are a correctness
floor. They are pinned at 8 and never scaled.

Particle count cannot change mid-game either: bodies already exist in the well
with a fixed lattice, and re-meshing a deformed body mid-run would visibly pop.
It is a startup decision.

**Mid-game scaling is render-side only**, where degradation is graceful and
invisible to the physics:
- render resolution scale (the largest lever, and fragment-bound work is the
  likely bottleneck per ADR 0007)
- shader quality — fewer noise octaves, cheaper subsurface approximation
- effect density — screen shake, particles, glow bloom

If render-side scaling cannot hold 60fps, the correct response is to drop a tier
**and say so on the next launch**, not to silently degrade the simulation.

**5. Frame budget at 60Hz (16.67 ms), reference device, medium tier:**

| stage | estimated ms |
| ----- | ------------ |
| solver (8 substeps) | 2.5 – 5.9 |
| coverage bands | < 0.1 |
| vertex buffer build + upload | ~0.5 |
| GL draw + fragment shading | unmeasured — GPU-bound, ADR 0007 |
| OS, input, audio, headroom | remainder |

The CPU side is comfortable. **The unmeasured risk in this product is now the
fragment shader, not the solver** — which is a reversal of the assumption the
project started with, and the UX Designer should know it.

## Alternatives considered

**Keep the 2020 floor and hand-tune for it** — rejected. It means tuning against
an imagined device, and any claim we made about it would be unverified. Worse, it
would push the default tier down to protect hardware nobody has, degrading the
experience on the device that actually matters.

**Continuous dynamic quality scaling of the simulation** — rejected on
measurement, as argued above. This was the Product Lead's instinct and it is the
right idea applied to the wrong parameters. Adopted for rendering, rejected for
physics.

**Drop API 29 to a higher minimum matching the reference device** — rejected. It
would cost reach for no benefit; nothing in this design needs a newer API, and
ADR 0002's pure-Kotlin decision removes the native-library concerns that usually
drive minimum-SDK bumps.

**Ship a single fixed quality level tuned to the reference device** — rejected,
but it is the honest simple option and close to viable. It lost because a single
level tuned to a Snapdragon 7s Gen 3 would be unplayable on genuinely weak
hardware, and the tier mechanism is one measurement and one branch — far less
machinery than continuous scaling, and it degrades rather than fails.

**Multi-threading the solver to buy headroom** — rejected and worth recording so
nobody adds it by drift. Gauss-Seidel is order-dependent (ADR 0003) and
determinism forbids racy parallelism (ADR 0006), so it would require graph
colouring — real complexity for headroom the measurements say we do not need.

## Consequences

**Easy.** Performance claims become honest and testable against a device we can
hold. Weak hardware degrades to a lower tier instead of stuttering. The
calibration reuses the spike's reference configuration, so the first on-device run
immediately yields the true derating factor and retires the project's largest
unknown.

**Hard.** Three tiers means three lattice sizes, so index buffers, tuned
compliance values and possibly tuned coverage thresholds exist per tier. That is
real tuning surface. Mitigation: tune medium properly, derive the others, and
accept that low and high are approximations until someone complains.

**Live with.** We will ship without knowing how the game runs on a 2020 device,
and we should say so plainly rather than imply otherwise. The estimated 3–7x
derating band is wide; the low end and high end differ by more than a factor of
two, which is the difference between "comfortable" and "tight" at the high tier.
That band closes the moment an APK runs on the Fairphone — which is Milestone 1,
by design.

**Blocker.** On-device frame timing cannot be measured in this container: no
physical device, no emulator with meaningful GPU access. Recorded in
`/work/.team/blockers.md`. It closes at Milestone 1 by running the reference
configuration on the client's Fairphone 6 and dividing by the host number in
`/work/spike/solver-budget/results-host.txt`. The benchmark should ship in the
debug build as a hidden one-tap action so this costs nobody any effort.
