# Proposed build order

Date: 2026-07-20 · Author: Architect · For the Product Lead to sequence into `backlog.md`

The organising goal: **get a phone into a human's hand feeling the squish as early
as possible.** That moment answers both of the project's real questions — does it
feel good, and does it hit framerate — and everything else is downstream of the
answers.

## The key insight

**Milestone 1 needs no clearing rule, no losing condition, no scoring, and no art
direction.** It needs a solver, a renderer that draws triangles, touch input, and
a build. That is why it can come early, and why it should.

Deferring the mechanic to Milestone 2 is not cutting corners — the coverage-band
rule and the losing condition are both *cheap* (ADR 0004 measures bands at 0.05%
of a frame) and neither can be tuned sensibly until someone has felt the physics.

## Sequence

### Stage 0 — foundation (blocks everything)
**DevOps.** Pinned reproducible Gradle build. Two modules per ADR 0008. The
`:core-sim` no-Android build check — write it now, or it is violated within a
week. One command to run, one to test. Debug APK installable on the Fairphone.

*Nothing else can start. This is the only true bottleneck in the plan.*

### Stage 1 — parallel (three tracks, no shared files)
| Track | Owner | Work |
| ----- | ----- | ---- |
| A | backend | `:core-sim` physics: particles, distance + area constraints, uniform-grid broadphase, contacts + friction, fixed tick + 8 substeps. Port the shapes from the spike; **do not port the spike code.** JVM tests for stability (settled KE) and determinism (seeded replay). |
| B | frontend | `:app` skeleton: GL ES 3.0 context, dynamic VBO + static IBO, flat-shaded triangles, edge-to-edge insets, drag/tap/swipe → `InputFrame`, impact haptics. |
| C | ux | Visual language against the ADR 0007 varying set and the ADR 0010 inset constraint. **Must confirm the varying list before Stage 3.** |

Tracks A and B meet only at `/work/docs/contracts.md`. They can genuinely proceed
without talking.

### Stage 2 — 🎯 **MILESTONE 1: "Squish Toy"**
Integrate A + B. One piece falls into an empty well. Drag it, drop it, watch it
squash and settle. Impact haptic. Flat colours, no procedural shading, no score,
no clearing, no losing.

**This is the milestone demo.** The client holds the phone and says whether it
feels heavy.

Also, and importantly: **run the reference benchmark here and close the derating
blocker.** Ship it as a hidden one-tap action in the debug build. This is the
moment the project's largest unknown becomes a number.

Decisions that get made or revisited on the evidence from this stage:
- is the material spongy enough, and at what compliance (ADR 0001)
- does the friction model make material feel sticky (ADR 0003 unease)
- is `GLSurfaceView` pacing adequate, or do we need `SurfaceView` + `Choreographer` (ADR 0007)
- does the solver hit budget, or do we drop a tier / revisit NDK (ADR 0002 trigger)

### Stage 3 — the mechanic
| Track | Owner | Work |
| ----- | ----- | ---- |
| A | backend | Piece spawning + sequence, lock detection via kinetic energy, coverage bands (ADR 0004), the clear rule, stack drop and re-settle. |
| B | frontend | Procedural shading pass 1 — gel/subsurface, noise grain, rim lighting. Band glow driven by `uBandFill`. **Profile the fragment shader and close the second blocker.** |

### Stage 4 — the game
| Track | Owner | Work |
| ----- | ----- | ---- |
| A | backend | Losing condition + overflow grace (ADR 0005), difficulty ramp, scoring. |
| B | frontend | Landing silhouette, screen shake, impact propagation, clear-release feedback. |
| C | all | Dev tuning panel — thresholds, compliance, grace window. **Needed before tuning, not after.** |

### Stage 5 — completion
Accessibility (reduced motion, colourblind-safe hues — both are configuration per
ADR 0007, not new code paths). Settings + defensive persistence (ADR 0010).
Startup quality calibration (ADR 0009). QA hardening. Signed release APK +
sideload instructions.

### 🎯 **MILESTONE 2: complete game**

## Dependency graph

```
Stage 0 ──┬── 1A backend physics ──┬── MILESTONE 1 ──┬── 3A mechanic ──┬── 4A rules ──┐
          ├── 1B renderer + input ─┘                 └── 3B shading ───┴── 4B feel ───┼── Stage 5 ── M2
          └── 1C ux spec ──────────────────────────────┘ (gates 3B)                   │
                                                          4C dev panel ───────────────┘
```

## What I would resist

- **Building the clear rule before Milestone 1.** It is cheap and it is untunable
  until the physics is felt. Building it early means tuning it twice.
- **Procedural shading before Milestone 1.** It is the largest unmeasured cost in
  the product; putting it in front of the feel test risks the demo slipping on
  work that is not what the demo is testing.
- **CI beyond a reproducible build.** The store is deferred; the audience is the
  client. One-command build and test is the requirement.
- **Any second game mode, level format, or tuning-parameter plugin system.** The
  client's standing instruction is to keep the first iteration simple, and none
  of these has a requester.
