# Review — `InputFrame` contract change (boundary sign-off)

Reviewer: **Architect** · Date: 2026-07-21 · Branch: `chore/inputframe-contract`

Boundary: `:app` ↔ `:core-sim`. This is a signature change, so per
`docs/contracts.md` §5 it crosses the module boundary and is the Architect's
call. The consumer (Frontend) had already confirmed the shape with the producer
(Backend); this note is the boundary sign-off, reviewing the contract as an
artifact — not either side's code.

## The change

`InputFrame` goes from `{ dragX, rotate, hardDrop, hardDropVelocity }` to
`{ dragX, rotate, drop }`. `hardDrop` and `hardDropVelocity` are removed; the
app-side `VelocityWindow` machinery that fed `hardDropVelocity` is deleted.
Rationale: the control scheme changed to slide-to-aim → release-to-drop →
rotate-while-falling. Release *is* the drop and the fall is plain gravity, so the
flick-velocity boost is redundant by construction.

Four additive `SimState` fields land in the same change (`activePiecePhase`,
`positioningTicksRemaining`, `positioningWindowTicks`, `particlesPerBody`).

## Verdict: **APPROVED**

Signed off. Findings and the two required follow-ups below.

## What I checked

### 1. The removed fields have no consumer that is left stranded

Grepped the tree (`git grep` on the branch). Every reference:

**Production (4 sites, all part of the machinery being removed by design):**
- `core-sim/.../InputFrame.kt` — the field definitions and `clear()`.
- `core-sim/.../Simulation.kt:553` — `if (input.hardDrop) applyHardDrop(body, input.hardDropVelocity)`; plus `applyHardDrop` itself (~L696).
- `app/.../input/PlayerIntent.kt` — `requestHardDrop`, `hardDropVelocity` latch, `drainInto` copy (L48–69).
- `app/.../input/GestureRecognizer.kt:243` + `VelocityWindow.kt` — swipe-down recognition and the trailing-window velocity estimate.

Nothing reads `hardDropVelocity` outside this cluster. No third module, no
persistence, no telemetry. The removal is self-contained across the two modules.

**Tests (8 files) DO read/write the fields — this is the one real consequence.**
`DeformationTest`, `DeterminismTest`, `SolverBehaviourTest`, `BroadphaseMarginTest`,
`CoreSimHardeningTest` (core-sim) and `CompressionRangeTest`,
`ImpactEnergyRangeTest`, `SquishToyTest`, `GestureRecognizerTest` (app) all use
`hardDrop = true; hardDropVelocity = 30f` (or `9f` in `DeterminismTest`) as the
*mechanism* to impart a fast impact for deformation / haptics / compression
coverage. Removing the field removes their way to reach impact speed. This is
exactly the "forbid something without supplying what makes the ban livable" trap
the constitution warns about — so it is a **required follow-up, not optional**:
Backend (core-sim tests) and Frontend (app tests) must supply a replacement path
for imparting impact velocity (drop from height under gravity, or a test-only
seed-with-velocity helper) in the same PRs that remove the field, so `main`
stays green. `DeterminismTest` specifically must be rewritten against `drop`/
`rotate` since its whole point is the input replay path.

### 2. The phase-gated input model is sound and does not leak phase into `:app`

The current core already interprets fields itself (`applyInput`,
`Simulation.kt:548–554`), which is what `contracts.md` §2 promises ("the core
decides what they mean"). The new model keeps that: the recognizer is
phase-agnostic — pointer-up → `drop`, tap → `rotate` **and** `drop`, drag →
`dragX` — and the core disambiguates by `activePiecePhase` (POSITIONING honours
`dragX`/`drop`, ignores `rotate`; FALLING honours `rotate`, ignores
`dragX`/`drop`). The recognizer never branches on phase, so no game state
crosses into `:app`. This is the correct side of the boundary for the
disambiguation to live on, and it is consistent with §2. The tap-emits-both
encoding is deliberate and clean: one gesture fact, meaning assigned by the core.

### 3. The additive `SimState` fields are additive and framework-free

`PiecePhase` is a plain `enum` in `:core-sim`; the three counters are `Int`.
Per §5, `SimState` additions do not need a signature — I documented them anyway
(§3, §6) because they shipped alongside the signature edit and the consumer needs
them. `particlesPerBody` is the right thing to expose: with tetrominoes the
per-body particle count goes `lattice² → 4·lattice²`, and any `:app` code that
computed a stride as `bodyLattice²` would silently break. Supplying the value is
the "what do I give them instead" answer — good.

### 4. Determinism survives the change (ADR 0006 / ADR 0013)

- `drop` and `rotate` are one-shot booleans consumed on the tick read; `dragX`
  is a float delta. No wall-clock enters the sim through `InputFrame`.
- The POSITIONING window is tick-counted (`positioningTicksRemaining`), never
  wall-clock — consistent with ADR 0013.
- Rotation stays exact: `applyRotate` uses `(x,y) → (y,-x)`, no trigonometry,
  no lookup drift (`Simulation.kt:593–597`) — consistent with ADR 0006.
- Removing `hardDropVelocity` removes an app-side estimate derived from
  *timestamped* touch samples. The sim was already deterministic given the value,
  but deleting the machinery removes a real-time-dependent input entirely. Net
  determinism posture improves.
- The one-shot clearing discipline (`:app` clears, `step` never writes
  `InputFrame`) is preserved and re-stated in the updated §2. It stays
  load-bearing for `InputFrame`-sequence replay.

## Follow-ups owned outside this note (flagged, not blocking the sign-off)

1. **Test migration (required, above).** Backend + Frontend supply a
   replacement impact-velocity path in the removal PRs.
2. **ADR numbering collision.** The Backend's design references ADR 0013
   (no-wall-clock) and a pinned lattice, but ADRs 0011-piece-geometry / 0012 /
   0013 live only on the unmerged `chore/architecture` branch, while `main`'s
   0011 is the silhouette ADR. Two different 0011s exist. The Backend's two new
   ADRs must not take 0012/0013. This is the Product Lead's to reconcile before
   either branch merges; raised so it is not discovered at merge time.

---
*— **Architect***
