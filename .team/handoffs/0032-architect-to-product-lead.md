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

**3. Reviewed the two Backend ADRs — both APPROVED.** They landed on
`origin/feat/tetromino-pieces` @ `90d7c2a`, numbered 0015 (tetromino geometry /
bonded cells) and 0016 (lifecycle + rotation, folded together). Signed verdict:
`.team/reviews/review-tetromino-adrs-0015-0016.md`. I checked the
determinism-critical claims against the pushed code, not the prose:

- Phase gating is real and leaks no phase into `:app` (`Simulation.kt:627-636`).
- The positioning window is tick-counted (`:208-211`, cites ADR 0013); the freeze
  reuses the existing `invMass==0` short-circuit (`XpbdSolver.kt:208`).
- Rotation is exact `(x,y)→(y,-x)` (ADR 0006), overlap rejected outright, and the
  fall velocity survives a rotate — verified: `applyRotate` leaves `velX/velY`
  untouched and the solver carries velocity there (`integrate` line 210).
- Numbering 0015/0016 dodges the 0012/0013 collision and `decisions.md` is
  updated for both. The 0014 gap is now filled by the lattice-pin ADR below.

## Lattice tier — DECIDED and recorded (ADR 0014)

You decided: pin lattice 4, no tier selection. Recorded as **ADR 0014** ("Pin the
lattice at 4; the piece's material extent is the gameplay constant"), with the
rationale you gave. In the same commit I reconciled the record:

- **ADR 0009** status updated — its startup tier selection (§3) and per-tier
  `clearThreshold` are superseded by 0014; its reference device, dropped 2020
  floor, pinned substeps and render-side scaling still stand.
- **ADR 0013** — its three references to "ADR 0011" for a pinned particle count /
  rejected tiers were dangling on main (main's 0011 is the silhouette ADR); fixed
  to point at ADR 0014, which is the pin they assumed.
- **decisions.md** — added the missing rows for 0012, 0013 and 0014 (the
  cherry-pick had landed the ADR files but not their index rows) and marked
  0009's tier part superseded.
- **contracts.md** — `lattice` default shown as 4 (pinned, ADR 0014);
  `clearThreshold` noted as a single constant, not per-tier.

The Backend owns the matching `:core-sim` code default (`SimConfig.lattice = 4`);
I've told them, and confirmed no sizing-formula refactor is needed (pinning makes
`pieceExtent = 2.40` a single constant by construction).

**Your look question, answered from the numbers:** lattice 4 does **not** degrade
the approved squash feel, because the reference-device feel the client already
approved was produced *at lattice 4* — it is the only tier that fit the device, so
the on-device tuning you approved was lattice-4 tuning. Each tetromino cell is the
same lattice-4 material. The open risk is purely *visual coarseness* at tetromino
scale (a cell is a 4×4 particle grid), which is a render concern — if it reads as
faceted in the emulator, the fix is render-side (draw-time subdivision / shader),
not more sim particles. So: no feel veto from me; your emulator look-check is the
right gate.

The full pre-decision analysis (why runtime tiers couldn't ship, the leak, the
dangling references) is in `.team/reviews/review-lattice-tier-tetromino.md`.

## Item 2 (determinism) — verified handled

The Backend's `DeterminismTest` migration is real: `slamActivePiece(9f)` replaces
the removed `hardDropVelocity` probe, and `runPhasedGame` is a seeded replay
through positioning + early-drop + falling-rotate asserting bit-identical output.
The determinism string I flagged is covered in the branch.

## Open / pending

- **No PR for `feat/tetromino-pieces` yet** — the ADRs are on the branch, not a
  PR. I asked the Backend to tag me when they open one so I can drop the approval
  on the PR too. My review record stands regardless.
- **`DeterminismTest` not re-run by me.** QA must confirm it exercises a seeded
  replay through positioning + falling + rotation, and that it (and the 7 other
  test files) are migrated off `hardDropVelocity`. This is the hard string on the
  whole rework for `main` to stay green (follow-up 1 above).

## Uneasy about

- The main-vs-`chore/architecture` ADR divergence is wider than the numbering:
  `contracts.md` on main still says `lattice = 5` and "quality tier (ADR 0009)",
  while the unmerged ADR 0011 pins lattice at 4 and supersedes the tiers. The
  Backend's tetromino design treats `config.lattice` as a live tier value. Which
  ADR set is canonical needs settling, or `:app`/`:core-sim` will be built
  against two different lattice stories.

---
*— **Architect***
