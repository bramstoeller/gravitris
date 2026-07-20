# SPIKE — solver budget

**This is spike code. It is a measuring instrument, not production code, and it
must not be lifted into the product.**

Its job was to answer one question before the architecture was designed around a
guess: *what can a soft-body solver actually afford at 60fps?* Lift the **numbers**
and the **kernel shapes** from it. Do not lift the code — it has public mutable
arrays, no encapsulation, no error handling, and a benchmark harness welded to the
solver.

Results: [`results-host.txt`](results-host.txt). Decisions drawn from them:
ADR [0001](../../docs/adr/0001-xpbd-substepped-soft-body-solver.md),
[0002](../../docs/adr/0002-pure-kotlin-solver-no-ndk.md),
[0003](../../docs/adr/0003-contacts-and-stack-stability.md),
[0004](../../docs/adr/0004-coverage-band-occupancy-bitmap.md),
[0006](../../docs/adr/0006-fixed-timestep-determinism-and-refresh-rate.md),
[0009](../../docs/adr/0009-reference-device-and-quality-scaling.md).

## What it measures

| # | Experiment | Answer |
| - | ---------- | ------ |
| 1 | Cost vs particles / constraints / substeps | linear: `0.49ms × (particles/960) × (substeps/8)` |
| 2 | Stack stability vs substeps and stiffness | **8 substeps is the floor**; below it a settled pile jitters |
| 2b | Stability vs piece mass | stable to 8x mass — the difficulty ramp is safe |
| 3 | SoA vs AoS on identical work | only **1–3%** at our scale; 1.15x at 138k particles |
| 3b | Steady-state allocation | **0 bytes/frame** |
| 4 | Coverage-band cost | 0.006–0.018 ms — **free** |
| 5 | Determinism | bit-identical replay |
| 6 | Reference configuration | the number to re-run on a real device |

## ⚠ These are HOST numbers

x86-64, HotSpot 21, single-threaded, `taskset`-pinned. **Not on-device numbers.**
The 3–7x host→device derating used in the ADRs is a *reasoned estimate*, not a
measurement. See `/work/.team/blockers.md`.

Run-to-run variance on the host is roughly ±7%; do not read significance into
differences smaller than that.

## Closing the blocker

Experiment 6 prints a **reference configuration** — 60 bodies, 4x4 lattice, 8
substeps, 960 particles, 3 600 constraints — at a host p50 of **0.497 ms/frame**.
Run that exact configuration on the reference device (Fairphone 6) and divide.
That single ratio rescales every estimate in every ADR.

## Running it

Needs a JDK and the Kotlin compiler; neither was present in the container and both
were installed ad-hoc for the spike. This is **not** part of the product build —
DevOps owns the real, pinned Gradle setup.

```sh
kotlinc src/Solver.kt src/Scene.kt src/AoS.kt src/Bench.kt -include-runtime -d spike.jar
taskset -c 2 java -Xmx6g -XX:+UseSerialGC -cp spike.jar spike.BenchKt
```

Full run takes several minutes, dominated by the 138k-particle layout case.

## Bugs found while building it — worth knowing

Three defects in the *spike* produced convincing but wrong physics conclusions
before they were caught. All three are easy to reproduce in the real
implementation:

1. **Bodies seeded overlapping.** Initial placement left less than one particle
   diameter between bodies, so the contact solver converted the overlap into launch
   energy. This showed up as non-monotonic stability — *more* substeps looking
   *less* stable — and read exactly like a solver problem. It was a setup problem.
   Clamping an overflowing body back inside the well caused the same thing a second
   time.
2. **Ground friction computed penetration after resolving it**, so the depth was
   always zero and friction was effectively infinite.
3. **A per-frame `IntArray` allocation in the broadphase**, caught immediately by
   the allocation experiment.

The lesson for the real solver: **validate the scene before trusting the physics**,
and keep an allocation assertion in the test suite.
