# 0002. The solver stays pure Kotlin/JVM — no NDK

Status: proposed
Date: 2026-07-20

## Context

The brief says "native Kotlin", which the Product Lead wrote to mean *Kotlin on
Android with no game engine* — compiling to DEX. It has since been read by the
Security Engineer as possibly meaning NDK-native code, which carries a real
packaging consequence: with the Play Store deferred we ship a single universal
APK, and an NDK solver would carry `arm64-v8a`, `armeabi-v7a` and `x86_64`
simultaneously, at which point the brief's ~5MB APK target does not survive.

There is a legitimate engineering case for the NDK here — a constraint solver is
exactly the kind of code that wants SIMD and explicit memory control. So this
needs deciding on evidence rather than on how the brief is read.

## Decision

**The solver is pure Kotlin, compiled to DEX. No NDK, no JNI, no native
libraries anywhere in the APK.**

Three measurements drive this.

**1. The budget does not require it.** Measured host cost is 0.49 ms/frame for
960 particles / 3 600 constraints / 8 substeps (ADR 0001). Derating to the
reference device (Snapdragon 7s Gen 3, Cortex-A720 prime at ~2.4GHz, running
ART) is estimated at **3–7x**, combining roughly 2.5–3.5x for the CPU and
1.3–2x for ART's optimizing compiler against HotSpot C2:

| tier   | particles | est. device ms (3–7x band) | verdict |
| ------ | --------- | -------------------------- | ------- |
| low    | 960       | 1.5 – 3.4                  | fits easily |
| medium | 1 500     | 2.5 – 5.9                  | fits, this is the default |
| high   | 2 160     | 3.8 – 9.0                  | fits if the device is at the good end |

Against a 16.67ms frame with ~6ms budgeted for simulation, the default tier fits
with headroom even at the pessimistic end of the band. **The NDK would be buying
speed we do not need.** This estimate is unverified — see ADR 0009 and
`blockers.md` — and this ADR's trigger for revisiting is stated below.

**2. The layout win the NDK would unlock is not there.** ADR 0001 measured SoA
vs AoS at 1–3% in our size range; the working set fits in L2. The main things
C++ would add — SIMD and control over memory layout — target a bottleneck the
measurement says we do not have. The spike already achieves **0 bytes/frame**
allocation in pure Kotlin, which was the layout benefit that actually mattered.

**3. Pure Kotlin buys determinism that C++ would cost us.** Since Java 17 all
JVM floating-point arithmetic is strict IEEE-754, and `Math.sqrt` is specified as
correctly rounded. The solver uses only `+ - * /` and `sqrt`, so **physics is
bit-identical across every device that runs the APK**. The spike confirms
bit-identical replay (`hashes MATCH`). A C++ solver built with the toolchain's
usual `-ffast-math`-adjacent defaults would give up exactly this, and ADR 0006
spends that determinism on QA's entire test strategy.

**Packaging answer for the Product Lead: no ABI splits exist, universal
packaging is free, and ~5MB holds comfortably.** Estimated release APK:

| component | size |
| --------- | ---- |
| Kotlin stdlib after R8 shrinking | ~0.5–1.0 MB |
| game + solver DEX | ~0.2–0.3 MB |
| shaders (GLSL source, text) | < 50 KB |
| textures / audio assets | 0 — everything is procedural |
| native libraries | **0** |
| **total** | **~2–4 MB** |

The brief's ~5MB target survives with room to spare, and it survives *because*
the art direction is procedural and the solver is not native.

## Alternatives considered

**NDK solver, all three ABIs** — rejected. This is the option the Security
Engineer's note assumed. It roughly triples the native payload for no benefit to
the client, whose device is `arm64-v8a`, and it breaks the size target. It also
brings the Android 15+ **16KB page size** requirement into scope, which is a
build-configuration trap that pure Kotlin simply does not have.

**NDK solver, `arm64-v8a` only** — rejected, but it is the credible alternative
and the one to revisit. The native payload would be small (~100–200 KB, not the
multi-MB blowup), and it is consistent with treating the Fairphone 6 as the
reference device. It lost on three counts: the measured budget does not need the
speed; it forfeits cross-device bit-identical determinism; and it would drop
support for 32-bit devices, which for a game whose stated floor is a 2020
mid-range phone is a real if unmeasured cost. It also adds a C++ toolchain,
JNI marshalling, and a second language to a team the client wants kept small.

**Kotlin/Native** — rejected. It would give native compilation without C++, but
it means a second Kotlin toolchain, an unfamiliar memory model, and per-ABI
libraries — every cost of the NDK option plus novelty, which is precisely what
the "boring technology" and "keep it simple" constraints rule out.

**Java rather than Kotlin for the hot loops** — rejected. Kotlin compiling
`FloatArray` loops produces essentially the same bytecode as Java `float[]`; the
spike is written in Kotlin and hits the numbers above. There is no win, and a
second language in the core would be pure cost.

## Consequences

**Easy.** One language, one toolchain, one build. No JNI boundary to marshal
across, debug, or crash on. No ABI matrix, no 16KB page alignment concern, no
per-device packaging. The solver runs unmodified on the desktop JVM, which is
what makes ADR 0008's framework-free core and QA's deterministic replay tests
possible at all — that benefit is large and it is a direct consequence of this
decision, not a side effect.

**Hard.** We are betting on ART's optimizing compiler for the hottest loop in the
product, and we are betting on an unverified derating factor. If the bet is
wrong, the fix is not a tweak — it is porting the solver across a JNI boundary.
We accept that risk because the estimated headroom is large (default tier at
2.5–5.9ms against a ~6ms allowance) and because ADR 0009's tiering degrades
gracefully rather than failing.

**Live with.** No SIMD. If a future feature genuinely needs several thousand
particles — a mode with many small pieces, say — this decision gets revisited
rather than worked around.

**Trigger to revisit:** at Milestone 1, when the reference configuration is run
on the Fairphone 6, if simulation at the default tier exceeds **8 ms/frame**.
That is the number that would mean the 3–7x band was optimistic. The response
would be, in order: drop to the low tier; if still short, `arm64-v8a`-only NDK
with a re-baselined APK size of ~3–4 MB. Both are recorded here so neither is
discovered at packaging time.
