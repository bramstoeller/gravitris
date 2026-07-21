# 0032 — Architect → Product Lead

Date: 2026-07-21 · Branch: `chore/inputframe-contract` · PR: #22

## What I did

**1. Signed the `InputFrame` contract change (boundary-crossing, my call per
`contracts.md` §5).** The control scheme moved from "drag + swipe-down hard-drop"
to "slide-to-aim → release-to-drop → rotate-while-falling". `InputFrame` is now
`{ dragX, rotate, drop }`; `hardDrop` + `hardDropVelocity` are removed and the
app-side `VelocityWindow` machinery goes with them.

- `docs/contracts.md`: §2 rewritten (new shape + phase-gated input model), §3
  documents the additive `SimState` fields (`activePiecePhase`,
  `positioningTicksRemaining`, `positioningWindowTicks`, `particlesPerBody`) and
  a new `PiecePhase` enum, §5 lists `PiecePhase`, new §6 change-log row.
- `.team/reviews/review-inputframe-contract.md`: the signed verdict (APPROVED)
  with the concrete verification.

Commit range: `2964a9f` (single commit on the branch). Docs only — no code.

## What I verified (not trusted)

- **No stranded consumer.** Grepped the tree: `hardDrop`/`hardDropVelocity` live
  only in the machinery being removed (`InputFrame.kt`, `Simulation.kt:553` +
  `applyHardDrop`, `PlayerIntent.kt`, `GestureRecognizer.kt:243`,
  `VelocityWindow.kt`) — plus 8 test files (see follow-up 1).
- **Phase model is sound and leak-free.** The recognizer stays phase-agnostic
  (pointer-up→`drop`, tap→`rotate`+`drop`, drag→`dragX`); the core disambiguates
  by `activePiecePhase`. Honours contracts §2 ("the core decides what they
  mean"); no game state crosses into `:app`.
- **Determinism survives.** One-shot `drop`/`rotate`, delta `dragX`, tick-counted
  positioning window (ADR 0013), exact `(x,y)→(y,-x)` rotation (ADR 0006).
  Removing the timestamped `VelocityWindow` input improves the posture.
- **"Squash feel preserved by construction" holds — and I found why.** Mass is
  per-*particle*, not per-body (`SoftBodyWorld.kt:208-209,223-224`:
  `particleMass = config.initialPieceMass; invMass = 1/particleMass`, identical
  for every particle). A 4·L² tetromino therefore gives each particle the same
  mass/invMass as today's L² cell; gravity is a mass-independent acceleration;
  compliance/rest-lengths/substeps unchanged → per-material response is
  identical. Nuance passed to Backend: this is exact for the *per-material*
  response only; *aggregate* landing deformation still scales with total mass and
  fall speed (a tall I-piece falls further, squashes more) — emergent-correct,
  not a retune.

## What I deliberately did NOT do

- **Did not author any ADR.** The two ADRs (rotation semantics, piece lifecycle)
  are the Backend's; my job is to review them. See "Open / pending".
- **Did not touch `SimConfig`'s `lattice`/tier fields** (still `5` + ADR 0009
  language on main) even though the unmerged pinned-lattice ADR supersedes them —
  out of scope here and entangled with the numbering issue below.
- **Did not merge.** Yours to land.

## Two follow-ups with owners (flagged, not blocking the sign-off)

1. **Test migration — required, or it reddens `main`.** 8 test files use
   `hardDrop=true; hardDropVelocity=30f` (9f in `DeterminismTest`) as the
   *mechanism* to impart a fast impact for deformation/haptics/compression
   coverage: core-sim's `DeformationTest`, `DeterminismTest`,
   `SolverBehaviourTest`, `BroadphaseMarginTest`, `CoreSimHardeningTest`; app's
   `CompressionRangeTest`, `ImpactEnergyRangeTest`, `SquishToyTest`,
   `GestureRecognizerTest`. The removal PRs (Backend for core-sim, Frontend for
   app) must supply a replacement impact-velocity path. Told the Backend directly.

2. **ADR numbering collision — needs your reconciliation before the tetromino
   work merges.** `main`'s 0011 = silhouette. The unmerged `chore/architecture`
   branch carries a *different* 0011 (piece-geometry / pinned-lattice) plus 0012
   (restitution) and 0013 (frame-overrun / no-wall-clock) — and the Backend's
   design correctly cites 0013 and the pinned lattice as canonical. So
   `chore/architecture` is meant to land, but merging it as-is puts two 0011s in
   `docs/adr/`, and `.team/decisions.md` only indexes to 0011. The Backend's two
   new ADRs also cannot safely take 0012/0013. This is a merge-time landmine that
   wants an owner (renumber `chore/architecture`'s 0011→0012+ and merge, or
   decide otherwise). Not blocking PR #22.

## Open / pending

- **The two Backend ADRs (rotation, lifecycle) are NOT yet reviewed** because
  they are not yet pushed — `origin/feat/tetromino-pieces` does not exist and the
  branch has no `.kt`/new-ADR files yet. I have coordinated with the Backend
  (agent `a8725061881b6f102`) and asked them to confirm ADR numbers and push. My
  pre-review of the *design* (from their conversation file) found it sound and
  consistent with 0005/0006/0013; the signed ADR verdicts are outstanding until
  the files land. If this subagent run ends before they push, the ADR review is
  the one piece of the DoD still open and must be picked up.

## Uneasy about

- The main-vs-`chore/architecture` ADR divergence is wider than the numbering:
  `contracts.md` on main still says `lattice = 5` and "quality tier (ADR 0009)",
  while the unmerged ADR 0011 pins lattice at 4 and supersedes the tiers. The
  Backend's tetromino design treats `config.lattice` as a live tier value. Which
  ADR set is canonical needs settling, or `:app`/`:core-sim` will be built
  against two different lattice stories.

---
*— **Architect***
