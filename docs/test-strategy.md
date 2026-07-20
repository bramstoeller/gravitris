# Test strategy — `:core-sim`

Owner: QA Engineer. Scope of this document: the pure-Kotlin physics/game core
(`:core-sim`). The GL render path and `:app` are out of scope here and are the
subject of a separate strategy once a frame has rendered.

## How to run

```
./gradlew :core-sim:test          # the whole core suite, one command
./gradlew :core-sim:test --tests "gravitris.CoreSimHardeningTest"   # one class
```

All tests are JVM-only (ADR 0008): no device, no emulator, no renderer. They run
in seconds and are deterministic, which is what makes them usable as replay
regressions (ADR 0006).

## What is covered, at which level

The core has no network, no persistence and no concurrency, so there is no
integration or end-to-end layer to build yet — everything is a unit/behaviour
test against the deterministic solver. The suite is behaviour-first: it asserts
what the brief and ADRs promise (containment, softness, stability, determinism,
zero allocation), not the shape of the code that delivers them.

| Area | Where | What it pins |
| ---- | ----- | ------------ |
| Lattice topology | `SolverBehaviourTest` | constraint/particle counts match ADR 0001's cost table |
| Containment | `SolverBehaviourTest` | a piece stays in the well at any fall speed; walls/floor exact |
| No tunnelling (lattice 5) | `SolverBehaviourTest` | a hard drop at v=30 does not pass through a resting piece |
| Softness | `SolverBehaviourTest` | material compresses under load but does not collapse |
| Impacts/haptics | `SolverBehaviourTest` | landing fires one event per body; resting fires none |
| Scene validation | `SolverBehaviourTest` | overlap/out-of-well/degenerate config all throw |
| Input kinematics | `SolverBehaviourTest` | drag/rotate move without injecting velocity; clamped to well |
| Stability | `StabilityTest` | a settled pile stays quiet; heavier/spongier material still settles |
| Substep cliff | `StabilityTest` | 2 substeps diverges 8 orders vs 8 substeps (the cliff, not a boundary) |
| Determinism | `DeterminismTest` | bit-identical replay; stateless across step loops; InputFrame identity irrelevant |
| Zero allocation | `AllocationTest` | steady-state tick and input-driven tick allocate ≤ 8 bytes/frame |
| **Empty well** | `CoreSimHardeningTest` | the `n == 0` early-out is a safe no-op and recovers |
| **Body capacity** | `CoreSimHardeningTest` | the ceiling throws cleanly; a packed well is finite and contained |
| **Long idle hold** | `CoreSimHardeningTest` | a settled pile does not wake or recruit contacts over 6000 frames |
| **Determinism at scale** | `CoreSimHardeningTest` | a 40-body scene with drag/drop/rotate/wall contact replays bit-identically |
| **Zero gravity** | `CoreSimHardeningTest` | a weightless body at rest manufactures no energy |
| **Stacked-body contact** | `CoreSimHardeningTest` | a body settling onto another stays rigid across cell rows |
| Rigid contact per tier | `BroadphaseMarginTest` | a hard drop stays rigid at lattice 4/5/6 (lands via PR #10 — see findings) |

## Deliberately not tested, and why

- **Cross-device bit-identity.** ADR 0006 claims determinism across devices; the
  JVM suite proves it only within one JVM. The `sqrt`/no-transcendental argument
  is sound, but "no ARM device has been measured" is an honest gap. Closing it
  needs the on-device benchmark harness, tied to the same blocker as ADR 0009's
  derating numbers.
- **Residual kinetic energy as a fidelity measure.** It is not one (handoff
  0008). Stability tests read KE only as a binary "did it settle", with generous
  thresholds and convergence over a long hold, never as "how good is the solve".
- **A substep boundary guard (e.g. "7 fails").** Between 4 and 8 substeps there
  is no signal in either scene shape; a boundary assertion would be asserting
  noise. The suite asserts the 2-vs-8 cliff instead.
- **The GL render path and interpolation alpha.** Out of module. Flagged by the
  Architect (handoff 0008) as the highest-value target once rendering exists; a
  wrong interpolation alpha produces judder that looks like a physics bug, so it
  must be ruled out in the render path before the solver is reopened.

## Findings from this pass

**One real defect, found on the pre-game-landing solver and already fixed on
`main`.** Hunting `feat/core-sim`, I found contacts were not rigid at lattice 6
(the ADR 0009 high-detail tier): a hard drop at terminal speed sank one body
**43.7% of a particle diameter** into another at lattice 6, against **0%** at
lattice 4/5, because the broadphase was rebuilt once per frame and at lattice 6
the shrunken radius let a piece drift 1.39 cells/frame past the 3×3 stencil. The
solver rework that landed with the game rebuilds the broadphase **every substep**
(commit `0571697`), which closes it — I verified `BroadphaseMarginTest` green on
`main` (lattice 6 at 0.90% vs the 15% budget). It now lands as a live regression
guard via Backend Engineer **PR #10**, not this branch. Full write-up:
`.team/reviews/qa-broadphase-margin.md`.

Everything else held under adversarial load: the previously-flagged settled-pile
drift did **not** reproduce (a 16-body pile is flat at 140 contacting particles
and bounded energy over 6000 idle frames); determinism survived a 40-body
stressed scene; the empty well, a fully packed well, and zero gravity are all
safe. `CoreSimHardeningTest` (this branch) carries those checks.
