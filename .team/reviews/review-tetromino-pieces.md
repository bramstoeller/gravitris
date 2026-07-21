# Review: feat/tetromino-pieces (PR #24)

Verdict: approve-with-comments
Range: `origin/main` `3ec22aa` .. `origin/feat/tetromino-pieces` `dce2c7d`

**Commit-3 delta (`dce2c7d`, reviewed after the initial verdict).** Two of my
non-blocking comments are addressed and one is only partly addressed — details in
"Commit 3 delta" below. `:core-sim` re-verified on a cold merge: 84 tests, 0
failures, 0 skipped, with the new lattice-4 default. Verdict unchanged: clear to
land as the geometry half.

The tetromino geometry (ADR 0015) and the positioning→falling control lifecycle
(ADR 0016): seven shapes as four bonded `L×L` soft-body cells, spawning, sliding,
dropping, falling, rotating (footprint swap), settling and clearing, plus the
`InputFrame{dragX,rotate,drop}` / `PiecePhase` / additive `SimState` contract. I
reviewed the `:core-sim` geometry and the contract, and verified it against a
**cold merge onto current `main`**. The `:app` red is known and by design (the
removed `hardDrop`; `:app` fixes live on the Frontend's PR #23, green combined) —
I did not chase it. Clear to land as the geometry half; comments are non-blocking.

## Blocking

None.

## Verified — the geometry and contract are correct

- **`:core-sim` is genuinely green.** Cold-merged onto `main` (`3ec22aa`) with no
  conflicts; `:core-sim:test` BUILD SUCCESSFUL. `TetrominoShapeTest` 8/8,
  `DeterminismTest` 8/8. The CI red is confined to `:app` (it references the
  removed `hardDrop`/`hardDropVelocity`), as stated.
- **Determinism across the new phase machine + rotation.** `runPhasedGame` drives
  a *running seeded game* through positioning-slide + falling-rotate + early-drop
  and asserts a bit-identical fingerprint across two runs
  (`assertArrayEquals`, seed 424242, 1500 frames). The scripted replay also swaps
  the removed `hardDrop` for `slamActivePiece(9f)` and keeps its split-run and
  fresh-`InputFrame` identity checks. This is the property the whole replay
  strategy rests on, and it holds.
- **The seven shapes are correct, distinct tetrominoes.** I checked each layout by
  hand (cy-up): 0 I (flat bar), 1 O (2×2), 2 T, 3 S, 4 Z, 5 J, 6 L — all correct,
  all four orthogonally-connected cells. The archetype order is frozen and matches
  the palette identity indices. `TetrominoShapeTest` pins four-connected-cells (BFS
  over `neighbour`), the constant `4·L²` particle count, one body per piece, the
  seam weld (facing cells stay ~one spacing apart after settling, not fragmenting),
  and `particleEdge` marking the true outline not internal seams.
- **Squash/settle feel preserved by construction — verified, with a caveat.** Each
  cell is the same `L×L` lattice at the same spacing/radius, and the solver
  constants (`distanceCompliance`, `areaCompliance`, substeps) are unchanged, so
  per-material response is identical. The inert tree-seam padding is proven
  harmless: a weightless tree shape seeded at rest stays at rest (KE < 1e-3 over
  400 ticks), so the self-referential constraints inject no motion. The *aggregate*
  differs — a single cell squashes ~9% vs a lone block's ~15% because seam
  neighbours share load — which the handoff records the Architect confirmed. Fair
  claim: per-material unchanged, aggregate necessarily stiffer for a 4-cell piece.
- **Coverage-band clearing works on shaped pieces, non-trivially.** The shaped-clear
  test runs the *real* game (`start()`, seeded dealer, actual tetrominoes),
  accumulates a pile, and asserts a clear fires by watching `bodyCount` drop — a
  clear removes whole bodies, so a decrease can only be a real clear. It uses an
  easy `clearThreshold = 0.6` to stay reachable in 8000 ticks, but exercises the
  genuine shaped clear path end to end, not a degenerate one.
- **Lattice-agnostic geometry; nothing hardcodes a tier.** `SoftBodyWorld` derives
  everything from `config.lattice` — `particlesPerCell`, `cellDistance`, `cellArea`,
  `seamDistance`, `seamArea`, particle placement and triangle indices are all
  formulas in `lattice`, no magic 5. Exercised at 4/5/6 by `ContractTest`,
  `ContactGapTest` and `BroadphaseMarginTest` (all loop the three tiers and pass).
  Default `lattice` stays 5; the tier pin is ADR 0014's call, correctly not baked
  into this geometry.
- **Contract additions are additive and well-shaped.** `InputFrame` is now
  `{dragX, rotate, drop}` (hard-drop gone), phase-gated in the *core*
  (`applyInput`: POSITIONING slides/drops, FALLING rotates), read-don't-mutate
  preserved for replay determinism. `SimState` gains `particlesPerBody`,
  `activePiecePhase`, `positioningTicksRemaining/WindowTicks` and the `PiecePhase`
  enum — all additive, with the guarantee `activePiecePhase == POSITIONING ⇒
  activePieceBody >= 0` so a stray drag can never move a non-positioning piece.

## Commit 3 delta (`dce2c7d`)

- **Lattice-4 pin — clean.** `SimConfig.lattice` default `5 → 4`, well defaults
  `10×20 → 20×40`, with docs deferring the pin story to ADR 0014 ("accepts 4|5|6,
  shipping tier pinned at 4, no runtime tier selection"). It is a **value-only**
  default change — the geometry stays fully parametric, no tier logic is
  hardcoded. Re-verified: `:core-sim` 84 tests, 0 failures, 0 skipped on a full
  `--rerun-tasks` (test configs are explicit, so only the game default moved).
- **`triangleIndices` doc → per-cell — correct.** Now documents cell-local reuse:
  a body is `particlesPerBody / bodyLattice²` cells, draw the index set per cell
  at vertex offset `k · bodyLattice²`; values unchanged ("a cell *is* the old
  block"). Matches the per-cell extrusion and the B2 seam-not-triangulated /
  `particleEdge`-0-on-seams rendering. Aligns the contract with what the Frontend
  consumes.
- **`slamActivePiece` — doc hardened, but the annotation was NOT added.** The doc
  is now emphatic ("Test/probe only — MUST NOT be called from game or production
  code"), which strengthens the structural guarantee. But commit 3 did **not**
  add any `@VisibleForTesting`; it explicitly declines it, reasoning that the
  annotation "lives in `androidx.annotation`" which `:core-sim` cannot depend on.
  That reasoning overlooks the pure-JVM `org.jetbrains.annotations.VisibleForTesting`,
  which needs no Android dependency and is the correct lever. So the annotation
  half of the Architect's nit remains open — see below. This does not change the
  verdict: the hard-drop control path is closed structurally (no `hardDrop` on
  `InputFrame`, `applyInput` never calls `slam`) and the doc now says so
  forcefully.

## Should fix (non-blocking)

- **`slamActivePiece`: the `@VisibleForTesting` annotation is still absent after
  commit 3.** The doc is now strong (commit 3), and the control path is closed
  structurally, so this is genuinely optional — but if the Architect wants the
  compiler-level guard their nit asked for, it *is* achievable: add pure-JVM
  `org.jetbrains.annotations.VisibleForTesting`. Commit 3 declined the annotation
  on the mistaken grounds that it only exists in `androidx.annotation` (which
  would indeed trip `CheckNoAndroidDependency` / ADR 0002); the JetBrains one does
  not. Not a merge blocker either way.
- **Consumer sign-off on the `InputFrame`/`SimState` contract isn't separately
  recorded.** The ADRs 0015/0016 are Architect-approved and PR #23 (the Frontend's
  consumer) is green combined, which is strong evidence the shell builds against
  it — but there's no consumer-side confirmation on record the way band-contract,
  FrameDriver and the `Phase` contract each got one. Worth a one-line sign-off from
  the Frontend on the drop/rotate phase-gate and `particlesPerBody`/countdown
  fields, since you're landing the two halves together.

## Notes (non-blocking)

- The backend flagged (handoff 0029, "uneasy about") that rotating a *settled*
  tetromino is energetic; in the game rotation only happens while falling, so it is
  a stress-case, but a good adversarial target for QA — take them up on it.

## What is good

- Rotation as a runtime position transform (ADR 0016) rather than a per-orientation
  shape table means there is no second layout to keep in sync — the frozen cell
  table is the single source. The blocked-rotation *rejection* (rather than forcing
  the turn and letting the contact solver eject the overlap) is exactly the
  launch-energy trap the earlier drag work already taught, applied correctly here.
  And the contract does the "supply the value that makes the ban livable" thing the
  constitution asks: `particlesPerBody` published alongside the warning not to size
  buffers from `bodyLattice²`.

---
*— **Code Reviewer***
