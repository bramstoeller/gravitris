# Squish — architecture

Date: 2026-07-20 · Author: Architect · Status: proposed, for Gate 2

An Android falling-block game where pieces are deformable soft bodies that squash
and settle under real physics, and horizontal bands clear when ~90% filled by
material rather than when a perfect row forms.

## Shape of the system

```
┌──────────────────────────────────────────────────────────────┐
│  :app        Android application (Kotlin, API 29..36)        │
│                                                              │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────────┐    │
│  │  Input     │  │  Renderer  │  │  Shell               │    │
│  │  drag/tap/ │  │  GL ES 3.0 │  │  lifecycle, insets,  │    │
│  │  swipe     │  │  1 dynamic │  │  haptics, settings,  │    │
│  │            │  │  VBO, 1-2  │  │  dev tuning panel,   │    │
│  │            │  │  draw calls│  │  startup calibration │    │
│  └─────┬──────┘  └─────▲──────┘  └──────────────────────┘    │
│        │ InputFrame    │ SimState (read-only arrays)         │
└────────┼───────────────┼─────────────────────────────────────┘
         │               │            one-way dependency
┌────────▼───────────────┴─────────────────────────────────────┐
│  :core-sim   pure Kotlin/JVM — no Android, no framework      │
│                                                              │
│  game/    pieces, spawning, lock detection, coverage bands,  │
│           clear rule, overflow/loss rule, scoring            │
│                                                              │
│  physics/ XPBD solver: integrate → distance → area →         │
│           contacts → bounds → velocities, x8 substeps        │
│           uniform-grid broadphase, SoA FloatArrays           │
└──────────────────────────────────────────────────────────────┘
```

**One rule holds the design together: `:core-sim` has no Android dependency**,
enforced by a build check. Everything else follows — deterministic JVM tests, no
device needed for physics work, backend and frontend able to work in parallel
from day one.

## What each part owns

| Component | Owns | Does not own |
| --------- | ---- | ------------ |
| `physics/` | Particles, constraints, contacts, broadphase, integration | Game rules, what a "piece" means |
| `game/` | Pieces, spawn, lock, bands, clearing, loss, score | How anything is drawn or felt |
| Renderer | Vertex buffers, shaders, the procedural look, band glow | Any simulation state |
| Input | Gestures → `InputFrame` | Interpreting them as game actions |
| Shell | Lifecycle, insets, haptics, persistence, calibration | Game rules |

## How it communicates

One seam, one direction. The shell advances the simulation and reads its state;
the simulation never calls back.

```
each display frame:
  accumulator += clamp(frameDelta, max 4 ticks)
  while accumulator >= 1/60:
      sim.step(inputFrame)        // fixed tick, 8 substeps, deterministic
      accumulator -= 1/60
  render(sim.state, alpha = accumulator / tick)
```

Full signatures: [`contracts.md`](contracts.md).

## Where state lives

| State | Lives in | Lifetime |
| ----- | -------- | -------- |
| Particle positions, velocities, constraints | `:core-sim`, SoA `FloatArray`s | the run |
| Band fill, score, phase | `:core-sim` `SimState` | the run |
| Personal best, settings, quality tier | `SharedPreferences` | install |
| Vertex/index buffers, shaders | GPU, rebuilt from sim state | GL context |

There is no database, no cache layer, no queue, no network, no background
service, and no third-party SDK. **All of these are deliberate absences** — the
product needs none of them, and they should not appear by drift.

Steady state allocates **0 bytes per frame** (measured), which is what keeps GC
pauses out of a 16.67 ms budget.

## Deployment

Single **signed universal APK**, sideloaded — the Play Store is deferred. No
native libraries, therefore no ABI splits (ADR 0002). Estimated size **~2–4 MB**
against the brief's ~5 MB target. Reproducible headless Gradle build, pinned
toolchain, one documented command.

## The numbers this rests on

Measured in `/work/spike/solver-budget/` (host x86-64, HotSpot, single-threaded):

| Finding | Result |
| ------- | ------ |
| Solver cost | `0.49 ms × (particles/960) × (substeps/8)`, linear |
| Substep floor for a stable stack | **8** — below this a settled pile jitters |
| "Spongy" affordable? | **Yes.** Cost is independent of stiffness across a 1000x range |
| SoA vs AoS at our scale | **1–3%**, not the large win the brief assumed |
| Coverage bands | 0.006–0.018 ms — 0.05% of a frame, effectively free |
| Allocation per frame | 0 bytes |
| Determinism | bit-identical replay |

**Estimated** on the reference device (Fairphone 6), medium tier: 2.5–5.9 ms
solver against a 16.67 ms frame. Derating factor 3–7x is an **estimate, not a
measurement** — see ADR 0009 and `blockers.md`.

The largest remaining performance unknown is **the fragment shader, not the
solver** — a reversal of the project's founding assumption.

## Decisions

| ADR | Decision |
| --- | -------- |
| [0001](adr/0001-xpbd-substepped-soft-body-solver.md) | XPBD with 8 substeps, SoA layout |
| [0002](adr/0002-pure-kotlin-solver-no-ndk.md) | Pure Kotlin solver, no NDK |
| [0003](adr/0003-contacts-and-stack-stability.md) | Particle contacts, friction, substep floor |
| [0004](adr/0004-coverage-band-occupancy-bitmap.md) | Coverage bands via occupancy bitmap |
| [0005](adr/0005-losing-condition-overflow-with-settle-grace.md) | Loss: spawn overflow with settle grace |
| [0006](adr/0006-fixed-timestep-determinism-and-refresh-rate.md) | Fixed 60Hz tick, deterministic, 60Hz render |
| [0007](adr/0007-rendering-deformable-meshes-gles3.md) | Dynamic VBO, static IBO, shading contract |
| [0008](adr/0008-module-boundaries.md) | Two modules, framework-free core |
| [0009](adr/0009-reference-device-and-quality-scaling.md) | Fairphone 6 reference, startup tiers |
| [0010](adr/0010-android-platform-baseline.md) | API 29–36, edge-to-edge, universal APK |
